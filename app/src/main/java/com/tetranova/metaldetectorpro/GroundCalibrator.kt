package com.tetranova.metaldetectorpro
import kotlin.math.*

/**
Rappresenta l'esito finale dell'elaborazione di calibrazione del terreno.
 */
data class GroundCalibrationReport(
    val success: Boolean,
    val groundAngleDeg: Float,
    val centerPhaseDeg: Float,
    val groundCenter: IQVector,
    val pcaQuality: Float,
    val tangentialRMS: Float,
    val tangentialSigma: Float,  // ✅ AGGIUNTO: ora trasferisce la sigma al ViewModel
    val message: String
)

class GroundCalibrator {
    private val samples = mutableListOf<IQVector>()
    var minSamplesRequired = 45

    fun startAccumulation() {
        samples.clear()
    }

    fun addSample(sample: IQVector) {
        samples.add(sample)
    }

    fun computeCalibration(): GroundCalibrationReport {
        val n = samples.size
        if (n < minSamplesRequired) {
            return GroundCalibrationReport(false, 0f, 0f, IQVector.ZERO, 0f, 0f, 0f, "Campioni insufficienti ($n/$minSamplesRequired).")
        }

        // 1. Calcolo del Centroide
        var sumI = 0.0
        var sumQ = 0.0
        for (s in samples) { sumI += s.i; sumQ += s.q }
        val center = IQVector((sumI / n).toFloat(), (sumQ / n).toFloat())

        // 2. Calcolo Matrice di Covarianza
        var varI = 0f
        var varQ = 0f
        var covIQ = 0f
        for (s in samples) {
            val dI = s.i - center.i
            val dQ = s.q - center.q
            varI += dI * dI
            varQ += dQ * dQ
            covIQ += dI * dQ
        }

        // Analisi PCA
        val tr = varI + varQ
        val disc = sqrt((varI - varQ) * (varI - varQ) + 4f * covIQ * covIQ)
        val l1 = (tr + disc) / 2f
        val l2 = (tr - disc) / 2f
        val pcaQuality = if (l2 > 1e-9f) l1 / l2 else 100f

        val angleRad = atan2(2f * covIQ, (varI - varQ) + disc)
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        // 3. Residui Tangenziali
        val gx = cos(angleRad)
        val gy = sin(angleRad)
        var sumSq = 0f
        val tangValues = ArrayList<Float>(n)
        for (s in samples) {
            val compI = s.i - center.i
            val compQ = s.q - center.q
            // Proiezione ortogonale sulla retta
            val tangential = compI * (-gy) + compQ * gx
            tangValues.add(abs(tangential))
            sumSq += tangential * tangential
        }

        val tangentialRMS = sqrt(sumSq / n)
        val meanTang = tangValues.average().toFloat()
        var varianceTang = 0f
        for (v in tangValues) { varianceTang += (v - meanTang) * (v - meanTang) }
        val tangentialSigma = sqrt(varianceTang / n)  // ✅ Calcolato correttamente

        // 4. Validazione basata sulla logica originale del VectorProcessor
        val success = pcaQuality >= 3.0f && tangentialSigma <= tangentialRMS * 0.8f
        val message = if (success) {
            "✅ Calibrazione riuscita! Angolo = ${"%.1f".format(angleDeg)}° (PCA = ${"%.1f".format(pcaQuality)})"
        } else {
            if (pcaQuality < 3.0f) {
                "❌ Qualità PCA troppo bassa (${"%.1f".format(pcaQuality)}). Rilevato target o disturbo."
            } else {
                "❌ Pompaggio irregolare (Sigma: %.3f > RMS*0.8: %.3f).".format(tangentialSigma, tangentialRMS * 0.8f)
            }
        }

        val centerPhaseDeg = Math.toDegrees(atan2(center.q, center.i).toDouble()).toFloat()
        return GroundCalibrationReport(
            success = success,
            groundAngleDeg = angleDeg,
            centerPhaseDeg = centerPhaseDeg,
            groundCenter = center,
            pcaQuality = pcaQuality,
            tangentialRMS = tangentialRMS,
            tangentialSigma = tangentialSigma,  // ✅ Trasferito al report
            message = message
        )
    }
}