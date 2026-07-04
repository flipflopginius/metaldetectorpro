package com.tetranova.metaldetectorpro
import kotlin.math.*

data class IQVector(val i: Float, val q: Float) {
    val magnitude: Float get() = sqrt(i * i + q * q)
    operator fun plus(o: IQVector) = IQVector(i + o.i, q + o.q)
    operator fun minus(o: IQVector) = IQVector(i - o.i, q - o.q)
    fun distanceTo(o: IQVector): Float = sqrt((i - o.i) * (i - o.i) + (q - o.q) * (q - o.q))
    companion object { val ZERO = IQVector(0f, 0f) }
}

data class VectorResult(
    val vector: IQVector,
    val baseline: IQVector,
    val delta: IQVector,
    val vectorDistance: Float,
    val relativeAngleDeg: Float,
    val isFerroso: Boolean,
    val isDetected: Boolean,
    val confidence: Float,
    val vdi: Int,
    val metalType: String,
    val isAngleValid: Boolean
)

class VectorProcessor {
    var onLogMessage: ((String) -> Unit)? = null
    var isManualGroundBalance = false
    var isCalibratingGround = false
    var nonFerroLowDeg = 4.0f
    var ferroMinDeg = 5.0f
    var kVect = 1.2f
    var phaseHysteresisDeg = 1.5f
    var enterDetectionThreshold = 0.15f
    var exitDetectionThreshold = 0.09f
    var noiseMagnitude = 0.01f
    var autoRetuneTimeMs = 5000L
    private var detectionStartTimeMs = 0L

    // Fase ASSOLUTA di riferimento (stesso dominio di rawPhaseDeg).
    // Usata SOLO per la lancetta a schermo e per la rotazione di discriminazione.
    var groundPhaseOffsetDeg = 0f

    var baselineVector = IQVector.ZERO   // traslazione
    var tangentialRMS = 0f
    var tangentialSigma = 0f
    var groundCenter = IQVector.ZERO
    var discAnglePolarity = 1.0f
    var lastPcaQuality = 1.0f

    // Asse di terreno (dominio Cartesiano del vettore compensato, NON fase assoluta).
    // Usato SOLO per scomporre il segnale in radiale (terreno/pumping, da ignorare)
    // e tangenziale (bersaglio, da rilevare).
    var hasGroundAxis = false
        private set
    var groundAxisDeg = 0f
        private set
    private var groundAxisCos = 1f
    private var groundAxisSin = 0f

    private var smoothedI = 0f
    private var smoothedQ = 0f
    private val glenEmaAlpha = 0.15f
    private var phaseFilterSin = 0f
    private var phaseFilterCos = 1f
    private val phaseDiffAlpha = 0.25f
    private var internalIsDetected = false

    fun processSample(rawDelta: Float, rawPhaseDeg: Float): VectorResult {
        val rad = Math.toRadians(rawPhaseDeg.toDouble())
        val realI = (rawDelta * cos(rad)).toFloat()
        val realQ = (rawDelta * sin(rad)).toFloat()

        if (smoothedI == 0f && smoothedQ == 0f) {
            smoothedI = realI
            smoothedQ = realQ
        } else {
            smoothedI += glenEmaAlpha * (realI - smoothedI)
            smoothedQ += glenEmaAlpha * (realQ - smoothedQ)
        }

        val filteredInput = IQVector(smoothedI, smoothedQ)
        val compensated = filteredInput - baselineVector
        val magnitude = compensated.magnitude

        // Metrica usata per la SOGLIA di rilevazione: se conosciamo l'asse di
        // terreno, usiamo solo la componente tangenziale (fuori asse). Senza
        // asse noto (solo aria-cal), degradiamo alla magnitudine totale:
        // comportamento identico a prima della modifica.
        val detectionMagnitude = if (hasGroundAxis) {
            abs(-compensated.i * groundAxisSin + compensated.q * groundAxisCos)
        } else {
            magnitude
        }

        val wasDetected = internalIsDetected

        internalIsDetected = if (wasDetected) {
            detectionMagnitude > exitDetectionThreshold
        } else {
            detectionMagnitude > enterDetectionThreshold
        }

        // Controllo Timer e Auto-Retune di sicurezza
        val now = System.currentTimeMillis()
        if (internalIsDetected && !wasDetected) {
            // Fronte di salita: appena entrati in rilevazione, facciamo partire il timer
            detectionStartTimeMs = now
        } else if (internalIsDetected && (now - detectionStartTimeMs > autoRetuneTimeMs)) {
            // TIMEOUT SUPERATO: Il target è lì da troppo tempo (es. appoggiato sul tavolo)

            // 1. Forza l'uscita dallo stato di rilevazione
            internalIsDetected = false

            // 2. Ingloba il disturbo o il target statico nella nuova baseline
            setBaselineIQ(filteredInput)

            // 3. Resetta i filtri di fase per eliminare artefatti visivi o audio
            phaseFilterSin = 0f
            phaseFilterCos = 1f

            // 4. Notifica la UI/Console del reset automatico
            onLogMessage?.invoke("Timeout 5s: Autotune forzato. Nuova baseline acquisita.")
        }

        val relativePhase = normalizeAngle(rawPhaseDeg - groundPhaseOffsetDeg)
        val relRad = Math.toRadians(relativePhase.toDouble())
        phaseFilterSin += phaseDiffAlpha * (sin(relRad).toFloat() - phaseFilterSin)
        phaseFilterCos += phaseDiffAlpha * (cos(relRad).toFloat() - phaseFilterCos)

        val smoothedPhase = Math.toDegrees(atan2(phaseFilterSin.toDouble(), phaseFilterCos.toDouble())).toFloat()
        val displayAngleDeg = smoothedPhase * discAnglePolarity

        var metalType = "AMBIGUO"
        var vdi = 0
        val isAngleValidCheck = magnitude > (noiseMagnitude * 1.5f)

        if (magnitude > 0.001f && internalIsDetected && isAngleValidCheck) {
            val rawAngle = atan2(compensated.q, compensated.i) * (180f / PI.toFloat())
            val angleDeg = normalizeAngle(rawAngle - groundPhaseOffsetDeg) * discAnglePolarity

            if (angleDeg > nonFerroLowDeg) {
                metalType = "NON_FERRO"
                vdi = angleDeg.roundToInt().coerceIn(1, 99)
            } else if (angleDeg < -ferroMinDeg) {
                metalType = "FERRO"
                vdi = angleDeg.roundToInt().coerceIn(-99, -1)
            }
        }

        return VectorResult(
            vector = filteredInput,
            baseline = baselineVector,
            delta = compensated,
            vectorDistance = magnitude,
            relativeAngleDeg = displayAngleDeg,
            isFerroso = (metalType == "FERRO"),
            isDetected = internalIsDetected,
            confidence = if (internalIsDetected && isAngleValidCheck) 1.0f else 0f,
            vdi = vdi,
            metalType = metalType,
            isAngleValid = isAngleValidCheck
        )
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    fun setBaselineIQ(vector: IQVector) {
        this.baselineVector = vector
        this.smoothedI = vector.i
        this.smoothedQ = vector.q
    }

    fun setNoiseEstimate(noise: Float) {
        this.noiseMagnitude = noise.coerceAtLeast(0.001f)
    }

    fun setDiscThresholds(low: Float, high: Float) {
        this.nonFerroLowDeg = low
        this.ferroMinDeg = high
    }

    /** Applica il risultato della calibrazione a pompaggio: baseline, fase assoluta e asse di terreno, in un'unica operazione atomica. */
    fun applyGroundCalibration(baseline: IQVector, phaseOffsetDeg: Float, groundAxisDeg: Float) {
        setBaselineIQ(baseline)
        groundPhaseOffsetDeg = phaseOffsetDeg
        setGroundAxis(groundAxisDeg)
        phaseFilterSin = 0f
        phaseFilterCos = 1f
        internalIsDetected = false
    }

    /** Imposta solo l'asse di terreno (per il bilanciamento manuale), senza toccare baseline o fase assoluta. */
    fun setGroundAxis(axisDeg: Float) {
        val rad = Math.toRadians(axisDeg.toDouble())
        groundAxisCos = cos(rad).toFloat()
        groundAxisSin = sin(rad).toFloat()
        groundAxisDeg = axisDeg
        hasGroundAxis = true
    }

    fun clearGroundAxis() {
        hasGroundAxis = false
        groundAxisDeg = 0f
        groundAxisCos = 1f
        groundAxisSin = 0f
    }

    fun reset() {
        smoothedI = 0f
        smoothedQ = 0f
        phaseFilterSin = 0f
        phaseFilterCos = 1f
        internalIsDetected = false
        detectionStartTimeMs = 0L
    }
}