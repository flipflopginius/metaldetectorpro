package com.tetranova.prerex

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import kotlin.math.abs

class SerialParser(
    private val telemetryChannel: Channel<TelemetryData>,
    private val paramsFlow: MutableStateFlow<DeviceParams>,
    private val consoleChannel: Channel<String>,
    private val onPong: () -> Unit = {}
) {
    private val BATTERY_AVG_WINDOW = 4
    private val batteryWindow = ArrayDeque<Float>(BATTERY_AVG_WINDOW)

    @Volatile private var frequencyOverridePending = false
    @Volatile private var frequencyOverrideValue = 0f
    @Volatile private var frequencyOverrideUntilMs = 0L

    private var lastBatchCounter: Long = -1L

    // Statistiche per il monitoraggio della stabilità
    private var droppedBatches = 0L

    // Garantisce che i timestamp siano strettamente crescenti ed evita sovrapposizioni
    private var lastEmittedTimestampMs: Long = 0L

    // Despike: mediana a 3 campioni.
    private var rawPrev1I: Float? = null   // delta n-1
    private var rawPrev1Q: Float? = null   // phase n-1
    private var rawPrev2I: Float? = null   // delta n-2
    private var rawPrev2Q: Float? = null   // phase n-2

    private fun median3(a: Float, b: Float, c: Float): Float =
        maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    fun notifyFrequencyChange(newFreq: Float) {
        frequencyOverrideValue = newFreq
        frequencyOverridePending = true
        frequencyOverrideUntilMs = System.currentTimeMillis() + 3000L
    }

    fun parse(line: String) {
        val cleanLine = line.trimEnd('\r').trim()
        try {
            when {
                cleanLine.startsWith("B:") -> {
                    val payload = cleanLine.substring(2).trim()
                    val parts = payload.split(';')

                    if (parts.isEmpty()) return

                    val batchCounter = parts[0].toLongOrNull()
                    if (batchCounter == null) {
                        Log.w("Parser", "⚠️ Contatore batch non valido: '${parts[0]}'")
                        return
                    }

                    // FIX 1 & 3: Gestione corretta dei batch persi e reset del filtro mediana
                    if (lastBatchCounter >= 0 && batchCounter != lastBatchCounter + 1) {
                        val gap = (batchCounter - lastBatchCounter - 1).toInt()
                        if (gap > 0) {
                            droppedBatches += gap

                            // RESET FONDAMENTALE: Se abbiamo perso campioni, interrompiamo
                            // la continuità del filtro mediana per evitare valori distorti!
                            rawPrev1I = null
                            rawPrev1Q = null
                            rawPrev2I = null
                            rawPrev2Q = null

                            if (gap > 1 || droppedBatches % 10 == 0L) {
                                consoleChannel.trySend("⚠️ Batch persi: $gap (tot persi: $droppedBatches)")
                                Log.w("Parser", "⚠️ Batch persi: atteso ${lastBatchCounter + 1}, ricevuto $batchCounter (gap: $gap)")
                            }
                        }
                    }
                    lastBatchCounter = batchCounter

                    val batchReceiveTimeMs = System.currentTimeMillis()

                    if (parts.size <= 1) return

                    // Processa i campioni senza usare drop(1) per ridurre le allocazioni
                    val parsedDeltas = FloatArray(parts.size - 1)
                    val parsedPhases = FloatArray(parts.size - 1)
                    var validCount = 0

                    for (i in 1 until parts.size) {
                        val cleanSample = parts[i].trim()
                        if (cleanSample.isEmpty()) continue

                        val commaIdx = cleanSample.indexOf(',')
                        if (commaIdx <= 0 || commaIdx == cleanSample.lastIndex) continue

                        val delta = cleanSample.substring(0, commaIdx).trim().toFloatOrNull()
                        val rawPhase = cleanSample.substring(commaIdx + 1).trim().toFloatOrNull()

                        if (delta == null || rawPhase == null) continue
                        if (delta.isNaN() || rawPhase.isNaN()) continue
                        if (rawPhase !in -180f..180f) continue
                        if (delta !in 0f..2000f) continue

                        // Filter Mediana 3 senza allocazioni di oggetti Pair
                        val p1I = rawPrev1I
                        val p1Q = rawPrev1Q
                        val p2I = rawPrev2I
                        val p2Q = rawPrev2Q

                        val outDelta: Float
                        val outPhase: Float

                        if (p1I != null && p2I != null && p1Q != null && p2Q != null) {
                            outDelta = median3(p2I, p1I, delta)
                            outPhase = median3(p2Q, p1Q, rawPhase)
                        } else {
                            outDelta = delta
                            outPhase = rawPhase
                        }

                        rawPrev2I = rawPrev1I
                        rawPrev2Q = rawPrev1Q
                        rawPrev1I = delta
                        rawPrev1Q = rawPhase

                        parsedDeltas[validCount] = outDelta
                        parsedPhases[validCount] = outPhase
                        validCount++
                    }

                    if (validCount == 0) return

                    // FIX 2: Calcolo Timestamp Monotonico Garantito
                    val sampleIntervalMs = 2L // 500 Hz
                    var currentTimestampMs = batchReceiveTimeMs - (validCount - 1) * sampleIntervalMs

                    // Se il buffer seriale accumula ritardo, garantiamo che il timestamp non torni mai indietro
                    if (currentTimestampMs <= lastEmittedTimestampMs) {
                        currentTimestampMs = lastEmittedTimestampMs + sampleIntervalMs
                    }

                    for (i in 0 until validCount) {
                        val sampleTime = currentTimestampMs + (i * sampleIntervalMs)

                        telemetryChannel.trySend(TelemetryData(
                            delta = parsedDeltas[i],
                            phase = parsedPhases[i],
                            timestampMs = sampleTime
                        ))

                        lastEmittedTimestampMs = sampleTime
                    }
                }

                cleanLine == "PONG" -> onPong()

                cleanLine == "MSG:MODE_USB" -> {
                    reset()
                    paramsFlow.value = paramsFlow.value.copy(transportMode = "USB")
                    consoleChannel.trySend("🟢 Modalità USB attiva")
                }

                cleanLine == "STATUS_ACK" -> {
                    consoleChannel.trySend("✅ Status ricevuto")
                    Log.d("Parser", "📊 Status ACK ricevuto")
                }

                cleanLine.startsWith("INF:") -> {
                    val parts = cleanLine.substring(4).split(",")
                    if (parts.size >= 2) {
                        val batteryRaw = parts[0].toFloatOrNull() ?: 0f
                        val freqFromEsp = parts[1].toFloatOrNull() ?: 0f
                        val batteryCorrected = batteryRaw * 4.39f

                        if (batteryWindow.size >= BATTERY_AVG_WINDOW) batteryWindow.removeFirst()
                        batteryWindow.addLast(batteryCorrected)

                        val batteryAvg = batteryWindow.sum() / batteryWindow.size
                        val isReady = batteryWindow.size >= BATTERY_AVG_WINDOW

                        val now = System.currentTimeMillis()
                        val freq = if (frequencyOverridePending && now < frequencyOverrideUntilMs) {
                            if (abs(freqFromEsp - frequencyOverrideValue) < 50f) {
                                frequencyOverridePending = false
                                freqFromEsp
                            } else {
                                frequencyOverrideValue
                            }
                        } else {
                            frequencyOverridePending = false
                            freqFromEsp
                        }

                        paramsFlow.value = paramsFlow.value.copy(
                            battery = batteryAvg,
                            frequency = freq,
                            batteryReady = isReady
                        )
                        Log.d("Parser", "🔋 Battery: $batteryAvg V, Freq: $freq Hz")
                    }
                }

                cleanLine.startsWith("MSG:") -> {
                    val msg = cleanLine.substring(4)
                    if (!msg.contains("Soglie:") && !msg.contains("groundRMS")) {
                        consoleChannel.trySend("ℹ️ $msg")
                    }
                    Log.d("Parser", "💬 MSG: $msg")
                }

                else -> {
                    Log.d("Parser", "⚠️ Riga non riconosciuta: '$cleanLine'")
                }
            }
        } catch (e: Exception) {
            Log.e("Parser", "Parse error: $cleanLine", e)
        }
    }

    fun reset() {
        batteryWindow.clear()
        frequencyOverridePending = false
        lastBatchCounter = -1L
        droppedBatches = 0L
        lastEmittedTimestampMs = 0L
        rawPrev1I = null
        rawPrev1Q = null
        rawPrev2I = null
        rawPrev2Q = null
        Log.d("Parser", "Parser resettato")
    }
}