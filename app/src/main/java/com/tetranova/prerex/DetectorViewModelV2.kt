package com.tetranova.prerex

import android.app.Application
import android.content.ContentValues
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import kotlin.time.Duration.Companion.milliseconds

// =============================================================
// ENUM STATI AUTO TRACKING
// =============================================================

enum class AutoTrackingState {
    IDLE,           // Non attivo
    ACTIVATING,     // In attivazione (transizione)
    ACTIVE,         // Attivo e funzionante
    DEACTIVATING    // In disattivazione (transizione)
}

class DetectorViewModelV2(application: Application) : AndroidViewModel(application) {
    val usb = UsbCdcManager(application.applicationContext)

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus = _connectionStatus.asStateFlow()

    val vectorProcessor = VectorProcessor()
    val vectorAudio = VectorAudioEngine()

    val groundCalibrator = GroundCalibrator()
    val depthCalibrator = DepthCalibrator()

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

    private val _isMetalDetecting = MutableStateFlow(false)
    val isMetalDetecting = _isMetalDetecting.asStateFlow()

    var sensAmpiezza by mutableFloatStateOf(10f)
        private set
    var discLow by mutableFloatStateOf(4f)
    var discHigh by mutableFloatStateOf(5f)
    var phaseHysteresis by mutableFloatStateOf(1.5f)
    var dutyCycle by mutableIntStateOf(40)

    var isGroundCalibrating by mutableStateOf(false)
        private set

    // ===== DIAGNOSTICA LIVE PUMPING =====
    var pumpingDiagnostics by mutableStateOf<PumpingLiveDiagnostics?>(null)
    var isManualGroundBalance by mutableStateOf(false)
    var manualGroundAngleDeg by mutableFloatStateOf(0f)

    // FIX (Manuale scriveva nel parametro sbagliato): sui detector professionali esiste
    // UN SOLO "numero di GB" — sia Auto/Pumping che Manuale scrivono la stessa variabile,
    // il Manuale è un "tweak" di qualche tacca sopra il valore appena calibrato (vedi
    // ricerca: tecnica documentata su Garrett AT Pro, "auto ground balance poi tweak
    // manuale"). Nel nostro codice quel riferimento è vectorProcessor.groundPhaseOffsetDeg
    // (guida l'angolo a schermo e la classificazione FERRO/NON_FERRO), NON groundAxisDeg
    // (asse PCA interno per la sola soglia di detection, mai esposto sui prodotti
    // commerciali). calibratedPhaseOffsetDeg ricorda il valore puro impostato dall'ultimo
    // Air Balance/Pumping; manualGroundAngleDeg è la correzione (delta, range -45..45,
    // stesso dominio dello slider esistente) applicata SOPRA quel riferimento — non un
    // sostituto assoluto, perché groundPhaseOffsetDeg vive in un dominio (-180..180)
    // diverso da quello dello slider.
    private var calibratedPhaseOffsetDeg = 0f
    var pumpingMotionDetected by mutableStateOf(false)

    // ===== VISUALIZZAZIONE LIVE DEL PUMPING =====
    // Riusa esattamente lo stesso IQPlotWidget già usato nella schermata principale
    // (stessa vista isometrica Radiale/Tangenziale/Tempo, stessi colori, stesso
    // significato) invece di introdurre un linguaggio grafico nuovo. "accettato"
    // riusa isFerroso=false (verde/ciano) e "scartato" riusa isFerroso=true (rosso),
    // stessa identica funzione vectorDotColor() già esistente, solo risemantizzata.
    var pumpingCurrentSample by mutableStateOf(IQVector.ZERO)
    var pumpingBaseline by mutableStateOf(IQVector.ZERO)
    var pumpingDotAccepted by mutableStateOf(true)
    var pumpingAcceptedCount by mutableStateOf(0)
    var pumpingRejectedCount by mutableStateOf(0)
        private set

    private val _rawDataMessages = MutableStateFlow<List<String>>(emptyList())
    val rawDataMessages = _rawDataMessages.asStateFlow()
    private val rawBuffer = ArrayDeque<String>(100)
    private var rawSampleCounter = 0

    // =============================================================
    // REGISTRAZIONE SESSIONE (CSV per analisi offline)
    // =============================================================
    // Cattura delta/fase grezzi + stato completo di detection e GroundTracker a ~100Hz,
    // indipendentemente dal throttle UI (33ms). Il downsampling usa data.timestampMs (il
    // timestamp per-campione equispaziato calcolato in SerialParser), non un contatore, per
    // restare accurato anche se il flusso USB rallenta o ha dei gap.
    private var isRecordingSession = false
    private var recordingStartMs = 0L
    private var recordingDurationMs = 30_000L
    private var lastRecordedSampleMs = 0L
    private val recordSampleIntervalMs = 10L // 100 Hz
    private val recordingRows = mutableListOf<String>()
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()


    private var airBaseline = IQVector.ZERO
    private var airPhaseOffsetDeg = 0f
    private var airNoise = 0.01f
    private var airEnterThreshold = 0.15f
    private var airExitThreshold = 0.09f

    // STATEFLOW PER AIR CALIBRATION (OSSERVABILE IN UI)
    private val _hasAirCalibration = MutableStateFlow(false)
    val hasAirCalibration: StateFlow<Boolean> = _hasAirCalibration.asStateFlow()

    // STATO PUMPING COMPLETATO
    private val _isPumpingDone = MutableStateFlow(false)
    val isPumpingDone: StateFlow<Boolean> = _isPumpingDone.asStateFlow()

    // MOTION DETECTOR (GIROSCOPIO + ACCELEROMETRO)
    private lateinit var motionDetector: GroundMotionDetector

    @Volatile private var connectionAttemptInProgress = false
    private var calibrationInProgress = false
    private var dataCollectionJob: Job? = null
    private var autoConnectJob: Job? = null
    private var usbStatusJob: Job? = null
    private var calibrationJob: Job? = null
    private var lastUsbEventTime = 0L
    private var lastUiUpdateMs = 0L

    // ===== LOGGING PERIODICO STATUS =====
    private var lastStatusLogMs = 0L
    private val STATUS_LOG_INTERVAL_MS = 1000L   // 1 secondo

    private val depthAnalyzer = DepthAnalyzer()

    // STATO PER LE IMPRONTE (osservabile)
    private val _fingerprintCount = MutableStateFlow(0)
    val fingerprintCount: StateFlow<Int> = _fingerprintCount.asStateFlow()

    // STATO CALIBRAZIONE PROFONDITÀ (osservabile): depthCalibrator.isCalibrating()/
    // isCalibrated() sono plain var, non osservabili da Compose — senza questi StateFlow
    // la UI (CommandsScreen) si aggiornava solo per coincidenza, quando un'altra
    // ricomposizione non correlata capitava a ricapitare nel momento giusto.
    private val _isDepthCalibrating = MutableStateFlow(false)
    val isDepthCalibrating: StateFlow<Boolean> = _isDepthCalibrating.asStateFlow()
    private val _isDepthCalibrated = MutableStateFlow(false)
    val isDepthCalibrated: StateFlow<Boolean> = _isDepthCalibrated.asStateFlow()

    // STATO PER LA PREDIZIONE (osservabile)
    private val _currentPrediction = MutableStateFlow<DepthCalibrator.TargetPrediction?>(null)
    val currentPrediction: StateFlow<DepthCalibrator.TargetPrediction?> = _currentPrediction.asStateFlow()
    private var lastPredictionEventMs = 0L

    // ===== GROUND TRACKER PER AUTOTRACKING (VERSIONE CON MACCHINA A STATI) =====
    private val groundTracker = GroundTracker()

    private val _isAutoGroundTracking = MutableStateFlow(false)
    val isAutoGroundTracking: StateFlow<Boolean> = _isAutoGroundTracking.asStateFlow()

    private val _trackingQuality = MutableStateFlow(1f)
    val trackingQuality: StateFlow<Float> = _trackingQuality.asStateFlow()

    private val _trackingStatus = MutableStateFlow("Inattivo")
    val trackingStatus: StateFlow<String> = _trackingStatus.asStateFlow()

    private val _trackerUpdates = MutableStateFlow(0L)
    val trackerUpdates: StateFlow<Long> = _trackerUpdates.asStateFlow()

    // STATI DI TRANSIZIONE AUTO TRACKING
    private val _autoTrackingState = MutableStateFlow(AutoTrackingState.IDLE)
    val autoTrackingState: StateFlow<AutoTrackingState> = _autoTrackingState.asStateFlow()

    private val _displayAutoValue = MutableStateFlow(0f)
    val displayAutoValue: StateFlow<Float> = _displayAutoValue.asStateFlow()

    // G.ID (lettura continua e indipendente del terreno) / G.SET (riferimento
    // calibrato al Pumping, statico) — vedi commenti in VectorProcessor.kt.
    // Pubblicati SEMPRE che ci sia un asse calibrato, non solo con l'auto-tracking
    // attivo: sono lo strumento diagnostico che permette di decidere QUANDO
    // attivare il tracking, non solo di osservarlo mentre gira.
    private val _groundIdDeg = MutableStateFlow(0f)
    val groundIdDeg: StateFlow<Float> = _groundIdDeg.asStateFlow()

    private val _groundSetDeg = MutableStateFlow(0f)
    val groundSetDeg: StateFlow<Float> = _groundSetDeg.asStateFlow()

    companion object {
        const val USB_EVENT_DEBOUNCE_MS = 2000L
        const val PHASE_DISPLAY_RANGE_DEG = 30f
        const val DISC_MIN_GAP = 1f
        const val HISTORICAL_SIZE = 200
        const val HISTOGRAM_SIZE = 60
        const val RAW_SAMPLE_DECIMATION = 10
        const val UI_THROTTLE_MS = 33L
        const val PREDICTION_HOLD_MS = 1500L

        // FIX "unica fonte di verità": questi tre valori erano scritti come letterali
        // duplicati sia in performCalibration() (Air Balance) sia in
        // finishGroundCalibration() (Pumping). Una modifica fatta in un solo punto
        // avrebbe fatto silenziosamente divergere i due percorsi di calibrazione, senza
        // errori né avvisi - esattamente la classe di bug (parametro sovrascritto/duplicato
        // senza fonte unica) identificata come causa di problemi passati. Centralizzati qui;
        // entrambi i percorsi li referenziano, non li ridefiniscono.
        const val NOISE_TO_ENTER_THRESHOLD_MULTIPLIER = 3.5f
        const val EXIT_THRESHOLD_RATIO = 0.6f
        // FIX SCALA ADC (provvisorio, vedi commenti inline): range allargato per non
        // distorcere rms*3.5 in attesa di dati reali di log per la taratura definitiva.
        const val ENTER_THRESHOLD_CLAMP_MIN = 1f
        const val ENTER_THRESHOLD_CLAMP_MAX = 400f
    }

    init {
        // FIX: kVect ora coerente con sensAmpiezza (default massimo) invece di restare al
        // valore grezzo precedente (1.2), che mostrato come slider 1-10 appariva vicino al
        // minimo ("Sensibilità: 1") pur non essendolo realmente nella scala K sottostante.
        vectorProcessor.kVect = kFromSens(sensAmpiezza)

        // Carica impronte salvate all'avvio
        val loaded = depthCalibrator.loadFromFile(getApplication())
        if (loaded > 0) {
            logToConsole("📂 Caricate $loaded impronte salvate")
            _fingerprintCount.value = loaded
        }

        vectorProcessor.onAutoTrackingStuckReset = {
            // FIX (deadlock falsi positivi): reanchorDriftOrigin() da sola non
            // corregge un centro corrotto (vedi commenti in GroundTracker.kt).
            // requestBaselineRecovery() gestisce anche il ripristino dell'ultimo
            // centro buono in caso di recuperi ravvicinati, e segnala quando il
            // tracking va disattivato perché la baseline non è più affidabile.
            val stillTracking = groundTracker.requestBaselineRecovery()
            if (!stillTracking) {
                viewModelScope.launch(Dispatchers.Main) {
                    forceDisableAutoTrackingForRecalibration()
                }
            }
        }
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

        // =============================================================
        // INIZIALIZZA IL MOTION DETECTOR (CON ISTERESI)
        // =============================================================
        motionDetector = GroundMotionDetector(getApplication())
        motionDetector.onMotionStateChanged = { isMoving ->
            // Inoltra lo stato di movimento al GroundTracker
            groundTracker.setMotionState(isMoving)
        }
        motionDetector.startListening()
        logToConsole("📡 Motion Detector avviato: ${motionDetector.getSensorStatus()}")

        // FIX "unica fonte di verità": stableMovementThreshold, stablePhaseThresholdDeg e
        // stableMagnitudeThresholdPercent erano riassegnati qui con valori che ora
        // coincidono ESATTAMENTE con i default della classe GroundTracker (dopo il fix di
        // riscalatura ADC). Mantenere l'override crea una seconda fonte di verità per lo
        // stesso parametro: se in futuro si cambia il default in GroundTracker.kt senza
        // ricordarsi di questa riga, il valore realmente in uso resta silenziosamente
        // quello vecchio, qui. Rimosso: il default della classe è ora l'unica fonte.
        viewModelScope.launch {
            usb.consoleFlow.buffer(capacity = 32).collect { msg ->
                if (!msg.contains("Batch") && !msg.contains("gap")) {
                    withContext(Dispatchers.Main) { logToConsole(msg) }
                }
            }
        }
    }

    private fun kFromSens(s: Float) = when {
        s >= 8f -> 2.5f + (s - 8f) * 0.5f
        s >= 5f -> 1.8f + (s - 5f) * 0.233f
        s >= 3f -> 1.2f + (s - 3f) * 0.3f
        else    -> 0.8f + (s - 1f) * 0.2f
    }.coerceIn(0.8f, 3.5f)

    fun updateSensAmpiezza(nuovoValore: Float) {
        sensAmpiezza = nuovoValore.coerceIn(1f, 10f)
        vectorProcessor.kVect = kFromSens(sensAmpiezza)
        logToConsole("Sensibilità impostata: %.1f (K=%.2f)".format(sensAmpiezza, vectorProcessor.kVect))
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
                        val accepted = motionDetector.isDeviceMoving()
                        pumpingMotionDetected = accepted
                        pumpingDotAccepted = accepted
                        pumpingCurrentSample = currentSample
                        if (accepted) {
                            pumpingAcceptedCount++
                            pumpingDiagnostics = groundCalibrator.addSample(currentSample)
                            pumpingBaseline = pumpingDiagnostics!!.center
                        } else {
                            pumpingRejectedCount++
                            pumpingBaseline = pumpingDiagnostics?.center ?: IQVector.ZERO
                        }
                    } else {
                        pumpingMotionDetected = false
                    }

                    val vr = withContext(Dispatchers.Default) {
                        if (!calibrationInProgress) {
                            vectorProcessor.processSample(data.delta, data.phase)
                        } else null
                    }

                    if (vr != null) {
                        val depthResult = withContext(Dispatchers.Default) {
                            depthAnalyzer.processSample(
                                vectorDistance = vr.vectorDistance,
                                confidence = vr.confidence,
                                vdi = vr.vdi,
                                threshold = vectorProcessor.enterDetectionThreshold,
                                historicalData = historicalData,
                                phaseHistogram = phaseHistogram
                            )
                        }

// FIX: fallbackMotionDetection() era definito in GroundMotionDetector ma non veniva mai
                        // chiamato da nessuna parte. Su un dispositivo privo sia di giroscopio che di
                        // accelerometro, isDeviceMoving() sarebbe rimasto false per sempre, bloccando
                        // permanentemente il gate di movimento del GroundTracker. Qui lo colleghiamo
                        // usando la distanza vettoriale del segnale (già calcolata da vectorProcessor)
                        // come proxy di "quanto sta cambiando il segnale campione-per-campione" - il
                        // metodo stesso ignora la chiamata se un sensore hardware è disponibile.
                        if (!motionDetector.hasHardwareSensor()) {
                            motionDetector.fallbackMotionDetection(vr.vectorDistance)
                        }

// ===== GROUND TRACKER =====
                        if (_isAutoGroundTracking.value) {
                            val isNoise = vr.vectorDistance < vectorProcessor.noiseMagnitude * 2f

                            // FIX BUG SUBDOLO: la vecchia "isTargetLike" ricalcolava la detection da zero
                            // confrontando vr.vectorDistance con una soglia ASSOLUTA fissa (0.02f), scollegata
                            // da enterDetectionThreshold/kVect (cioè dalla sensibilità impostata dall'utente)
                            // e dall'isteresi/confirmSamples che il pipeline reale usa per vr.isDetected.
                            // Conseguenza: con sensibilità alta, un vero target debole (distance < 0.02 ma
                            // vr.isDetected == true) veniva passato al tracker con isDetected = false,
                            // cioè trattato come "terreno" -> il suo campione finiva nella media di
                            // GROUND_LOCK e, tramite setExternalBaseline/setExternalAxis (chiamati ad ogni
                            // campione, non throttled), contaminava la baseline reale. Baseline spostata =>
                            // il terreno normale comincia a sembrare un delta => falsi positivi a catena,
                            // esattamente il "dopo un paio di rilevazioni tutto peggiora" osservato.
                            // Con sensibilità bassa succedeva l'opposto: rumore di terreno superava 0.02f
                            // e veniva forzato come isDetected = true, innescando cooldown/freeze spuri che
                            // impedivano al tracker di agganciarsi mai in GROUND_LOCK.
                            //
                            // FIX: usare direttamente vr.isDetected, che è già lo stato di detection reale
                            // del pipeline (soglie di ingresso/uscita, isteresi di fase, confirmSamples) e
                            // che scala correttamente con la sensibilità impostata dall'utente.
                            // FIX: setMotionState() arriva solo sui CAMBI di stato (callback
                            // onMotionStateChanged), ma isTrueStatic() (LTA assoluto sotto
                            // soglia, vedi GroundMotionDetector) deve essere riletto ad ogni
                            // campione: l'LTA evolve con continuità, senza un evento dedicato
                            // di cambio stato.
                            groundTracker.setImuStaticState(motionDetector.isTrueStatic())
                            groundTracker.update(
                                sample = vr.vector,
                                isDetected = vr.isDetected,
                                isNoise = isNoise,
                                threshold = vectorProcessor.enterDetectionThreshold / vectorProcessor.kVect
                            )

                            // FIX (bug di propagazione, riassorbimento statico): finalizeStaticRecovery()
                            // in GroundTracker esegue sempre transitionTo(DETECTION) subito dopo essersi
                            // chiamato (siamo per costruzione dentro isDetected==true), quindi getState()
                            // non diventa mai "GROUND_LOCK" lungo questo percorso — la condizione di
                            // pubblicazione qui sotto (basata su getState()=="GROUND_LOCK") non si
                            // sarebbe mai verificata, e il centro corretto sarebbe rimasto intrappolato
                            // dentro GroundTracker, mai trasmesso a VectorProcessor. Controllo separato,
                            // incondizionato: va fatto ad ogni campione, a prescindere da stato/qualità/
                            // isDetected, perché consuma un evento one-shot (vedi consumeStaticRecoveryPending).
                            if (groundTracker.consumeStaticRecoveryPending()) {
                                vectorProcessor.setExternalBaseline(groundTracker.getCenter())
                                vectorProcessor.setExternalAxis(groundTracker.getGroundAngleDeg())
                                logToConsole("🧲 Riassorbimento statico propagato a VectorProcessor")
                            }

                            // PROTEZIONE IN USCITA: aggiorna la baseline SOLO se:
                            // - Il tracker è in GROUND_LOCK (stato stabile)
                            // - Non c'è detection attiva reale (vr.isDetected)
                            // - La qualità è sufficiente
                            if (groundTracker.isTracking() &&
                                groundTracker.isGroundLocked() &&
                                groundTracker.getUpdateCount() > 0 &&   // <-- AGGIUNTO
                                !vr.isDetected &&
                                groundTracker.getTrackingQuality() > 0.7f) {

                                vectorProcessor.setExternalBaseline(groundTracker.getCenter())
                                vectorProcessor.setExternalAxis(groundTracker.getGroundAngleDeg())
                            }
                        }

                        // ===== REGISTRAZIONE SESSIONE (CSV, ~100Hz) =====
                        // Tap indipendente dal throttle UI (33ms/~30Hz): usa data.timestampMs,
                        // il timestamp per-campione già equispaziato calcolato in SerialParser,
                        // per un downsampling a 100Hz più preciso di un semplice contatore.
                        if (isRecordingSession) {
                            if (data.timestampMs - lastRecordedSampleMs >= recordSampleIntervalMs) {
                                lastRecordedSampleMs = data.timestampMs
                                recordingRows.add(
                                    "%d,%.4f,%.2f,%.4f,%.2f,%b,%s,%d,%.1f,%b,%.4f,%.4f,%.3f,%b,%s,%.4f,%b,%.3f,%b".format(
                                        data.timestampMs,
                                        data.delta,
                                        data.phase,
                                        vr.vectorDistance,
                                        vr.relativeAngleDeg,
                                        vr.isDetected,
                                        vr.metalType,
                                        vr.vdi,
                                        vr.confidence,
                                        vr.isAngleValid,
                                        vectorProcessor.enterDetectionThreshold / vectorProcessor.kVect,
                                        vectorProcessor.exitDetectionThreshold / vectorProcessor.kVect,
                                        vectorProcessor.kVect,
                                        _isAutoGroundTracking.value,
                                        groundTracker.getState(),
                                        groundTracker.getDriftFromOrigin(),
                                        groundTracker.isDriftLimitReached(),
                                        groundTracker.getTrackingQuality(),
                                        motionDetector.isDeviceMoving()
                                    )
                                )
                            }

                            if (System.currentTimeMillis() - recordingStartMs >= recordingDurationMs) {
                                finishSessionRecording()
                            }
                        }

                        // ===== CALIBRAZIONE DEPTH =====
                        if (depthCalibrator.isCalibrating() && depthResult.features != null && !depthResult.isTracking) {
                            viewModelScope.launch {
                                val result = depthCalibrator.addCalibrationSample(
                                    eventFeatures = depthResult.features,
                                    vdi = depthResult.features.peakVdi,
                                    confidence = vr.confidence,
                                    threshold = vectorProcessor.enterDetectionThreshold,
                                    context = getApplication()
                                )
                                // addCalibrationSample() può completare la calibrazione
                                // internamente (campioni sufficienti raccolti) e quindi
                                // cambiare sia isCalibrating sia isCalibrated.
                                _isDepthCalibrating.value = depthCalibrator.isCalibrating()
                                _isDepthCalibrated.value = depthCalibrator.isCalibrated()
                                if (result != null) {
                                    _fingerprintCount.value = depthCalibrator.getFingerprintCount()
                                    logToConsole(result.message)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(getApplication(), result.message, Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    val status = depthCalibrator.getCalibrationStatus()
                                    logToConsole(status)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(getApplication(), status, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }

                        // ===== PREDIZIONE TARGET =====
                        if (depthCalibrator.getFingerprintCount() > 0 && depthResult.features != null && !depthResult.isTracking) {
                            lastPredictionEventMs = System.currentTimeMillis()
                            viewModelScope.launch {
                                val prediction = depthCalibrator.predictTarget(
                                    eventFeatures = depthResult.features,
                                    vdi = depthResult.features.peakVdi,
                                    threshold = vectorProcessor.enterDetectionThreshold
                                )
                                _currentPrediction.value =
                                    if (prediction != null && prediction.similarity > 60f) prediction else null
                            }
                        } else if (!vr.isDetected &&
                            System.currentTimeMillis() - lastPredictionEventMs > PREDICTION_HOLD_MS) {
                            _currentPrediction.value = null
                        }

                        // ===== UI THROTTLED =====
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdateMs >= UI_THROTTLE_MS) {
                            lastUiUpdateMs = now
                            withContext(Dispatchers.Main) {
                                updateUiState(vr, depthResult)

                                // G.ID/G.SET: sempre visibili se esiste un asse calibrato,
                                // indipendentemente dall'auto-tracking — servono per DECIDERE
                                // quando attivarlo, non solo per monitorarlo mentre è già attivo.
                                if (groundTracker.hasGroundAxis()) {
                                    _groundIdDeg.value = vectorProcessor.getGroundIdDeg()
                                    _groundSetDeg.value = vectorProcessor.getGroundSetDeg()
                                }

                                // Pubblicazione stato GroundTracker verso UI
                                if (_isAutoGroundTracking.value && groundTracker.isTracking()) {
                                    val quality = groundTracker.getTrackingQuality()
                                    _trackingQuality.value = quality
                                    _trackerUpdates.value = groundTracker.getUpdateCount()
                                    _displayAutoValue.value = groundTracker.getGroundAngleDeg()
                                    _trackingStatus.value = "Tracking: " + when {
                                        quality > 0.8f -> "✅ Buono"
                                        quality > 0.5f -> "⚠️ Medio"
                                        else -> "🔴 Basso"
                                    }
                                }
                            }
                        }

                        // ===== LOGGING PERIODICO STATUS (ogni 1 secondo) =====
                        val nowLog = System.currentTimeMillis()
                        if (nowLog - lastStatusLogMs >= STATUS_LOG_INTERVAL_MS) {
                            lastStatusLogMs = nowLog
                            logPeriodicStatus()
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

    private fun updateUiState(vr: VectorResult, depthResult: DepthAnalyzer.DepthResult) {
        vectorResult = vr
        groundAngleDeg = vectorProcessor.groundAxisDeg
        iqCurrent = vr.vector
        iqBaseline = vr.baseline
        liveDelta = vr.vectorDistance
        _isMetalDetecting.value = vr.isDetected
        normalizedDistance = (vr.vectorDistance / vectorProcessor.enterDetectionThreshold.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        phaseDiff = vr.relativeAngleDeg
        phaseNormalized = (vr.relativeAngleDeg / PHASE_DISPLAY_RANGE_DEG).coerceIn(-1f, 1f)

        val displayDepth = if (vr.isDetected) depthResult.depth else 0f

        analysis = AnalysisResult(
            vdi = vr.vdi,
            type = vr.metalType,
            confidence = vr.confidence,
            amplitude = vr.vectorDistance * 1000f,
            isLocked = _currentPrediction.value != null,
            depth = displayDepth
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
        pcaQuality = vp.lastPcaQuality.coerceIn(0f, 50f)
        terrainMineralization = if (_hasAirCalibration.value) {
            (vp.groundCenter.distanceTo(airBaseline) / airNoise.coerceAtLeast(0.001f)).coerceIn(0f, 10f)
        } else {
            0f
        }
        terrainReactivity = (vp.groundCenter.magnitude * 100f).coerceIn(0f, 10f)
        terrainStability = if (vp.tangentialRMS > 0.001f) (vp.tangentialSigma / vp.tangentialRMS).coerceIn(0f, 10f) else 0f
    }

    private fun sensFromK(k: Float): Float {
        // FIX: questa era ancora l'inversa della VECCHIA kFromSens (invertita, K
        // piccolo=più sensibile). Non l'avevo controllata quando ho corretto
        // kFromSens — i punti di rottura (1.1/1.59/2.2/3.0) sono quelli della
        // vecchia formula, non di quella giusta. Ricalcolata come vera inversa dei
        // 4 segmenti di kFromSens: [1,3]->[0.8,1.2], [3,5]->[1.2,1.8],
        // [5,8]->[1.8,2.5], [8,10]->[2.5,3.5].
        return when {
            k <= 1.2f -> 1f + (k - 0.8f) / 0.2f
            k <= 1.8f -> 3f + (k - 1.2f) / 0.3f
            k <= 2.5f -> 5f + (k - 1.8f) / ((2.5f - 1.8f) / 3f)
            else -> 8f + (k - 2.5f) / 0.5f
        }.coerceIn(1f, 10f)
    }

    private fun syncSensitivityFromProcessor() {
        val currentK = vectorProcessor.kVect
        val newSens = sensFromK(currentK)
        sensAmpiezza = newSens
        logToConsole("Sensibilità sincronizzata: %.1f (K=%.2f)".format(newSens, currentK))
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
                // Riferimento calibrato aggiornato: eventuali correzioni Manuale precedenti
                // ripartono da questo nuovo Air Balance, non si sommano a quelle vecchie.
                calibratedPhaseOffsetDeg = phaseOffset
                // FIX SCALA ADC (provvisorio): il clamp (0.15f, 2.5f) era tarato sulla
                // vecchia scala normalizzata, incompatibile con detectionMagnitude/magnitude
                // che vivono nella scala ADC reale (centinaia, vedi commento su
                // maxAllowedGroundMagnitude in GroundTracker.kt). A differenza dei parametri
                // del GroundTracker non esiste qui un gemello già ricalibrato empiricamente
                // da cui derivare il rapporto corretto: il clamp è quindi SOLO allargato a un
                // intervallo che non distorce rms*3.5 in condizioni normali, NON un valore
                // definitivo. Il log sotto mostra rms grezzo per tarare i bound reali dopo il
                // primo test in campo.
                logToConsole("🔍 DEBUG SCALA: rms grezzo (Air) = %.4f | soglia pre-clamp = %.4f".format(rms, rms * NOISE_TO_ENTER_THRESHOLD_MULTIPLIER))
                vectorProcessor.enterDetectionThreshold = (rms * NOISE_TO_ENTER_THRESHOLD_MULTIPLIER).coerceIn(ENTER_THRESHOLD_CLAMP_MIN, ENTER_THRESHOLD_CLAMP_MAX)
                vectorProcessor.exitDetectionThreshold = vectorProcessor.enterDetectionThreshold * EXIT_THRESHOLD_RATIO
                sensAmpiezza = 5f
                vectorProcessor.kVect = kFromSens(sensAmpiezza)
                airBaseline = baseline
                airPhaseOffsetDeg = phaseOffset
                airNoise = rms
                airEnterThreshold = vectorProcessor.enterDetectionThreshold
                airExitThreshold = vectorProcessor.exitDetectionThreshold

                _hasAirCalibration.value = true

                syncSensitivityFromProcessor()

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

    // =============================================================
    // RESET COMPLETO AIR CALIBRATION
    // =============================================================
    fun resetAirCalibration() {
        _hasAirCalibration.value = false
        _isPumpingDone.value = false
        resetGroundBalance()
        vectorProcessor.reset()

        if (_isAutoGroundTracking.value) {
            toggleAutoGroundTracking()
        }
        groundTracker.reset()

        airBaseline = IQVector.ZERO
        airPhaseOffsetDeg = 0f
        airNoise = 0.01f
        airEnterThreshold = 0.15f
        airExitThreshold = 0.09f

        _trackingStatus.value = "Inattivo"
        _trackingQuality.value = 1f
        _trackerUpdates.value = 0L
        _displayAutoValue.value = 0f
        _autoTrackingState.value = AutoTrackingState.IDLE

        logToConsole("🔄 Air Balance resettata completamente")
        Toast.makeText(getApplication(), "Air Balance resettata", Toast.LENGTH_SHORT).show()
    }

    fun toggleManualGroundBalance() {
        isManualGroundBalance = !isManualGroundBalance
        vectorProcessor.isManualGroundBalance = isManualGroundBalance

        if (isManualGroundBalance) {
            // Cattura il riferimento calibrato ORA (ultimo Air Balance/Pumping), come un
            // "auto ground balance" appena fatto su un detector professionale: il Manuale
            // parte da lì con correzione zero, non da un valore precedente residuo.
            calibratedPhaseOffsetDeg = vectorProcessor.groundPhaseOffsetDeg
            manualGroundAngleDeg = 0f
            setManualGroundAngle(manualGroundAngleDeg)
            logToConsole("🖐️ Ground Balance MANUALE - correzione da G.SET calibrato (%.1f°)".format(calibratedPhaseOffsetDeg))
        } else {
            logToConsole("🤖 Ground Balance AUTOMATICO")
            // Ripristina il riferimento puro, annullando qualunque correzione manuale.
            vectorProcessor.groundPhaseOffsetDeg = calibratedPhaseOffsetDeg
            vectorProcessor.clearGroundAxis()
        }
    }

    fun setManualGroundAngle(deg: Float) {
        isManualGroundBalance = true
        vectorProcessor.isManualGroundBalance = true

        manualGroundAngleDeg = deg.coerceIn(-45f, 45f)
        groundAngleDeg = calibratedPhaseOffsetDeg + manualGroundAngleDeg

        // FIX: scrive nel riferimento che DAVVERO guida l'angolo a schermo e la
        // classificazione (groundPhaseOffsetDeg), come correzione sopra il valore
        // calibrato — non più groundAxisDeg (vedi commento sul campo
        // calibratedPhaseOffsetDeg). Effetto collaterale utile: il ciclo periodico
        // dell'Auto-Tracking (blocco "GROUND TRACKER" nel loop campioni) non tocca MAI
        // groundPhaseOffsetDeg, quindi questa correzione non viene più sovrascritta,
        // con o senza Auto-Tracking acceso.
        vectorProcessor.groundPhaseOffsetDeg = wrapAngleDeg180(calibratedPhaseOffsetDeg + manualGroundAngleDeg)

        logToConsole("Manuale: correzione=%.1f° | G.SET effettivo=%.1f°".format(
            manualGroundAngleDeg, vectorProcessor.groundPhaseOffsetDeg
        ))
    }

    fun startGroundCalibration() {
        if (isGroundCalibrating) return
        if (usb.connectionStatus.value !is ConnectionStatus.Connected) return

        // FIX: svuota qualunque campione già in coda nel buffer telemetrico PRIMA di
        // iniziare — altrimenti quei campioni vecchi (prodotti prima che l'utente
        // avviasse il pumping) verrebbero consumati tutti insieme in un solo istante
        // appena parte la raccolta, dando l'impressione che il pumping si completi da
        // solo senza che la bobina sia stata mossa.
        usb.drainTelemetryBacklog()

        if (isManualGroundBalance) {
            isManualGroundBalance = false
            vectorProcessor.isManualGroundBalance = false
        }

        if (_isAutoGroundTracking.value) {
            toggleAutoGroundTracking()
        }

        groundCalibrator.startAccumulation()
        isGroundCalibrating = true
        vectorProcessor.isCalibratingGround = true
        pumpingDiagnostics = null   // Resetta la diagnostica live

        // FIX: seeding da pumping. Il pump è l'unico momento in cui sappiamo con certezza
        // che l'utente sta attivamente maneggiando/muovendo il rilevatore: usiamo i campioni
        // del giroscopio raccolti in questa finestra per inizializzare il rumore di fondo
        // (LTA) del trigger adattivo con dati reali di QUESTA sessione/utente/dispositivo,
        // invece che con le costanti di default o col rumore ambientale pre-pump (es. il
        // telefono fermo su un tavolo prima di iniziare).
        motionDetector.beginPumpSeeding()

        pumpingMotionDetected = false
        pumpingCurrentSample = IQVector.ZERO
        pumpingBaseline = IQVector.ZERO
        pumpingDotAccepted = true
        pumpingAcceptedCount = 0
        pumpingRejectedCount = 0

        pcaQuality = 0.0f
        terrainMineralization = 0.0f
        terrainStability = 0.0f

        logToConsole(" Calibrazione (Pumping) avviata. Pompa la bobina...")
        Toast.makeText(getApplication(), "Pompa la bobina sul terreno!", Toast.LENGTH_SHORT).show()

        // FIX: un tempo fisso (10s) non ha senso quando il vero requisito è un NUMERO di
        // campioni validi (minSamplesRequired) — 10s possono essere pochi con una pompata
        // lenta/delicata, o inutilmente tanti con una pompata energica. Ora la calibrazione
        // finisce da sola appena si raggiunge il numero di campioni accettati richiesto,
        // con un tetto di sicurezza (30s) solo per non restare bloccati all'infinito se il
        // movimento non viene mai riconosciuto come valido.
        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            val minPumpingDurationMs = 5000L
            val safetyTimeoutMs = 30000L
            // FIX: diagnostica di tuning in console (logToConsole, NON logcat — visibile
            // in app anche con la USB occupata dal seriale ESP32). Stampa ogni ~1s (ogni
            // 5° ciclo del loop, che gira a 200ms) i valori reali di oscillationCount,
            // currentAmplitude e restNoiseAmplitudeSpan, indispensabili per tarare
            // GroundCalibrator.movementThreshold su dati misurati invece che a stima.
            var loopTick = 0
            while (isGroundCalibrating) {
                delay(200.milliseconds)
                loopTick++
                val elapsed = System.currentTimeMillis() - startMs
                val realOscillations = pumpingDiagnostics?.oscillationCount ?: 0
                val realSampleCount = pumpingDiagnostics?.sampleCount ?: 0

                if (loopTick % 5 == 0) {
                    val diag = pumpingDiagnostics
                    logToConsole("📊 Pump t=${elapsed}ms n=$realSampleCount osc=$realOscillations ampiezza=%.4f rumoreRiposo=%.4f".format(
                        diag?.currentAmplitude ?: 0f, diag?.restNoiseAmplitudeSpan ?: 0f
                    ))
                }

                // FIX: pumpingAcceptedCount (contatore del ViewModel, mai risincronizzato)
                // poteva disallinearsi da samples.size dentro GroundCalibrator, che viene
                // svuotato alla prima oscillazione vera confermata (scarto del
                // riscaldamento pre-pumping, vedi GroundCalibrator). pumpingDiagnostics
                // riflette invece sempre la dimensione VERA del buffer interno, aggiornata
                // ad ogni addSample().
                if (elapsed >= minPumpingDurationMs &&
                    realSampleCount >= groundCalibrator.minSamplesRequired &&
                    realOscillations >= groundCalibrator.minOscillationsRequired) {
                    logToConsole("✅ $realSampleCount campioni e $realOscillations pompate raccolte in ${elapsed}ms: calcolo automatico.")
                    finishGroundCalibration()
                    break
                }
                if (elapsed >= safetyTimeoutMs) {
                    logToConsole("⏰ Timeout di sicurezza (30s): calibrazione arrestata automaticamente.")
                    finishGroundCalibration()
                    break
                }
            }
        }
    }

    fun finishGroundCalibration() {
        if (!isGroundCalibrating) return

        calibrationJob?.cancel()
        calibrationJob = null

        val report = groundCalibrator.computeCalibration()

        // FIX: chiude la finestra di seeding e calcola il rumore di fondo (LTA) da dati
        // reali raccolti durante il pumping, indipendentemente dal successo della
        // calibrazione del terreno (i dati del giroscopio restano validi anche se il
        // pumping non ha prodotto un buon asse PCA).
        motionDetector.endPumpSeeding()
        val motionDiag = motionDetector.getAdaptiveDiagnostics()
        logToConsole("🌱 Seeding motion detector: LTA=%.4f rad/s | calibrato=%s".format(
            motionDiag.lta, motionDiag.isCalibrated
        ))

        this.pcaQuality = report.pcaQuality
        //  this.terrainStability = (10.0f - report.tangentialRMS * 100f).coerceIn(0f, 10f)

        var toastMessage = report.message

        if (report.success) {
            this.terrainMineralization = (report.groundCenter.magnitude * 100f).coerceIn(0f, 10f)
            this.terrainReactivity = if (report.groundAngleDeg > 0) 1.5f else 0.5f

            vectorProcessor.applyGroundCalibration(report.groundCenter, report.centerPhaseDeg, report.groundAngleDeg)

            val orientedAxisDeg = vectorProcessor.groundAxisDeg
            // Nuovo riferimento calibrato dal Pumping appena riuscito: come un "auto ground
            // balance" su un detector professionale, un'eventuale correzione Manuale
            // precedente riparte da zero sopra QUESTO valore, non si somma al vecchio.
            calibratedPhaseOffsetDeg = vectorProcessor.groundPhaseOffsetDeg

            vectorProcessor.tangentialRMS = report.tangentialRMS
            vectorProcessor.tangentialSigma = report.tangentialSigma
            vectorProcessor.groundCenter = report.groundCenter
            vectorProcessor.lastPcaQuality = report.pcaQuality

            val newNoise = report.tangentialRMS * 1.2f
            vectorProcessor.setNoiseEstimate(newNoise.coerceAtLeast(0.001f))
            // FIX SCALA ADC (provvisorio): vedi commento identico in performCalibration().
            // Stesso clamp allargato, stesso bisogno di dati reali per il valore definitivo.
            logToConsole("🔍 DEBUG SCALA: tangentialRMS grezzo (Pumping) = %.4f | newNoise = %.4f | soglia pre-clamp = %.4f".format(
                report.tangentialRMS, newNoise, newNoise * NOISE_TO_ENTER_THRESHOLD_MULTIPLIER))
            vectorProcessor.enterDetectionThreshold = (newNoise * NOISE_TO_ENTER_THRESHOLD_MULTIPLIER).coerceIn(ENTER_THRESHOLD_CLAMP_MIN, ENTER_THRESHOLD_CLAMP_MAX)
            vectorProcessor.exitDetectionThreshold = vectorProcessor.enterDetectionThreshold * EXIT_THRESHOLD_RATIO

            // FIX: manualGroundAngleDeg è ora un delta di fase (vedi commento sul campo
            // calibratedPhaseOffsetDeg), non più un asse — assegnargli orientedAxisDeg (un
            // grado di un dominio diverso) sarebbe scorretto. In pratica isManualGroundBalance
            // è già false qui in condizioni normali (startGroundCalibration lo disattiva
            // prima di avviare il Pumping); per sicurezza, se risultasse comunque attivo,
            // riparte da correzione zero sul nuovo riferimento appena calibrato.
            if (isManualGroundBalance) {
                manualGroundAngleDeg = 0f
                setManualGroundAngle(0f)
            }
            groundAngleDeg = orientedAxisDeg

            // INIZIALIZZA IL GROUND TRACKER CON L'ASSE DAL PUMPING (già orientato)
            groundTracker.setGroundAxis(orientedAxisDeg, report.groundCenter)
            _displayAutoValue.value = orientedAxisDeg
            _groundSetDeg.value = vectorProcessor.getGroundSetDeg()
            _groundIdDeg.value = vectorProcessor.getGroundIdDeg()

            // SETTA IL FLAG PUMPING COMPLETATO
            _isPumpingDone.value = true

            // FIX: report.message contiene l'angolo GREZZO calcolato dentro
            // GroundCalibrator, PRIMA che vectorProcessor.applyGroundCalibration() lo
            // orienti (può ruotarlo di 180°). Il Toast mostrava quindi un angolo diverso
            // da quello poi effettivamente usato/mostrato ovunque nel resto dell'app
            // (log, UI, GroundTracker) — stesso valore fisico, verso opposto.
            // Ricostruito qui con orientedAxisDeg, lo stesso identico numero che va nel
            // log e in tutto lo stato interno.
            toastMessage = "✅ Calibrazione riuscita! Angolo: %.1f° | PCA: %.1f".format(orientedAxisDeg, report.pcaQuality)

            logToConsole("✅ %s Angolo: %.1f° | Stabilità: %.2f".format(report.message, orientedAxisDeg, this.terrainStability))
            logToConsole("✅ Nuove soglie: Entra=%.3f Esci=%.3f".format(
                vectorProcessor.enterDetectionThreshold,
                vectorProcessor.exitDetectionThreshold
            ))
            logToConsole("✅ GroundTracker inizializzato con asse: %.1f°".format(orientedAxisDeg))
            logToConsole("✅ Auto Tracking e Manuale ora disponibili")
        } else {
            logToConsole("❌ Calibrazione Rifiutata: ${report.message}")
            // SE PUMPING FALLISCE, RESETTA IL FLAG
            _isPumpingDone.value = false
        }

        isGroundCalibrating = false
        vectorProcessor.isCalibratingGround = false

        Toast.makeText(getApplication(), toastMessage, Toast.LENGTH_LONG).show()
    }

    fun resetGroundBalance() {
        if (isGroundCalibrating) {
            calibrationJob?.cancel()
            calibrationJob = null
            isGroundCalibrating = false
            vectorProcessor.isCalibratingGround = false
            // FIX: se il pumping viene interrotto qui invece che da finishGroundCalibration(),
            // la finestra di seeding del motion detector va comunque chiusa esplicitamente,
            // altrimenti isSeedingFromPump resta bloccato aperto e i campioni continuano ad
            // accumularsi indefinitamente.
            motionDetector.endPumpSeeding()
        }

        if (isManualGroundBalance) {
            isManualGroundBalance = false
            vectorProcessor.isManualGroundBalance = false
        }

        if (_isAutoGroundTracking.value) {
            toggleAutoGroundTracking()
        }

        vectorProcessor.clearGroundAxis()

        // RESETTA ANCHE IL GROUND TRACKER E IL FLAG PUMPING
        groundTracker.reset()
        _displayAutoValue.value = 0f
        _groundIdDeg.value = 0f
        _groundSetDeg.value = 0f
        _autoTrackingState.value = AutoTrackingState.IDLE
        _isPumpingDone.value = false

        if (_hasAirCalibration.value) {
            vectorProcessor.setBaselineIQ(airBaseline)
            vectorProcessor.groundPhaseOffsetDeg = airPhaseOffsetDeg
            vectorProcessor.setNoiseEstimate(airNoise)
            vectorProcessor.enterDetectionThreshold = airEnterThreshold
            vectorProcessor.exitDetectionThreshold = airExitThreshold
            vectorProcessor.kVect = kFromSens(5f)
            sensAmpiezza = 5f
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

        // Riferimento calibrato riallineato al nuovo stato (Air Balance o zero): eventuali
        // correzioni Manuale successive ripartono da qui, non dal valore pre-reset.
        calibratedPhaseOffsetDeg = vectorProcessor.groundPhaseOffsetDeg

        manualGroundAngleDeg = 0f
        groundAngleDeg = 0f

        this.pcaQuality = 1.0f
        this.terrainMineralization = 0f
        this.terrainReactivity = 0f
        this.terrainStability = 0f

        Toast.makeText(getApplication(), "Ground Balance resettato", Toast.LENGTH_SHORT).show()
    }

    // =============================================================
    // FUNZIONI AUTO-TRACKING CON STATO DI TRANSIZIONE
    // =============================================================

    fun toggleAutoGroundTracking() {
        val currentState = _isAutoGroundTracking.value

        if (currentState) {
            // Disattivazione
            _autoTrackingState.value = AutoTrackingState.DEACTIVATING
            _isAutoGroundTracking.value = false

            // FIX: groundTracker.reset() cancellava l'asse calibrato dal pumping ad ogni
            // disattivazione, anche solo temporanea. groundTracker.update() è già
            // condizionato da _isAutoGroundTracking (vedi sopra), quindi il tracker smette
            // comunque di aggiornarsi qui — non serve nessun reset per "metterlo in pausa".
            // Cancellare l'asse costringeva a un pumping da capo ad ogni riattivazione,
            // anche quando i dati calibrati erano ancora perfettamente validi.
            vectorProcessor.clearExternalOverrides()
            _trackingStatus.value = "Inattivo"
            _displayAutoValue.value = 0f

            viewModelScope.launch {
                delay(150)
                _autoTrackingState.value = AutoTrackingState.IDLE
            }

            logToConsole("⏹ Auto-Tracking disattivato")
            Toast.makeText(getApplication(), "Auto-Tracking disattivato", Toast.LENGTH_SHORT).show()
        } else {
            // Verifica che il Pumping sia stato eseguito
            if (!_isPumpingDone.value || !groundTracker.hasGroundAxis()) {
                Toast.makeText(getApplication(), "⚠️ Esegui prima il PUMPING!", Toast.LENGTH_LONG).show()
                logToConsole("⚠️ Auto-Tracking: esegui prima il PUMPING")
                return
            }

            // Attivazione
            _autoTrackingState.value = AutoTrackingState.ACTIVATING
            _displayAutoValue.value = groundTracker.getGroundAngleDeg()

            // Inizializza il tracker con l'asse dal Pumping
            vectorProcessor.setExternalBaseline(groundTracker.getCenter())
            vectorProcessor.setExternalAxis(groundTracker.getGroundAngleDeg())

            _isAutoGroundTracking.value = true
            _trackingStatus.value = "Auto-Tracking attivo"
            _displayAutoValue.value = groundTracker.getGroundAngleDeg()

            viewModelScope.launch {
                delay(150)
                _autoTrackingState.value = AutoTrackingState.ACTIVE
            }

            logToConsole("🤖 Auto-Tracking attivato (asse dal Pumping)")
            Toast.makeText(getApplication(), "Auto-Tracking attivato", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Chiamata quando GroundTracker.requestBaselineRecovery() segnala che i
     * recuperi si sono ripetuti troppo in fretta (vedi recoveryDisableCount):
     * la baseline non è più affidabile, meglio fermarsi ed essere espliciti con
     * l'utente piuttosto che continuare a girare a vuoto su falsi positivi.
     */
    private fun forceDisableAutoTrackingForRecalibration() {
        _autoTrackingState.value = AutoTrackingState.DEACTIVATING
        _isAutoGroundTracking.value = false
        vectorProcessor.clearExternalOverrides()
        _trackingStatus.value = "⚠️ Baseline instabile"
        _displayAutoValue.value = 0f

        viewModelScope.launch {
            delay(150)
            _autoTrackingState.value = AutoTrackingState.IDLE
        }

        logToConsole("🛑 Auto-Tracking disattivato: troppi recuperi ravvicinati, la baseline non è più affidabile. Esegui un nuovo PUMPING.")
        Toast.makeText(
            getApplication(),
            "⚠️ Auto-Tracking disattivato: rifai il PUMPING",
            Toast.LENGTH_LONG
        ).show()
    }

    fun resetAutoTracker() {
        if (_isAutoGroundTracking.value) {
            val currentAxis = groundTracker.getGroundAngleDeg()
            val currentCenter = groundTracker.getCenter()

            groundTracker.reset()
            groundTracker.setGroundAxis(currentAxis, currentCenter)

            vectorProcessor.setExternalBaseline(groundTracker.getCenter())
            vectorProcessor.setExternalAxis(groundTracker.getGroundAngleDeg())

            _trackingStatus.value = "Reset effettuato"
            _trackingQuality.value = 1f
            _trackerUpdates.value = 0L
            _displayAutoValue.value = groundTracker.getGroundAngleDeg()

            logToConsole("🔄 Tracker resettato (asse mantenuto)")
            Toast.makeText(getApplication(), "Tracker resettato", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(getApplication(), "Auto-Tracking non attivo", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== FUNZIONI CALIBRAZIONE DEPTH =====

    fun startDepthCalibration(name: String, metalType: DepthCalibrator.MetalType, depthCm: Float) {
        viewModelScope.launch {
            val msg = depthCalibrator.startCalibration(name, metalType, depthCm)
            _isDepthCalibrating.value = depthCalibrator.isCalibrating()
            _isDepthCalibrated.value = depthCalibrator.isCalibrated()
            logToConsole(msg)
            Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
        }
    }

    fun completeDepthCalibration() {
        viewModelScope.launch {
            val result = depthCalibrator.completeCalibration(getApplication())
            _fingerprintCount.value = depthCalibrator.getFingerprintCount()
            _isDepthCalibrating.value = depthCalibrator.isCalibrating()
            _isDepthCalibrated.value = depthCalibrator.isCalibrated()
            logToConsole(result.message)
            Toast.makeText(getApplication(), result.message, Toast.LENGTH_LONG).show()
        }
    }

    fun cancelDepthCalibration() {
        viewModelScope.launch {
            depthCalibrator.cancelCalibration()
            _isDepthCalibrating.value = depthCalibrator.isCalibrating()
            _isDepthCalibrated.value = depthCalibrator.isCalibrated()
            logToConsole("Calibrazione annullata")
            Toast.makeText(getApplication(), "Calibrazione annullata", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearAllFingerprints() {
        viewModelScope.launch {
            depthCalibrator.clearFingerprints(getApplication())
            _fingerprintCount.value = 0
            _currentPrediction.value = null
            logToConsole("🧹 Impronte cancellate")
            Toast.makeText(getApplication(), "Impronte cancellate", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveFingerprintsToFile() {
        val success = depthCalibrator.saveToFile(getApplication())
        if (success) {
            _fingerprintCount.value = depthCalibrator.getFingerprintCount()
            logToConsole("💾 Impronte salvate su file")
            Toast.makeText(getApplication(), "Impronte salvate", Toast.LENGTH_SHORT).show()
        } else {
            logToConsole("❌ Errore salvataggio impronte")
            Toast.makeText(getApplication(), "Errore salvataggio", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== REGISTRAZIONE SESSIONE (CSV in Download) =====

    /**
     * Avvia la registrazione di una sessione diagnostica completa (delta/fase grezzi,
     * stato di detection, stato del GroundTracker) a ~100Hz per la durata indicata.
     * Al termine il file viene salvato automaticamente come CSV nella cartella Download
     * pubblica del dispositivo tramite MediaStore - vedi finishSessionRecording() per il
     * motivo per cui questo NON richiede alcun permesso runtime su Android 10+.
     */
    fun startSessionRecording(durationSeconds: Int = 30) {
        if (isRecordingSession) {
            logToConsole("⚠️ Registrazione già in corso, attendi il termine")
            return
        }
        if (usb.connectionStatus.value !is ConnectionStatus.Connected) {
            logToConsole("❌ Registrazione non avviata: dispositivo non connesso")
            Toast.makeText(getApplication(), "Connetti il dispositivo prima di registrare", Toast.LENGTH_SHORT).show()
            return
        }

        recordingRows.clear()
        recordingRows.add(
            "timestampMs,rawDelta,rawPhaseDeg,vectorDistance,relativeAngleDeg,isDetected,metalType,vdi," +
                    "confidence,isAngleValid,enterThresholdEff,exitThresholdEff,kVect,isAutoTracking,trackerState," +
                    "driftFromOrigin,driftLimitReached,trackingQuality,isCoilMoving"
        )
        recordingStartMs = System.currentTimeMillis()
        recordingDurationMs = durationSeconds.coerceIn(1, 300) * 1000L
        lastRecordedSampleMs = 0L
        isRecordingSession = true
        _isRecording.value = true

        logToConsole("🔴 Registrazione sessione avviata: ${durationSeconds}s a 100Hz")
        Toast.makeText(getApplication(), "Registrazione avviata (${durationSeconds}s)", Toast.LENGTH_SHORT).show()
    }

    /**
     * Chiude la registrazione in corso e salva il CSV accumulato nella cartella Download
     * pubblica, usando l'API MediaStore.Downloads (introdotta in Android 10 / API 29).
     *
     * PERCHÉ MediaStore E NON WRITE_EXTERNAL_STORAGE:
     * A partire da Android 10 (scoped storage), e in modo definitivo da Android 11, un'app
     * non può più scrivere liberamente nella cartella Download pubblica dichiarando il
     * permesso WRITE_EXTERNAL_STORAGE nel manifest: quel permesso, su queste versioni, NON
     * dà più accesso alle cartelle pubbliche condivise (Download, Pictures, ecc.) di altre
     * app. L'unica via supportata per scrivere un nuovo file in Download senza chiedere
     * all'utente un permesso invasivo (tipo MANAGE_EXTERNAL_STORAGE, riservato ad app di
     * gestione file) è passare per il ContentResolver con MediaStore.Downloads: il sistema
     * crea e possiede il file per conto dell'app, senza alcun dialogo di permesso runtime.
     * Questo funziona in modo identico su Android 10, 11, 12, 13+: nessuna differenza di
     * codice richiesta tra le versioni, a differenza del vecchio approccio con
     * Environment.getExternalStoragePublicDirectory() + WRITE_EXTERNAL_STORAGE, che su
     * Android 11+ fallisce silenziosamente o richiede requestLegacyExternalStorage (sconsigliato,
     * non supportato in modo affidabile oltre Android 10).
     * NON serve quindi aggiungere alcun permesso al AndroidManifest.xml per questa funzione.
     */
    private fun finishSessionRecording() {
        isRecordingSession = false
        _isRecording.value = false

        val rowCount = recordingRows.size - 1 // -1 per l'header
        if (rowCount <= 0) {
            logToConsole("❌ Registrazione: nessun campione raccolto")
            recordingRows.clear()
            return
        }

        val fileName = "metaldetector_session_${System.currentTimeMillis()}.csv"
        val csvContent = recordingRows.joinToString("\n")
        recordingRows.clear()

        viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                val resolver = getApplication<Application>().contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    // RELATIVE_PATH esiste solo da API 29 in su; il progetto già gestisce
                    // rami Build.VERSION_CODES condizionali altrove (vedi MainActivity.kt),
                    // quindi lo stesso pattern è coerente col resto del codice.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(csvContent.toByteArray())
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("SessionRecording", "Errore salvataggio CSV", e)
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    logToConsole("💾 Registrazione salvata: Download/$fileName ($rowCount campioni)")
                    Toast.makeText(getApplication(), "Salvato in Download: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    logToConsole("❌ Errore salvataggio registrazione CSV")
                    Toast.makeText(getApplication(), "Errore salvataggio CSV", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===== USB E CONNESSIONE =====

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
                // dataCollectionJob è mutato altrove (rebuildDataCollection(), sempre su
                // Main) solo da Main: confinare anche questa cancellazione su Main evita
                // una scrittura cross-thread non sincronizzata sulla stessa var.
                withContext(Dispatchers.Main) {
                    dataCollectionJob?.cancel()
                    dataCollectionJob = null
                }

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

    // ===== COMANDI E UTILITY =====

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

        if (lower.startsWith("record")) {
            val parts = lower.split(" ")
            val seconds = parts.getOrNull(1)?.toIntOrNull() ?: 30
            startSessionRecording(seconds)
            return
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
        logToConsole("Baseline: I=%.4f Q=%.4f".format(iqBaseline.i, iqBaseline.q))
        logToConsole("Vettore: I=%.4f Q=%.4f".format(iqCurrent.i, iqCurrent.q))
        logToConsole("Energia: %.4f SogliaEntra: %.4f Rumore: %.4f".format(
            vr?.vectorDistance ?: 0f, vectorProcessor.enterDetectionThreshold, vectorProcessor.noiseMagnitude))
        logToConsole("Angolo: %.1f° Tipo: %s Det: %s".format(vr?.relativeAngleDeg ?: 0f, vr?.metalType ?: "?", vr?.isDetected))

        if (_isAutoGroundTracking.value) {
            logToConsole("🤖 Auto-Tracking: Attivo | Qualità: ${"%.0f".format(_trackingQuality.value * 100)}% | Aggiornamenti: ${_trackerUpdates.value}")
        } else {
            logToConsole("🤖 Auto-Tracking: Disattivato")
        }

        if (_isPumpingDone.value) {
            logToConsole("✅ Pumping: Completato - Auto Tracking disponibile")
        } else {
            logToConsole("⏳ Pumping: Non eseguito - Auto Tracking non disponibile")
        }

        // Stato del movimento
        logToConsole("📡 Movimento: ${if (motionDetector.isDeviceMoving()) "🏃 IN MOVIMENTO" else "🛑 FERMO"} (${motionDetector.getSensorStatus()})")
        val diag = motionDetector.getAdaptiveDiagnostics()
        logToConsole("   MotionDiag: STA=${"%.4f".format(diag.sta)} LTA=${"%.4f".format(diag.lta)} ratio=${"%.2f".format(diag.ratio)} calibrato=${diag.isCalibrated}")

        // Stato del GroundTracker
        if (groundTracker.hasGroundAxis()) {
            logToConsole("📐 GroundTracker: Asse = %.1f° | Qualità = %.0f%% | Stato: %s".format(
                groundTracker.getGroundAngleDeg(),
                groundTracker.getTrackingQuality() * 100,
                groundTracker.getState()
            ))
        } else {
            logToConsole("📐 GroundTracker: Non inizializzato (esegui PUMPING)")
        }
    }

    // ===== LOGGING PERIODICO STATUS (automatico ogni 1 secondo) =====
    private fun logPeriodicStatus() {
        val sb = StringBuilder()
        sb.append("=== STATUS PERIODICO ===\n")

        // Movimento
        sb.append("Movimento: ${if (motionDetector.isDeviceMoving()) "🏃 IN MOVIMENTO" else "🛑 FERMO"}\n")

        // MotionDiag
        val diag = motionDetector.getAdaptiveDiagnostics()
        sb.append("MotionDiag: STA=${"%.4f".format(diag.sta)} LTA=${"%.4f".format(diag.lta)} ratio=${"%.2f".format(diag.ratio)} calibrato=${diag.isCalibrated}\n")

        // GroundTracker
        if (groundTracker.hasGroundAxis()) {
            sb.append("GroundTracker: Asse=${"%.1f".format(groundTracker.getGroundAngleDeg())}° ")
            sb.append("Stato=${groundTracker.getState()} ")
            sb.append("Aggiornamenti=${groundTracker.getUpdateCount()} ")
            sb.append("Qualità=${"%.0f".format(groundTracker.getTrackingQuality() * 100)}%\n")
            sb.append("radialDelta=${"%.4f".format(groundTracker.getLastRadialDelta())}\n")   // <-- AGGIUNTO
        } else {
            sb.append("GroundTracker: NON INIZIALIZZATO\n")
        }

        // VectorProcessor
        val vr = vectorResult
        if (vr != null) {
            sb.append("Energia=${"%.4f".format(vr.vectorDistance)} ")
            sb.append("Soglia=${"%.4f".format(vectorProcessor.enterDetectionThreshold)} ")
            sb.append("Det=${vr.isDetected} ")
            sb.append("Tipo=${vr.metalType}\n")
        }

        sb.append("Tempo: ${System.currentTimeMillis()}")
        logToConsole(sb.toString())
    }

    private fun logToConsole(msg: String) {
        console.add(msg)
        if (console.size > 120) console.removeAt(0)
    }

    // =============================================================
    // CLEANUP: ferma il motion detector e tutto il resto
    // =============================================================
    override fun onCleared() {
        motionDetector.stopListening()
        usb.cleanup()
        vectorAudio.stop()
        super.onCleared()
    }
}