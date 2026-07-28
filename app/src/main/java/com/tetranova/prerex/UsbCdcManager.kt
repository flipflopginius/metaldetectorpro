package com.tetranova.prerex

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.ContextCompat
import kotlin.time.Duration.Companion.milliseconds

class UsbCdcManager(private val context: Context) {
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _telemetryChannel = Channel<TelemetryData>(
        capacity = 512,  // ★ aumentato per 500 Hz
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val telemetryFlow: Flow<TelemetryData> = _telemetryChannel.receiveAsFlow()

    /**
     * Svuota qualunque campione telemetrico già in coda nel canale, senza elaborarlo.
     * Da chiamare subito prima di avviare una nuova raccolta "pulita" (es. pumping) —
     * il canale ha capacità 512 ed è possibile che contenga campioni prodotti PRIMA
     * dell'avvio esplicito della raccolta (es. durante una breve interruzione del
     * consumatore principale, o semplicemente il normale funzionamento nel tempo tra
     * un'azione dell'utente e l'altra). Senza questo svuotamento, quei campioni vecchi
     * verrebbero elaborati tutti insieme, in un solo istante, appena la nuova raccolta
     * inizia a leggere dal canale — dando l'impressione che il processo si completi
     * "da solo" senza che l'utente abbia ancora mosso nulla.
     */
    fun drainTelemetryBacklog() {
        var drained = 0
        while (_telemetryChannel.tryReceive().isSuccess) {
            drained++
        }
        if (drained > 0) {
            Log.d(TAG, "🧹 Svuotati $drained campioni telemetrici residui dal buffer")
        }
    }

    private val _consoleChannel = Channel<String>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val consoleFlow: Flow<String> = _consoleChannel.receiveAsFlow()

    private val _params = MutableStateFlow(DeviceParams())
    val params: StateFlow<DeviceParams> = _params.asStateFlow()

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var readJob: Job? = null
    lateinit var parser: SerialParser

    private val manualDisconnect = AtomicBoolean(false)
    private val connectMutex = Mutex()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tetranova.prerex.USB_PERMISSION"
        private const val TAG = "UsbCDC"
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                }
                if (granted && device != null) {
                    Log.d(TAG, "Permesso USB ottenuto")
                    scope.launch { connect(device) }
                } else {
                    _connectionStatus.value = ConnectionStatus.Error("Permesso USB negato")
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)

        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED // Usa la costante di ContextCompat
        )
    }

    fun findDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            UsbSerialProber.getDefaultProber().probeDevice(device) != null
        }
    }

    suspend fun connect(device: UsbDevice) = connectMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_connectionStatus.value is ConnectionStatus.Connected && serialPort != null) {
                Log.d(TAG, "connect(): già connesso, salto riconnessione ridondante")
                return@withContext
            }

            if (!usbManager.hasPermission(device)) {
                withContext(Dispatchers.Main) {
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context, 0, intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(device, pendingIntent)
                }
                _connectionStatus.value = ConnectionStatus.Connecting
                return@withContext
            }

            _connectionStatus.value = ConnectionStatus.Connecting
            disconnectLocked()
            manualDisconnect.set(false)

            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                _connectionStatus.value = ConnectionStatus.Error("Driver USB non trovato")
                return@withContext
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                _connectionStatus.value = ConnectionStatus.Error("Impossibile aprire dispositivo")
                return@withContext
            }

            serialPort = driver.ports[0]
            try {
                serialPort?.open(connection)
                serialPort?.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                parser = SerialParser(
                    telemetryChannel = _telemetryChannel,
                    paramsFlow       = _params,
                    consoleChannel   = _consoleChannel,
                    onPong           = {}
                )
                parser.reset()

                _connectionStatus.value = ConnectionStatus.Connected("ESP32 USB")
                startListening()
                delay(300.milliseconds)
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.Error("Errore apertura: ${e.message}")
                serialPort = null
            }
        }
    }

    private fun startListening() {
        readJob?.cancel()
        lateinit var newJob: Job
        newJob = scope.launch {
            val port = serialPort ?: return@launch
            // ★ Buffer più grande per 500 Hz (4KB)
            val buffer = ByteArray(4096)
            val lineBuffer = StringBuilder()
            // ★ FIX RACE CONDITION: readJob?.cancel() in disconnect() è non bloccante.
            // Se questo job (ormai "vecchio") esce dal loop DOPO che una nuova connect()
            // ha già avviato un nuovo readJob, non deve poter sovrascrivere lo stato
            // Connected/Connecting della nuova connessione. isCurrentJob() lo verifica
            // subito prima di ogni scrittura di stato di errore.
            fun isCurrentJob() = readJob === newJob
            // ★ FIX #2: il blocco `finally` viene eseguito SEMPRE, anche dopo un `catch`.
            // Senza questo flag, ogni disconnessione VERA (IOException genuina) veniva
            // prima segnalata correttamente dal catch ("Connessione USB persa" /
            // "Errore USB imprevisto") e SUBITO DOPO sovrascritta dal finally con il
            // messaggio generico "Lettore USB terminato inaspettatamente" — motivo per
            // cui quel messaggio compariva sempre, qualunque fosse la causa reale.
            var errorAlreadyReported = false
            try {
                while (isActive && _connectionStatus.value is ConnectionStatus.Connected) {
                    val len = port.read(buffer, 200)   // ★ timeout 200 ms
                    if (len < 0) {
                        throw IOException("USB disconnected or read error (len=$len)")
                    }
                    if (len == 0) continue

                    // ★ Elaborazione più efficiente
                    for (i in 0 until len) {
                        val ch = buffer[i].toInt().toChar()
                        if (ch == '\n') {
                            val line = lineBuffer.toString().trimEnd('\r')
                            lineBuffer.clear()
                            if (line.isNotEmpty()) {
                                if (!::parser.isInitialized) {
                                    Log.e(TAG, "Parser non inizializzato, ignoro riga: $line")
                                    continue
                                }
                                parser.parse(line)
                            }
                        } else if (ch != '\r') {
                            lineBuffer.append(ch)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Errore lettura USB: ${e.message}")
                if (isCurrentJob() && !manualDisconnect.get() && _connectionStatus.value is ConnectionStatus.Connected) {
                    _connectionStatus.value = ConnectionStatus.Error("Connessione USB persa")
                    errorAlreadyReported = true
                    _consoleChannel.trySend("🔴 Connessione USB persa: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore imprevisto lettura USB: ${e.message}", e)
                if (isCurrentJob() && !manualDisconnect.get() && _connectionStatus.value is ConnectionStatus.Connected) {
                    _connectionStatus.value = ConnectionStatus.Error("Errore USB imprevisto")
                    errorAlreadyReported = true
                    _consoleChannel.trySend("🔴 Errore USB imprevisto: ${e::class.simpleName} ${e.message}")
                }
            } finally {
                if (isCurrentJob() && !manualDisconnect.get() && !errorAlreadyReported) {
                    _connectionStatus.value = ConnectionStatus.Error("Lettore USB terminato inaspettatamente")
                    _consoleChannel.trySend("🟠 Reader terminato senza eccezione (job corrente=${isCurrentJob()})")
                }
                // Reset solo se questo era davvero il job corrente: altrimenti un job
                // "vecchio" potrebbe azzerare il flag proprio mentre il job nuovo
                // sta ancora facendo la sua disconnessione pulita.
                if (isCurrentJob()) manualDisconnect.set(false)
            }
        }
        readJob = newJob
    }

    fun sendCommand(cmd: String) {
        try {
            serialPort?.write("$cmd\n".toByteArray(), 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Invio fallito: $cmd", e)
        }
    }

    private fun disconnectLocked() {
        manualDisconnect.set(true)
        readJob?.cancel()
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    // Acquisisce connectMutex: una connect() può ancora essere in corso (es. utente
    // preme "Disconnetti" mentre l'auto-reconnect sta aprendo la porta), e senza il
    // lock le due potrebbero chiudere/riassegnare serialPort/readJob concorrentemente.
    suspend fun disconnect() = connectMutex.withLock {
        disconnectLocked()
    }

    fun cleanup() {
        // Teardown: nessuna nuova connect() può partire dopo scope.cancel(), quindi
        // qui basta la chiusura diretta senza passare dal mutex.
        disconnectLocked()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        _telemetryChannel.close()
        _consoleChannel.close()
        scope.cancel()
    }
}