package com.tetranova.metaldetectorpro

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

class UsbCdcManager(private val context: Context) {
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _telemetryChannel = Channel<TelemetryData>(
        capacity = 512,  // ★ aumentato per 500 Hz
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val telemetryFlow: Flow<TelemetryData> = _telemetryChannel.receiveAsFlow()

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
    private var readJob: Job? = null
    lateinit var parser: SerialParser

    private val manualDisconnect = AtomicBoolean(false)
    private val connectMutex = Mutex()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tetranova.metaldetectorpro.USB_PERMISSION"
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
        context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
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
            disconnect()
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
                    telemetryFlow = _telemetryChannel.asMutableSharedFlowAdapter(),
                    paramsFlow    = _params,
                    consoleFlow   = _consoleChannel.asMutableSharedFlowAdapter(),
                    onPong        = {}
                )
                parser.reset()

                _connectionStatus.value = ConnectionStatus.Connected("ESP32 USB")
                startListening()
                delay(300)
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.Error("Errore apertura: ${e.message}")
                serialPort = null
            }
        }
    }

    private fun startListening() {
        readJob?.cancel()
        readJob = scope.launch {
            val port = serialPort ?: return@launch
            // ★ Buffer più grande per 500 Hz (4KB)
            val buffer = ByteArray(4096)
            val lineBuffer = StringBuilder()
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
                if (!manualDisconnect.get() && _connectionStatus.value is ConnectionStatus.Connected) {
                    _connectionStatus.value = ConnectionStatus.Error("Connessione USB persa")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore imprevisto lettura USB: ${e.message}", e)
                if (!manualDisconnect.get() && _connectionStatus.value is ConnectionStatus.Connected) {
                    _connectionStatus.value = ConnectionStatus.Error("Errore USB imprevisto")
                }
            } finally {
                if (!manualDisconnect.get()) {
                    _connectionStatus.value = ConnectionStatus.Error("Lettore USB terminato inaspettatamente")
                }
                manualDisconnect.set(false)
            }
        }
    }

    suspend fun sendCommand(cmd: String) {
        try {
            serialPort?.write("$cmd\n".toByteArray(), 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Invio fallito: $cmd", e)
        }
    }

    fun disconnect() {
        manualDisconnect.set(true)
        readJob?.cancel()
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    fun cleanup() {
        disconnect()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        _telemetryChannel.close()
        _consoleChannel.close()
        scope.cancel()
    }
}

// ── Adapter helper ────────────────────────────────────────────────────────────
private fun <T> Channel<T>.asMutableSharedFlowAdapter(): MutableSharedFlow<T> {
    val channel = this
    return object : MutableSharedFlow<T> {
        override val replayCache: List<T> get() = emptyList()
        override val subscriptionCount: StateFlow<Int> get() = MutableStateFlow(0)
        override suspend fun collect(collector: FlowCollector<T>): Nothing =
            channel.receiveAsFlow().collect(collector).let { throw CancellationException() }
        override fun tryEmit(value: T): Boolean = channel.trySend(value).isSuccess
        override suspend fun emit(value: T) { channel.send(value) }
        override fun resetReplayCache() {}
    }
}