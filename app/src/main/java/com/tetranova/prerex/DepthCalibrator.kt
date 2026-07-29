package com.tetranova.prerex

import android.content.Context
import kotlin.math.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DepthCalibrator {

    enum class MetalType {
        FERRO, RAME, ALLUMINIO, STAGNO, ARGENTO, ORO, ALTRO;

        fun getEmoji(): String = when (this) {
            FERRO -> "🔴"
            RAME -> "🟠"
            ALLUMINIO -> "⚪"
            STAGNO -> "🔘"
            ARGENTO -> "⚪"
            ORO -> "🟡"
            ALTRO -> "🟣"
        }

        fun getColor(): Int = when (this) {
            FERRO -> 0xFFFF6B6B.toInt()
            RAME -> 0xFFFFA726.toInt()
            ALLUMINIO -> 0xFFB0BEC5.toInt()
            STAGNO -> 0xFF90A4AE.toInt()
            ARGENTO -> 0xFFCFD8DC.toInt()
            ORO -> 0xFFFFD740.toInt()
            ALTRO -> 0xFFCE93D8.toInt()
        }

        fun getVdiRange(): IntRange = when (this) {
            FERRO -> -99..-1
            RAME -> 40..70
            ALLUMINIO -> 10..35
            STAGNO -> 15..40
            ARGENTO -> 50..80
            ORO -> 45..65
            ALTRO -> -99..99
        }

        companion object {
            fun fromString(value: String): MetalType {
                return entries.find { it.name.equals(value, ignoreCase = true) } ?: ALTRO
            }
        }
    }

    data class TargetFingerprint(
        val id: String,
        val name: String,
        val metalType: MetalType,
        val depthCm: Float,
        val signalRatio: Float,
        val fwhm: Float,
        val area: Float,
        val centroid: Float,
        val meanSlope: Float,
        val maxSlope: Float,
        val sharpness: Float,
        val normalizedEnergy: Float,
        val vdi: Int,
        val timestamp: Long,
        val notes: String = ""
    )

    data class CalibrationResult(
        val success: Boolean,
        val message: String
    )

    private val mutex = Mutex()
    private val fingerprints = mutableListOf<TargetFingerprint>()
    private var isCalibrating = false
    private var currentCalibrationName: String = ""
    private var currentCalibrationMetalType: MetalType = MetalType.ALTRO
    private var currentCalibrationDepth: Float = 0f
    private val calibrationSamples = mutableListOf<CalibrationSample>()

    private var fitQuality = 0f
    private var isCalibrated = false

    private data class CalibrationSample(
        val signalRatio: Float,
        val fwhm: Float,
        val area: Float,
        val centroid: Float,
        val meanSlope: Float,
        val maxSlope: Float,
        val sharpness: Float,
        val normalizedEnergy: Float,
        val vdi: Int
    )

    companion object {
        private const val FILENAME = "depth_fingerprints.json"
        private const val MIN_FINGERPRINTS_FOR_FIT = 3
        private const val MIN_FIT_QUALITY = 0.3f
        private const val EPSILON = 1e-6f
        private const val SAMPLES_NEEDED = 10
    }

    fun saveToFile(context: Context): Boolean {
        return try {
            val jsonArray = JSONArray()
            for (fp in fingerprints) {
                val obj = JSONObject()
                obj.put("id", fp.id)
                obj.put("name", fp.name)
                obj.put("metalType", fp.metalType.name)
                obj.put("depthCm", fp.depthCm.toDouble())
                obj.put("signalRatio", fp.signalRatio.toDouble())
                obj.put("fwhm", fp.fwhm.toDouble())
                obj.put("area", fp.area.toDouble())
                obj.put("centroid", fp.centroid.toDouble())
                obj.put("meanSlope", fp.meanSlope.toDouble())
                obj.put("maxSlope", fp.maxSlope.toDouble())
                obj.put("sharpness", fp.sharpness.toDouble())
                obj.put("normalizedEnergy", fp.normalizedEnergy.toDouble())
                obj.put("vdi", fp.vdi)
                obj.put("timestamp", fp.timestamp)
                obj.put("notes", fp.notes)
                jsonArray.put(obj)
            }
            val file = File(context.filesDir, FILENAME)
            file.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadFromFile(context: Context): Int {
        return try {
            val file = File(context.filesDir, FILENAME)
            if (!file.exists()) return 0

            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val loaded = mutableListOf<TargetFingerprint>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val fp = TargetFingerprint(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    metalType = MetalType.fromString(obj.getString("metalType")),
                    depthCm = obj.getDouble("depthCm").toFloat(),
                    signalRatio = obj.getDouble("signalRatio").toFloat(),
                    fwhm = obj.getDouble("fwhm").toFloat(),
                    area = obj.getDouble("area").toFloat(),
                    centroid = obj.getDouble("centroid").toFloat(),
                    meanSlope = obj.getDouble("meanSlope").toFloat(),
                    maxSlope = obj.getDouble("maxSlope").toFloat(),
                    sharpness = obj.getDouble("sharpness").toFloat(),
                    normalizedEnergy = obj.getDouble("normalizedEnergy").toFloat(),
                    vdi = obj.getInt("vdi"),
                    timestamp = obj.getLong("timestamp"),
                    notes = obj.optString("notes", "")
                )
                loaded.add(fp)
            }

            fingerprints.clear()
            fingerprints.addAll(loaded)
            updateCalibratedParameters()
            loaded.size
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    suspend fun startCalibration(
        name: String,
        metalType: MetalType,
        depthCm: Float
    ): String = mutex.withLock {
        if (isCalibrating) {
            return "⚠️ Calibrazione già in corso"
        }

        if (name.isBlank()) {
            return "⚠️ Inserisci una descrizione"
        }

        isCalibrating = true
        currentCalibrationName = name
        currentCalibrationMetalType = metalType
        currentCalibrationDepth = depthCm
        calibrationSamples.clear()

        val existing = fingerprints.find { it.name.equals(name, ignoreCase = true) }
        val msg = if (existing != null) {
            "🔄 Sovrascrittura: $name (${existing.metalType.getEmoji()} ${existing.metalType})"
        } else {
            "📏 Nuova calibrazione: ${metalType.getEmoji()} $name a ${depthCm}cm"
        }

        return "$msg\n🔄 Passa la bobina $SAMPLES_NEEDED volte sul target (0/${SAMPLES_NEEDED})"
    }

    private fun similarity(a: Float, b: Float): Float = 1f - abs(a - b) / (a + b + 0.001f)

    suspend fun predictTarget(
        eventFeatures: DepthAnalyzer.EventFeatures,
        vdi: Int,
        threshold: Float
    ): TargetPrediction? = mutex.withLock {
        if (fingerprints.isEmpty()) return null

        val signalRatio = eventFeatures.peakAmplitude / threshold.coerceAtLeast(0.001f)

        // 🔥 FIX: la discriminazione ferro/non-ferro e' un vincolo A PRIORI, non un semplice
        // "bonus" di similarita'. Un chiodo (ferroso) non puo' MAI essere confuso con una
        // moneta (non ferrosa): filtriamo le impronte candidate per categoria PRIMA di
        // valutare la somiglianza di forma, cosi' un match di forma forte su un'impronta
        // della categoria sbagliata non puo' piu' "vincere".
        val isTargetFerrous = vdi < 0
        val candidates = fingerprints.filter { fp -> (fp.metalType == MetalType.FERRO) == isTargetFerrous }
        if (candidates.isEmpty()) return null

        var bestMatch: TargetFingerprint? = null
        var bestSimilarity = 0f

        for (fp in candidates) {
            val signalSim = similarity(fp.signalRatio, signalRatio)
            val fwhmSim = similarity(fp.fwhm, eventFeatures.fwhm)
            val areaSim = similarity(fp.area, eventFeatures.area)
            val centroidSim = similarity(fp.centroid, eventFeatures.centroid)
            val sharpnessSim = similarity(fp.sharpness, eventFeatures.sharpness)
            val energySim = similarity(fp.normalizedEnergy, eventFeatures.normalizedEnergy)

            val similarity = signalSim * 0.25f +
                    fwhmSim * 0.15f +
                    areaSim * 0.15f +
                    centroidSim * 0.10f +
                    sharpnessSim * 0.15f +
                    energySim * 0.20f

            var bonus = 0f
            if (abs(fp.vdi - vdi) < 20) bonus += 0.1f
            val vdiRange = fp.metalType.getVdiRange()
            if (vdi in vdiRange) bonus += 0.1f

            val totalSimilarity = similarity + bonus

            if (totalSimilarity > bestSimilarity) {
                bestSimilarity = totalSimilarity
                bestMatch = fp
            }
        }

        if (bestMatch == null || bestSimilarity < 0.3f) return null

        return TargetPrediction(
            fingerprint = bestMatch,
            similarity = (bestSimilarity * 100f).coerceIn(0f, 100f)
        )
    }

    data class TargetPrediction(
        val fingerprint: TargetFingerprint,
        val similarity: Float
    )

    suspend fun addCalibrationSample(
        eventFeatures: DepthAnalyzer.EventFeatures,
        vdi: Int,
        threshold: Float,
        context: Context? = null
    ): CalibrationResult? = mutex.withLock {
        if (!isCalibrating) return null

        val sample = CalibrationSample(
            signalRatio = eventFeatures.peakAmplitude / threshold.coerceAtLeast(0.001f),
            fwhm = eventFeatures.fwhm,
            area = eventFeatures.area,
            centroid = eventFeatures.centroid,
            meanSlope = eventFeatures.meanSlope,
            maxSlope = eventFeatures.maxSlope,
            sharpness = eventFeatures.sharpness,
            normalizedEnergy = eventFeatures.normalizedEnergy,
            vdi = vdi
        )

        calibrationSamples.add(sample)

        // FIX BUG #1/#2: completa la calibrazione UNA sola volta, qui,
        // passando il context cosi' saveToFile() viene davvero chiamato.
        // Prima veniva chiamato completeCalibrationInternal() senza context
        // (niente salvataggio), e poi il ViewModel richiamava completeCalibration()
        // una seconda volta trovando isCalibrating gia' false -> "Nessuna calibrazione in corso".
        return if (calibrationSamples.size >= SAMPLES_NEEDED) {
            completeCalibrationInternal(context)
        } else {
            null
        }
    }

    suspend fun completeCalibration(context: Context? = null): CalibrationResult = mutex.withLock {
        return completeCalibrationInternal(context)
    }

    private fun completeCalibrationInternal(context: Context? = null): CalibrationResult {
        if (!isCalibrating) {
            return CalibrationResult(false, "⚠️ Nessuna calibrazione in corso")
        }

        if (calibrationSamples.isEmpty()) {
            isCalibrating = false
            return CalibrationResult(false, "❌ Nessun campione raccolto")
        }

        val avgSignalRatio = calibrationSamples.map { it.signalRatio }.average().toFloat()
        val avgFwhm = calibrationSamples.map { it.fwhm }.average().toFloat()
        val avgArea = calibrationSamples.map { it.area }.average().toFloat()
        val avgCentroid = calibrationSamples.map { it.centroid }.average().toFloat()
        val avgSharpness = calibrationSamples.map { it.sharpness }.average().toFloat()
        val avgEnergy = calibrationSamples.map { it.normalizedEnergy }.average().toFloat()
        val avgMeanSlope = calibrationSamples.map { it.meanSlope }.average().toFloat()
        val avgMaxSlope = calibrationSamples.map { it.maxSlope }.average().toFloat()
        val avgVdi = calibrationSamples.map { it.vdi }.average().toInt()

        val now = System.currentTimeMillis()
        val newFingerprint = TargetFingerprint(
            id = "${currentCalibrationName}_${now}",
            name = currentCalibrationName,
            metalType = currentCalibrationMetalType,
            depthCm = currentCalibrationDepth,
            signalRatio = avgSignalRatio,
            fwhm = avgFwhm,
            area = avgArea,
            centroid = avgCentroid,
            meanSlope = avgMeanSlope,
            maxSlope = avgMaxSlope,
            sharpness = avgSharpness,
            normalizedEnergy = avgEnergy,
            vdi = avgVdi,
            timestamp = now,
            notes = ""
        )

        val existingIndex = fingerprints.indexOfFirst {
            it.name.equals(currentCalibrationName, ignoreCase = true)
        }
        if (existingIndex >= 0) {
            fingerprints.removeAt(existingIndex)
        }
        fingerprints.add(newFingerprint)

        isCalibrating = false
        val name = currentCalibrationName
        val depth = currentCalibrationDepth
        val metalType = currentCalibrationMetalType
        currentCalibrationName = ""
        currentCalibrationMetalType = MetalType.ALTRO
        currentCalibrationDepth = 0f
        calibrationSamples.clear()

        updateCalibratedParameters()
        context?.let { saveToFile(it) }

        val msg = if (existingIndex >= 0) {
            "✅ Aggiornato: ${metalType.getEmoji()} $name a ${depth}cm"
        } else {
            "✅ Nuova impronta: ${metalType.getEmoji()} $name a ${depth}cm"
        }

        return CalibrationResult(
            success = true,
            message = "$msg\n📊 Impronte totali: ${fingerprints.size}"
        )
    }

    suspend fun cancelCalibration() = mutex.withLock {
        isCalibrating = false
        currentCalibrationName = ""
        currentCalibrationMetalType = MetalType.ALTRO
        currentCalibrationDepth = 0f
        calibrationSamples.clear()
    }

    suspend fun clearFingerprints(context: Context? = null) = mutex.withLock {
        fingerprints.clear()
        isCalibrated = false
        fitQuality = 0f
        context?.let { saveToFile(it) }
    }

    private fun updateCalibratedParameters() {
        if (fingerprints.size < MIN_FINGERPRINTS_FOR_FIT) {
            isCalibrated = false
            return
        }

        val x = fingerprints.map { it.signalRatio }.toFloatArray()
        val y = fingerprints.map { it.depthCm }.toFloatArray()

        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumX2 = 0f
        val n = x.size.toFloat()

        for (i in x.indices) {
            sumX += x[i]
            val safeY = ln(y[i].coerceAtLeast(1.0f))
            sumY += safeY
            sumXY += x[i] * safeY
            sumX2 += x[i] * x[i]
        }

        val denominator = n * sumX2 - sumX * sumX

        if (abs(denominator) < EPSILON) {
            isCalibrated = false
            fitQuality = 0f
            return
        }

        val slope = (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n

        if (slope > 0) {
            isCalibrated = false
            fitQuality = 0f
            return
        }

        val yMean = sumY / n
        var ssTot = 0f
        var ssRes = 0f
        for (i in x.indices) {
            val yLog = ln(y[i].coerceAtLeast(1.0f))
            val predicted = intercept + slope * x[i]
            ssTot += (yLog - yMean) * (yLog - yMean)
            ssRes += (yLog - predicted) * (yLog - predicted)
        }

        if (ssTot < EPSILON) {
            isCalibrated = false
            fitQuality = 0f
            return
        }

        fitQuality = 1f - (ssRes / ssTot)
        isCalibrated = fitQuality >= MIN_FIT_QUALITY
    }

    fun getFingerprints(): List<TargetFingerprint> = fingerprints.toList()

    fun getMetalTypeName(type: MetalType): String =
        type.name.lowercase().replaceFirstChar { it.uppercase() }

    fun isCalibrated(): Boolean = isCalibrated
    fun isCalibrating(): Boolean = isCalibrating
    fun getFitQuality(): Float = fitQuality
    fun getFingerprintCount(): Int = fingerprints.size

    fun getCalibrationStatus(): String {
        if (!isCalibrating) return "Idle (${fingerprints.size} impronte)"
        return "Calibrazione ${currentCalibrationName} " +
                "(${currentCalibrationMetalType.getEmoji()} ${getMetalTypeName(currentCalibrationMetalType)}) " +
                "a ${currentCalibrationDepth}cm (${calibrationSamples.size}/$SAMPLES_NEEDED)"
    }
}