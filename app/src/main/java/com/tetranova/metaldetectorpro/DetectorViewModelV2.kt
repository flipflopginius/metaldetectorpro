package com.tetranova.metaldetectorpro

import android.app.Application
import android.hardware.usb.UsbDevice
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import kotlin.time.Duration.Companion.milliseconds

class DetectorViewModelV2(application: Application) : AndroidViewModel(application) {
    val usb = UsbCdcManager(application.applicationContext)

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus = _connectionStatus.asStateFlow()

    val vectorProcessor = VectorProcessor()
    val vectorAudio = VectorAudioEngine()

    val groundCalibrator = GroundCalibrator()

    var analysis by mutableStateOf(AnalysisResult())
    var params by mutableStateOf(DeviceParams())
    var liveDelta by mutableFloatStateOf(0f)
    var phaseDiff by mutableFloatStateOf(0f)
    var phaseNormalized by mutableFloatStateOf(0f)
    var vectorResult by mutableStateOf<VectorResult?>(null)
    var iqCurrent by mutableStateOf(IQVector(0f, 0f))
    var iqBaseline by mutableStateOf(IQVector(0f, 0f))
    var normalizedDistance by mutableFloatStateOf(0f)

    val console = mutableStateListOf<String>()
    var cmd by mutableStateOf("    ")
    var groundAngleDeg by mutableFloatStateOf(0f)
        private set

    val historicalData = mutableStateListOf<Pair<Float, Float>>()
    val phaseHistogram = mutableStateListOf<Float>()

    var pcaQuality by mutableFloatStateOf(1.0f)
    var terrainMineralization by mutableFloatStateOf(0f)
    var terrainReactivity by mutableFloatStateOf(0f)
    var terrainStability by mutableFloatStateOf(0f)
    var autoTerrainMode by mutableStateOf(false)

    private val _isMetalDetecting = MutableStateFlow(false)
    val isMetalDetecting = _isMetalDetecting.asStateFlow()

    var sensAmpiezza by mutableFloatStateOf(5.5f)
        private set
    var discLow by mutableFloatStateOf(4f)
    var discHigh by mutableFloatStateOf(5f)
    var phaseHysteresis by mutableFloatStateOf(1.5f)
    var dutyCycle by mutableIntStateOf(40)
    val sensFase get() = discHigh

    var isGroundCalibrating by mutableStateOf(false)
        private set
    var isManualGroundBalance by mutableStateOf(false)
    var manualGroundAngleDeg by mutableFloatStateOf(0f)
    var pumpingMotionDetected by mutableStateOf(false)
        private set

    private val _rawDataMessages = MutableStateFlow<List<String>>(emptyList())
    val rawDataMessages = _rawDataMessages.asStateFlow()
    private val rawBuffer = ArrayDeque<String>(100)
    private var rawSampleCounter = 0

    private var airBaseline = IQVector.ZERO
    private var airPhaseOffsetDeg = 0f
    private var airNoise = 0.01f
    private var airEnterThreshold = 0.15f
    private var airExitThreshold = 0.09f
    private var hasAirCalibration = false

    @Volatile private var connectionAttemptInProgress = false
    private var calibrationInProgress = false
    private var dataCollectionJob: Job? = null
    private var autoConnectJob: Job? = null
    private var usbStatusJob: Job? = null
    private var calibrationJob: Job? = null
    private var lastUsbEventTime = 0L
    private var lastUiUpdateMs = 0L

    private var lastSampleForPumping: IQVector? = null

    companion object {
        const val USB_EVENT_DEBOUNCE_MS = 2000L
        const val PHASE_DISPLAY_RANGE_DEG = 30f
        const val DISC_MIN_GAP = 1f
        const val HISTORICAL_SIZE = 200
        const val HISTOGRAM_SIZE = 60
        const val RAW_SAMPLE_DECIMATION = 10
        const val UI_THROTTLE_MS = 33L
    }

    init {
        vectorProcessor.onLogMessage = { msg ->
            viewModelScope.launch(Dispatchers.Main) {
                if (!msg.contains("Soglie:") && !msg.contains("Cavo USB")) {
                    logToConsole(msg)
                }
            }
        }

        vectorProcessor.kVect = kFromSens(sensAmpiezza)
        vectorProcessor.setDiscThresholds(discLow, discHigh)
        vectorProcessor.phaseHysteresisDeg = phaseHysteresis
        updateSensAmpiezza(sensAmpiezza)
        rebuildDataCollection()
        startPersistentAutoConnect()
        vectorAudio.start()

        viewModelScope.launch {
            usb.consoleFlow.buffer(capacity = 32).collect { msg ->
                if (!msg.contains("Batch") && !msg.contains("gap")) {
                    withContext(Dispatchers.Main) { logToConsole(msg) }
                }
            }
        }
    }

    private fun kFromSens(s: Float) = when {
        s >= 8f -> 0.8f + (10f - s) * 0.15f
        s >= 5f -> 1.2f + (8f - s) * 0.13f
        s >= 3f -> 1.8f + (5f - s) * 0.20f
        else    -> 2.5f + (3f - s) * 0.25f
    }.coerceIn(0.8f, 3.5f)

    fun updateSensAmpiezza(value: Float) {
        sensAmpiezza = value
        vectorProcessor.kVect = kFromSens(value)
        logToConsole("Sens.Ampiezza: %.1f → K=%.2f".format(value, vectorProcessor.kVect))
    }

    private fun rebuildDataCollection() {
        dataCollectionJob?.cancel()
        usbStatusJob?.cancel()
        dataCollectionJob = null
        usbStatusJob = null

        usbStatusJob = viewModelScope.launch {
            usb.connectionStatus.collect { status ->
                if (!calibrationInProgress) {
                    withContext(Dispatchers.Main) { updateConnectionStatus(status) }
                }
            }
        }

        dataCollectionJob = viewModelScope.launch {
            usb.telemetryFlow
                .flowOn(Dispatchers.IO)
                .onEach { data ->
                    rawSampleCounter++
                    if (rawSampleCounter % RAW_SAMPLE_DECIMATION == 0) {
                        val rawLine = "delta=%.4f phase=%.2f°".format(data.delta, data.phase)
                        if (rawBuffer.size >= 100) rawBuffer.removeFirst()
                        rawBuffer.addLast(rawLine)
                        _rawDataMessages.value = rawBuffer.toList()
                    }

                    val rad = Math.toRadians(data.phase.toDouble())
                    val currentSample = IQVector((data.delta * cos(rad)).toFloat(), (data.delta * sin(rad)).toFloat())

                    if (isGroundCalibrating) {
                        lastSampleForPumping?.let { last ->
                            val movement = currentSample.distanceTo(last)
                            if (movement > 0.01f) {
                                pumpingMotionDetected = true
                                groundCalibrator.addSample(currentSample)
                            }
                        }
                        lastSampleForPumping = currentSample
                    } else {
                        lastSampleForPumping = null
                        pumpingMotionDetected = false
                    }

                    val vr = withContext(Dispatchers.Default) {
                        if (!calibrationInProgress && !isGroundCalibrating) {
                            vectorProcessor.processSample(data.delta, data.phase)
                        } else null
                    }

                    if (vr != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdateMs >= UI_THROTTLE_MS) {
                            lastUiUpdateMs = now
                            withContext(Dispatchers.Main) {
                                updateUiState(vr)
                            }
                        }
                    }
                }
                .launchIn(this)
        }

        viewModelScope.launch {
            usb.params.collect { newParams ->
                withContext(Dispatchers.Main) {
                    params = newParams.copy(transportMode = params.transportMode)
                }
            }
        }
    }

    private fun updateUiState(vr: VectorResult) {
        vectorResult = vr
        // ✅ CORRETTO: Usiamo groundPhaseOffsetDeg al posto di groundAngleRad
        groundAngleDeg = vectorProcessor.groundAxisDeg
        iqCurrent = vr.vector
        iqBaseline = vr.baseline
        liveDelta = vr.vectorDistance
        _isMetalDetecting.value = vr.isDetected
        normalizedDistance = (vr.vectorDistance / vectorProcessor.enterDetectionThreshold.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        phaseDiff = vr.relativeAngleDeg
        phaseNormalized = (vr.relativeAngleDeg / PHASE_DISPLAY_RANGE_DEG).coerceIn(-1f, 1f)

        analysis = AnalysisResult(
            vdi = vr.vdi,
            type = vr.metalType,
            confidence = vr.confidence,
            amplitude = vr.vectorDistance * 1000f,
            isLocked = false,
            depth = 0f
        )

        val ampNorm = vr.vectorDistance / vectorProcessor.enterDetectionThreshold.coerceAtLeast(0.001f)
        historicalData.add(ampNorm to vr.relativeAngleDeg)
        if (historicalData.size > HISTORICAL_SIZE) historicalData.removeAt(0)

        if (vr.isAngleValid) {
            phaseHistogram.add(vr.relativeAngleDeg)
            if (phaseHistogram.size > HISTOGRAM_SIZE) phaseHistogram.removeAt(0)
        }

        if (!isGroundCalibrating) {
            syncTerrainMetrics()
        }

        vectorAudio.update(vr)
    }

    private fun syncTerrainMetrics() {
        val vp = vectorProcessor
        pcaQuality = vp.lastPcaQuality.coerceIn(0f, 10f)
        terrainMineralization = (vp.tangentialRMS / vp.noiseMagnitude.coerceAtLeast(0.001f)).coerceIn(0f, 10f)
        terrainReactivity = (vp.groundCenter.magnitude * 100f).coerceIn(0f, 10f)
        terrainStability = if (vp.tangentialRMS > 0.001f) (vp.tangentialSigma / vp.tangentialRMS).coerceIn(0f, 10f) else 0f
    }

    fun performCalibration() {
        if (calibrationInProgress) return

        viewModelScope.launch {
            calibrationInProgress = true
            logToConsole(">>> Calibrazione I/Q in corso...")
            vectorProcessor.reset()
            dataCollectionJob?.cancel()
            dataCollectionJob = null

            val samples = mutableListOf<IQVector>()
            val rawPhaseSamples = mutableListOf<Float>()
            val startMs = System.currentTimeMillis()
            val discardMs = startMs + 300L
            val endMs = startMs + 3000L

            withTimeoutOrNull(5000.milliseconds) {
                usb.telemetryFlow.collect { data ->
                    if (usb.connectionStatus.value !is ConnectionStatus.Connected) return@collect
                    val now = System.currentTimeMillis()
                    if (now !in discardMs until endMs) return@collect
                    if (data.delta.isNaN() || data.phase.isNaN()) return@collect

                    val rad = Math.toRadians(data.phase.toDouble())
                    samples.add(IQVector((data.delta * cos(rad)).toFloat(), (data.delta * sin(rad)).toFloat()))
                    rawPhaseSamples.add(data.phase)
                }
            }

            try {
                if (samples.size < 30) {
                    logToConsole(">>> ERRORE: campioni insufficienti")
                    return@launch
                }

                val avgI = samples.map { it.i }.average().toFloat()
                val avgQ = samples.map { it.q }.average().toFloat()
                val baseline = IQVector(avgI, avgQ)

                val dists = samples.map { it.distanceTo(baseline) }.sorted()
                val valid = dists.drop(dists.size / 10).take(dists.size * 9 / 10)
                val rms = sqrt(valid.map { d -> (d - valid.average()).pow(2) }.average()).toFloat().coerceAtLeast(0.001f)

                var sinSum = 0.0; var cosSum = 0.0
                for (p in rawPhaseSamples) {
                    val r = Math.toRadians(p.toDouble())
                    sinSum += sin(r); cosSum += cos(r)
                }
                val baseAngle = Math.toDegrees(atan2(sinSum, cosSum)).toFloat()
                val phaseOffset = (baseAngle % 360f).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it }

                vectorProcessor.setBaselineIQ(baseline)
                vectorProcessor.setNoiseEstimate(rms)
                vectorProcessor.groundPhaseOffsetDeg = phaseOffset
                vectorProcessor.enterDetectionThreshold = (rms * 3.5f).coerceIn(0.15f, 2.5f)
                vectorProcessor.exitDetectionThreshold = vectorProcessor.enterDetectionThreshold * 0.6f
                airBaseline = baseline
                airPhaseOffsetDeg = phaseOffset
                airNoise = rms
                airEnterThreshold = vectorProcessor.enterDetectionThreshold
                airExitThreshold = vectorProcessor.exitDetectionThreshold
                hasAirCalibration = true

                logToConsole(">>> OK! I=%.4f Q=%.4f SogliaEntra=%.4f".format(baseline.i, baseline.q, vectorProcessor.enterDetectionThreshold))

                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Calibrazione completata", Toast.LENGTH_SHORT).show()
                }
            } finally {
                rebuildDataCollection()
                calibrationInProgress = false
            }
        }
    }

    fun toggleManualGroundBalance() {
        isManualGroundBalance = !isManualGroundBalance
        vectorProcessor.isManualGroundBalance = isManualGroundBalance

        if (isManualGroundBalance) {
            manualGroundAngleDeg = vectorProcessor.groundAxisDeg
            setManualGroundAngle(manualGroundAngleDeg)
            logToConsole("🖐️ Ground Balance MANUALE")
        } else {
            logToConsole("🤖 Ground Balance AUTOMATICO")
        }
    }

    fun setManualGroundAngle(deg: Float) {
        isManualGroundBalance = true
        vectorProcessor.isManualGroundBalance = true

        manualGroundAngleDeg = deg.coerceIn(-45f, 45f)
        groundAngleDeg = manualGroundAngleDeg

        vectorProcessor.setGroundAxis(manualGroundAngleDeg)

        logToConsole("Manuale: Asse terreno=%.1f° | Baseline attiva: %s".format(
            manualGroundAngleDeg, vectorProcessor.baselineVector.toString()
        ))
    }

    fun startGroundCalibration() {
        if (isGroundCalibrating) return
        if (usb.connectionStatus.value !is ConnectionStatus.Connected) return

        if (isManualGroundBalance) {
            isManualGroundBalance = false
            vectorProcessor.isManualGroundBalance = false
        }

        isGroundCalibrating = true
        vectorProcessor.isCalibratingGround = true
        groundCalibrator.startAccumulation()

        pumpingMotionDetected = false
        lastSampleForPumping = null

        pcaQuality = 0.0f
        terrainMineralization = 0.0f
        terrainStability = 0.0f

        logToConsole(" Calibrazione (Pumping) avviata. Pompa la bobina...")
        Toast.makeText(getApplication(), "Pompa la bobina sul terreno!", Toast.LENGTH_SHORT).show()

        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            delay(10000.milliseconds)
            if (isGroundCalibrating) {
                logToConsole("⏰ Timeout: calibrazione arrestata automaticamente.")
                finishGroundCalibration()
            }
        }
    }

    fun finishGroundCalibration() {
        if (!isGroundCalibrating) return

        calibrationJob?.cancel()
        calibrationJob = null

        val report = groundCalibrator.computeCalibration()

        this.pcaQuality = report.pcaQuality
        this.terrainStability = (10.0f - report.tangentialRMS * 100f).coerceIn(0f, 10f)

        if (report.success) {
            this.terrainMineralization = (report.groundCenter.magnitude * 100f).coerceIn(0f, 10f)
            this.terrainReactivity = if (report.groundAngleDeg > 0) 1.5f else 0.5f

            vectorProcessor.applyGroundCalibration(report.groundCenter, report.centerPhaseDeg, report.groundAngleDeg)

            vectorProcessor.tangentialRMS = report.tangentialRMS
            vectorProcessor.tangentialSigma = report.tangentialSigma
            vectorProcessor.groundCenter = report.groundCenter
            vectorProcessor.lastPcaQuality = report.pcaQuality

            val newNoise = report.tangentialRMS * 1.2f
            vectorProcessor.setNoiseEstimate(newNoise.coerceAtLeast(0.001f))
            vectorProcessor.enterDetectionThreshold = (newNoise * 3.5f).coerceIn(0.15f, 2.5f)
            vectorProcessor.exitDetectionThreshold = vectorProcessor.enterDetectionThreshold * 0.6f

            manualGroundAngleDeg = report.groundAngleDeg
            groundAngleDeg = report.groundAngleDeg

            logToConsole("✅ %s Angolo: %.1f° | Stabilità: %.2f".format(report.message, report.groundAngleDeg, this.terrainStability))
            logToConsole("✅ Nuove soglie: Entra=%.3f Esci=%.3f".format(
                vectorProcessor.enterDetectionThreshold,
                vectorProcessor.exitDetectionThreshold
            ))
        } else {
            logToConsole("❌ Calibrazione Rifiutata: ${report.message}")
        }

        // Il gate si chiude solo ORA: baseline/fase/soglie sono già coerenti.
        isGroundCalibrating = false
        vectorProcessor.isCalibratingGround = false

        Toast.makeText(getApplication(), report.message, Toast.LENGTH_LONG).show()
    }

    fun resetGroundBalance() {
        if (isGroundCalibrating) {
            calibrationJob?.cancel()
            calibrationJob = null
            isGroundCalibrating = false
            vectorProcessor.isCalibratingGround = false
        }

        vectorProcessor.isManualGroundBalance = false
        vectorProcessor.clearGroundAxis()

        if (hasAirCalibration) {
            vectorProcessor.setBaselineIQ(airBaseline)
            vectorProcessor.groundPhaseOffsetDeg = airPhaseOffsetDeg
            vectorProcessor.setNoiseEstimate(airNoise)
            vectorProcessor.enterDetectionThreshold = airEnterThreshold
            vectorProcessor.exitDetectionThreshold = airExitThreshold
            logToConsole("↺ Ground Balance resettato alla calibrazione in aria.")
        } else {
            vectorProcessor.groundPhaseOffsetDeg = 0f
            vectorProcessor.setBaselineIQ(IQVector.ZERO)
            logToConsole("↺ Ground Balance resettato (nessuna calibrazione in aria presente).")
        }

        vectorProcessor.groundCenter = IQVector.ZERO
        vectorProcessor.tangentialRMS = 0f
        vectorProcessor.tangentialSigma = 0f
        vectorProcessor.lastPcaQuality = 1.0f

        isManualGroundBalance = false
        manualGroundAngleDeg = 0f
        groundAngleDeg = vectorProcessor.groundAxisDeg

        this.pcaQuality = 1.0f
        this.terrainMineralization = 0.0f
        this.terrainReactivity = 0.0f
        this.terrainStability = 0.0f

        Toast.makeText(getApplication(), "Ground Balance resettato", Toast.LENGTH_SHORT).show()
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        val now = System.currentTimeMillis()
        if (now - lastUsbEventTime < USB_EVENT_DEBOUNCE_MS) return
        lastUsbEventTime = now
        connectToUSB(device)
    }

    fun connectToUSB(device: UsbDevice) {
        if (connectionAttemptInProgress || usb.connectionStatus.value is ConnectionStatus.Connected) return

        viewModelScope.launch(Dispatchers.IO) {
            connectionAttemptInProgress = true
            try {
                dataCollectionJob?.cancel()
                dataCollectionJob = null

                _connectionStatus.value = ConnectionStatus.Connecting
                usb.connect(device)

                val connected = withTimeoutOrNull(6000.milliseconds) {
                    usb.connectionStatus.first { it is ConnectionStatus.Connected }
                }

                if (connected == null) {
                    logToConsole("️ USB timeout")
                    usb.disconnect()
                    _connectionStatus.value = ConnectionStatus.Disconnected
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    _connectionStatus.value = ConnectionStatus.Connected("USB ")
                    params = params.copy(transportMode = "USB ")
                    vectorAudio.start()
                    Toast.makeText(getApplication(), "Connesso (USB)", Toast.LENGTH_SHORT).show()
                    rebuildDataCollection()
                }

                delay(1500.milliseconds)
                usb.sendCommand("STATUS")
            } finally {
                connectionAttemptInProgress = false
            }
        }
    }

    private fun updateConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status

        when (status) {
            is ConnectionStatus.Connected -> {
                params = params.copy(transportMode = "USB ")
                vectorAudio.start()
                viewModelScope.launch { delay(200.milliseconds); usb.sendCommand("STATUS") }
            }
            is ConnectionStatus.Disconnected, is ConnectionStatus.Error -> {
                vectorAudio.stop()
                if (status is ConnectionStatus.Error) {
                    logToConsole(" ${status.message}")
                }
            }
            else -> {}
        }
    }

    private fun startPersistentAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch {
            while (isActive) {
                delay(5000.milliseconds)
                if (connectionAttemptInProgress) continue

                if (usb.connectionStatus.value !is ConnectionStatus.Connected &&
                    usb.connectionStatus.value !is ConnectionStatus.Connecting) {
                    val device = usb.findDevice()
                    if (device != null) connectToUSB(device)
                }
            }
        }
    }

    fun updateDiscThresholds(low: Float, high: Float) {
        val l = low.coerceIn(1f, 45f - DISC_MIN_GAP)
        val h = high.coerceIn(l + DISC_MIN_GAP, 45f)

        vectorProcessor.setDiscThresholds(l, h)
        discLow = vectorProcessor.nonFerroLowDeg
        discHigh = vectorProcessor.ferroMinDeg

        logToConsole("Disc: basso=%.1f° alto=%.1f°".format(discLow, discHigh))
    }

    fun updateSensFase(value: Float) = updateDiscThresholds(discLow, value)

    fun updateFrequency(freq: Float) {
        params = params.copy(frequency = freq)
        usb.parser.notifyFrequencyChange(freq)
        logToConsole("Frequenza: %.0fHz".format(freq))
    }

    fun updateDutyCycle(duty: Int) {
        dutyCycle = duty
        logToConsole("Duty: $duty%")
    }

    fun updatePhaseHysteresis(value: Float) {
        phaseHysteresis = value.coerceIn(0f, 5f)
        vectorProcessor.phaseHysteresisDeg = phaseHysteresis
        logToConsole("Isteresi: %.1f°".format(phaseHysteresis))
    }

    fun invertDiscPolarity() {
        vectorProcessor.discAnglePolarity = -vectorProcessor.discAnglePolarity
        logToConsole("Polarità invertita")
    }

    fun send() {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return

        val lower = trimmed.lowercase()
        cmd = "    "

        when (lower) {
            "status" -> { showStatus(); return }
            "calibra" -> { performCalibration(); return }
            "invertdisc" -> { invertDiscPolarity(); return }
            "reboot" -> {
                viewModelScope.launch { usb.sendCommand("REBOOT") }
                logToConsole("Riavvio ESP...")
                return
            }
        }

        if (lower.startsWith("set ")) { handleLocalCommand(trimmed); return }

        if (connectionStatus.value is ConnectionStatus.Connected) {
            viewModelScope.launch { usb.sendCommand(trimmed) }
            logToConsole("➡️ $trimmed")
        } else {
            logToConsole("❌ Non connesso")
        }
    }

    private fun handleLocalCommand(cmd: String) {
        val p = cmd.split(" ")
        if (p.size < 3) { logToConsole("Comando incompleto"); return }

        when (p[1].lowercase()) {
            "sens" -> p[2].toFloatOrNull()?.let { updateSensAmpiezza(it) } ?: logToConsole("Valore non valido")
            "disc" -> p[2].toFloatOrNull()?.let { updateSensFase(it) } ?: logToConsole("Valore non valido")
            "hyst" -> p[2].toFloatOrNull()?.let { updatePhaseHysteresis(it) } ?: logToConsole("Valore non valido")
            else -> {
                logToConsole("Sconosciuto: ${p[1]}")
            }
        }
    }

    private fun showStatus() {
        val vr = vectorResult
        logToConsole("=== STATO ===")
        logToConsole("Trasporto: USB | ${_connectionStatus.value}")
        logToConsole("Baseline: I=%.4f Q=%.4f".format(vectorProcessor.baselineVector.i, vectorProcessor.baselineVector.q))
        logToConsole("Vettore: I=%.4f Q=%.4f".format(iqCurrent.i, iqCurrent.q))
        logToConsole("Energia: %.4f SogliaEntra: %.4f Rumore: %.4f".format(
            vr?.vectorDistance ?: 0f, vectorProcessor.enterDetectionThreshold, vectorProcessor.noiseMagnitude))
        logToConsole("Angolo: %.1f° Tipo: %s Det: %s".format(vr?.relativeAngleDeg ?: 0f, vr?.metalType ?: "?", vr?.isDetected))
    }

    private fun logToConsole(msg: String) {
        console.add(msg)
        if (console.size > 120) console.removeAt(0)
    }

    override fun onCleared() {
        usb.cleanup()
        vectorAudio.stop()
        super.onCleared()
    }
}