package com.tetranova.prerex
import kotlin.math.*

class DepthAnalyzer {

    private val eventEntryThresholdFactor = 0.6f
    private val eventExitThresholdFactor = 0.4f
    private val iirAlpha = 0.30f
    private val maxDepth = 75f
    private val minSamplesForEvent = 5

    private var depthScale = 55f
    private var depthDecay = 0.22f

    private var isTrackingEvent = false
    private val eventSamples = mutableListOf<Float>()
    private var eventPeakAmplitude = 0f
    private var eventPeakIndex = 0
    private var eventPeakVdi = 0
    private var isDepthFinalized = false

    private var displayedDepth = 0f
    private var depthLive = 0f
    private var depthFinal = 0f

    private var lastEventFeatures: EventFeatures? = null

    data class EventFeatures(
        val peakAmplitude: Float,
        val fwhm: Float,
        val area: Float,
        val centroid: Float,
        val meanSlope: Float,
        val maxSlope: Float,
        val duration: Float,
        val sharpness: Float,
        val normalizedEnergy: Float,
        // 🔥 FIX: vdi campionato nell'istante di massima ampiezza dell'evento (massimo SNR),
        // NON l'ultimo campione istantaneo di coda (dove il segnale è già sceso vicino al
        // rumore e la discriminazione ferro/non-ferro diventa inaffidabile e può "sballare").
        val peakVdi: Int
    )

    data class DepthResult(
        val depth: Float,
        val isTracking: Boolean,
        val features: EventFeatures? = null
    )

    companion object FeatureExtractor {
        fun calculateFWHM(samples: List<Float>, peakIndex: Int, peakAmplitude: Float): Float {
            if (samples.size < 3 || peakAmplitude < 0.001f) return 1f

            val halfMax = peakAmplitude * 0.5f

            var leftIndex = 0
            for (i in peakIndex downTo 0) {
                if (samples[i] < halfMax) {
                    leftIndex = i
                    break
                }
            }

            var rightIndex = samples.size - 1
            for (i in peakIndex until samples.size) {
                if (samples[i] < halfMax) {
                    rightIndex = i
                    break
                }
            }

            val leftValue = samples[leftIndex]
            val leftNext = if (leftIndex > 0) samples[leftIndex - 1] else leftValue
            val leftInterp = if (leftValue < halfMax && leftNext > halfMax) {
                val diff = leftNext - leftValue
                if (diff > 0.001f) leftIndex - (halfMax - leftValue) / diff else leftIndex.toFloat()
            } else {
                leftIndex.toFloat()
            }

            val rightValue = samples[rightIndex]
            val rightPrev = if (rightIndex < samples.size - 1) samples[rightIndex + 1] else rightValue
            val rightInterp = if (rightValue < halfMax && rightPrev > halfMax) {
                val diff = rightPrev - rightValue
                if (diff > 0.001f) rightIndex + (halfMax - rightValue) / diff else rightIndex.toFloat()
            } else {
                rightIndex.toFloat()
            }

            return (rightInterp - leftInterp).coerceIn(0.5f, 30f)
        }

        fun calculateArea(samples: List<Float>): Float {
            if (samples.size < 2) return samples.sum()
            var area = 0f
            for (i in 1 until samples.size) {
                area += (samples[i - 1] + samples[i]) * 0.5f
            }
            return area.coerceIn(0.001f, 50f)
        }

        fun calculateCentroid(samples: List<Float>): Float {
            if (samples.isEmpty()) return 0.5f
            var sumValues = 0f
            var sumWeighted = 0f
            for (i in samples.indices) {
                val w = samples[i]
                sumValues += w
                sumWeighted += i * w
            }
            return if (sumValues > 0f) (sumWeighted / sumValues) / samples.size else 0.5f
        }

        fun calculateSlopeFeatures(samples: List<Float>): Pair<Float, Float> {
            if (samples.size < 3) return Pair(0.1f, 0.1f)
            var maxSlope = 0f
            var sumSlope = 0f
            var count = 0
            for (i in 1 until samples.size) {
                val slope = abs(samples[i] - samples[i - 1])
                if (slope > maxSlope) maxSlope = slope
                sumSlope += slope
                count++
            }
            val meanSlope = if (count > 0) sumSlope / count else 0.1f
            return Pair(maxSlope.coerceIn(0.001f, 1f), meanSlope.coerceIn(0.001f, 1f))
        }

        fun calculateAmplitudeStdDev(data: List<Pair<Float, Float>>): Float {
            if (data.size < 3) return 0.1f
            val amplitudes = data.map { it.first }
            val mean = amplitudes.average().toFloat()
            val variance = amplitudes.map { (it - mean) * (it - mean) }.average().toFloat()
            return sqrt(variance).coerceIn(0.001f, 1f)
        }

        fun calculatePhaseStdDev(phaseData: List<Float>): Float {
            if (phaseData.size < 3) return 5f
            val mean = phaseData.average().toFloat()
            val variance = phaseData.map { (it - mean) * (it - mean) }.average().toFloat()
            return sqrt(variance).coerceIn(0.1f, 30f)
        }
    }

    private fun extractEventFeatures(
        samples: List<Float>,
        peakAmplitude: Float,
        peakIndex: Int,
        peakVdi: Int
    ): EventFeatures {
        val fwhm = calculateFWHM(samples, peakIndex, peakAmplitude)
        val area = calculateArea(samples)
        val centroid = calculateCentroid(samples)
        val (maxSlope, meanSlope) = calculateSlopeFeatures(samples)
        val sharpness = if (area > 0f) peakAmplitude / area else 0f
        val normalizedEnergy = if (fwhm > 0f) area / fwhm else 0f

        return EventFeatures(
            peakAmplitude = peakAmplitude,
            fwhm = fwhm,
            area = area,
            centroid = centroid,
            meanSlope = meanSlope,
            maxSlope = maxSlope,
            duration = samples.size.toFloat(),
            sharpness = sharpness,
            normalizedEnergy = normalizedEnergy,
            peakVdi = peakVdi
        )
    }

    private fun calculateDepth(
        baseDepth: Float,
        stability: Float,
        phaseReliability: Float,
        confidence: Float,
        vdi: Int,
        features: EventFeatures?
    ): Float {
        val widthFactor = if (features != null) {
            1f + 0.25f * tanh((4f - features.fwhm) / 2f)
        } else 1f

        val areaFactor = if (features != null) {
            val normArea = features.area / features.duration
            1f / (1f + 0.20f * normArea)
        } else 1f

        val symmetryFactor = if (features != null) {
            0.90f + 0.10f * (1f - abs(features.centroid - 0.5f) * 2f)
        } else 1f

        val slopeFactor = if (features != null) {
            1f / (1f + features.meanSlope * 1.8f)
        } else 1f

        val sharpnessFactor = if (features != null) {
            1f + 0.15f * tanh((0.5f - features.sharpness) / 0.3f)
        } else 1f

        val energyFactor = if (features != null) {
            val normEnergy = features.normalizedEnergy
            1f + 0.15f * tanh((0.3f - normEnergy) / 0.2f)
        } else 1f

        val confidenceWeight = 0.85f + 0.15f * (confidence / 100f)
        val ferrousWeight = if (vdi < 0) 0.90f else 1f

        val compositeFactor = 0.50f +
                0.12f * stability +
                0.10f * phaseReliability +
                0.08f * widthFactor +
                0.08f * areaFactor +
                0.05f * symmetryFactor +
                0.05f * slopeFactor +
                0.04f * sharpnessFactor +
                0.06f * energyFactor +
                0.10f * confidenceWeight +
                0.05f * ferrousWeight

        var depth = baseDepth * compositeFactor.coerceIn(0.4f, 1.6f)
        return depth.coerceIn(0f, maxDepth)
    }

    fun setCalibratedParameters(scale: Float, decay: Float) {
        depthScale = scale
        depthDecay = decay
    }

    fun processSample(
        vectorDistance: Float,
        confidence: Float,
        vdi: Int,
        threshold: Float,
        historicalData: List<Pair<Float, Float>>,
        phaseHistogram: List<Float>
    ): DepthResult {
        val isDetected = vectorDistance > threshold
        val entryThreshold = threshold * eventEntryThresholdFactor
        val exitThreshold = threshold * eventExitThresholdFactor

        // Reset se non rilevato
        if (!isDetected && !isTrackingEvent) {
            if (displayedDepth > 0.1f) displayedDepth = 0f
            return DepthResult(
                depth = 0f,
                isTracking = false,
                features = null
            )
        }

        // Inizio evento
        if (!isTrackingEvent && vectorDistance > entryThreshold) {
            isTrackingEvent = true
            eventSamples.clear()
            eventPeakAmplitude = 0f
            eventPeakIndex = 0
            eventPeakVdi = 0
            isDepthFinalized = false
        }

        if (isTrackingEvent) {
            eventSamples.add(vectorDistance)

            if (vectorDistance > eventPeakAmplitude) {
                eventPeakAmplitude = vectorDistance
                eventPeakIndex = eventSamples.size - 1
                eventPeakVdi = vdi
            }

            // historicalData/phaseHistogram non cambiano nel corso di questa chiamata:
            // calcolati una volta sola e riusati sia per la stima live sia (se l'evento
            // finisce in questo stesso campione) per quella finale.
            val stability = (1f - calculateAmplitudeStdDev(historicalData) * 2f).coerceIn(0.3f, 1f)
            val phaseReliability = (1f - calculatePhaseStdDev(phaseHistogram) / 25f).coerceIn(0.3f, 1f)

            // DEPTH LIVE (solo diagnostico)
            val signalRatio = (vectorDistance / threshold).coerceIn(0.5f, 15f)
            val baseDepth = depthScale * exp(-depthDecay * signalRatio)

            val liveComposite = 0.60f +
                    0.15f * stability +
                    0.15f * phaseReliability +
                    0.10f * (0.85f + 0.15f * (confidence / 100f))

            depthLive = baseDepth * liveComposite.coerceIn(0.4f, 1.6f)
            if (displayedDepth == 0f || !isDepthFinalized) displayedDepth = depthLive

            // ═══════════════════════════════════════════════════════════
            // 🔥 FINE EVENTO: usa exitThreshold (isteresi reale)
            // ═══════════════════════════════════════════════════════════
            if (vectorDistance < exitThreshold) {
                isTrackingEvent = false

                if (eventSamples.size >= minSamplesForEvent) {
                    val features = extractEventFeatures(
                        samples = eventSamples,
                        peakAmplitude = eventPeakAmplitude,
                        peakIndex = eventPeakIndex,
                        peakVdi = eventPeakVdi
                    )
                    lastEventFeatures = features
                    isDepthFinalized = true

                    val finalSignalRatio = (eventPeakAmplitude / threshold).coerceIn(0.5f, 15f)
                    val finalBaseDepth = depthScale * exp(-depthDecay * finalSignalRatio)

                    depthFinal = calculateDepth(
                        baseDepth = finalBaseDepth,
                        stability = stability,
                        phaseReliability = phaseReliability,
                        confidence = confidence,
                        vdi = vdi,
                        features = features
                    )

                    if (displayedDepth == 0f) {
                        displayedDepth = depthFinal
                    } else {
                        displayedDepth = displayedDepth * (1f - iirAlpha) + depthFinal * iirAlpha
                    }

                } else {
                    displayedDepth = 0f
                }
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 🔥 FIX BUG #1: il flag è un impulso, non un livello
        // features disponibile SOLO per questa chiamata
        // ═══════════════════════════════════════════════════════════
        val featuresForThisCall = if (isDepthFinalized) lastEventFeatures else null

        val result = DepthResult(
            depth = displayedDepth,
            isTracking = isTrackingEvent,
            features = featuresForThisCall
        )

        // ═══════════════════════════════════════════════════════════
        // 🔥 CONSUMA IL FLAG: disponibile solo per QUESTA chiamata
        // ═══════════════════════════════════════════════════════════
        isDepthFinalized = false

        return result
    }

    fun reset() {
        isTrackingEvent = false
        eventSamples.clear()
        eventPeakAmplitude = 0f
        eventPeakIndex = 0
        eventPeakVdi = 0
        isDepthFinalized = false
        displayedDepth = 0f
        depthLive = 0f
        depthFinal = 0f
        lastEventFeatures = null
    }
}