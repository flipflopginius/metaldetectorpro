package com.tetranova.metaldetectorpro

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import kotlin.math.abs

class SerialParser(
    private val telemetryFlow: MutableSharedFlow<TelemetryData>,
    private val paramsFlow: MutableStateFlow<DeviceParams>,
    private val consoleFlow: MutableSharedFlow<String>,
    private val onPong: () -> Unit = {}
) {
    private val BATTERY_AVG_WINDOW = 4
    private val batteryWindow = ArrayDeque<Float>(BATTERY_AVG_WINDOW)

    @Volatile private var frequencyOverridePending = false
    @Volatile private var frequencyOverrideValue = 0f
    @Volatile private var frequencyOverrideUntilMs = 0L

    private var lastBatchCounter: Long = -1L

    // ★ Statistiche per il monitoraggio della stabilità
    private var totalSamples = 0L
    private var droppedBatches = 0L

    fun notifyFrequencyChange(newFreq: Float) {
        frequencyOverrideValue = newFreq
        frequencyOverridePending = true
        frequencyOverrideUntilMs = System.currentTimeMillis() + 3000L
    }

    fun parse(line: String) {
        val cleanLine = line.trimEnd('\r').trim()
        Log.d("Parser", "📥 RAW: '$cleanLine'")
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

                    // ★ Gestione batch persi con logica migliorata
                    if (lastBatchCounter >= 0 && batchCounter != lastBatchCounter + 1) {
                        val gap = (batchCounter - lastBatchCounter).toInt()
                        droppedBatches++
                        // Mostra solo i gap significativi (per non inondare)
                        if (gap > 1 || droppedBatches % 10 == 0L) {
                            consoleFlow.tryEmit("⚠️ Batch persi: gap $gap (tot: $droppedBatches)")
                            Log.w("Parser", "⚠️ Batch persi: atteso ${lastBatchCounter + 1}, ricevuto $batchCounter (gap: $gap)")
                        }
                    }
                    lastBatchCounter = batchCounter

                    val batchReceiveTimeMs = System.currentTimeMillis()
                    val samples = parts.drop(1)

                    // ★ Elaborazione batch più efficiente
                    if (samples.isEmpty()) return

                    // ★ Estrai tutti i dati in un'unica passata
                    val parsedSamples = mutableListOf<Pair<Float, Float>>()
                    for (sample in samples) {
                        val cleanSample = sample.trim()
                        if (cleanSample.isEmpty()) continue

                        val commaIdx = cleanSample.indexOf(',')
                        if (commaIdx <= 0 || commaIdx == cleanSample.lastIndex) continue

                        val deltaStr = cleanSample.substring(0, commaIdx).trim()
                        val phaseStr = cleanSample.substring(commaIdx + 1).trim()

                        val delta = deltaStr.toFloatOrNull()
                        val rawPhase = phaseStr.toFloatOrNull()

                        if (delta == null || rawPhase == null) continue
                        if (delta.isNaN() || rawPhase.isNaN()) continue
                        if (rawPhase !in -180f..180f) continue

                        parsedSamples.add(delta to rawPhase)
                    }

                    if (parsedSamples.isEmpty()) return

                    // ★ Timestamp più preciso: campioni equidistanti
                    // A 500 Hz → 2 ms per campione
                    val sampleIntervalMs = 2L
                    val batchSize = parsedSamples.size

                    // ★ Calcola offset per centrare il timestamp
                    // Usa il tempo di ricezione del batch e distribuisci i campioni
                    val firstSampleTime = batchReceiveTimeMs - (batchSize - 1) * sampleIntervalMs

                    for ((index, sample) in parsedSamples.withIndex()) {
                        val (delta, phase) = sample
                        val sampleTimestampMs = firstSampleTime + index * sampleIntervalMs

                        telemetryFlow.tryEmit(TelemetryData(
                            delta = delta,
                            phase = phase,
                            timestampMs = sampleTimestampMs
                        ))
                        totalSamples++
                    }
                }

                cleanLine == "PONG" -> onPong()

                cleanLine == "MSG:MODE_USB" -> {
                    reset()
                    paramsFlow.value = paramsFlow.value.copy(transportMode = "USB")
                    consoleFlow.tryEmit("🟢 Modalità USB attiva")
                }

                cleanLine == "STATUS_ACK" -> {
                    consoleFlow.tryEmit("✅ Status ricevuto")
                    Log.d("Parser", "📊 Status ACK ricevuto")
                }

                cleanLine.startsWith("INF:") -> {
                    val parts = cleanLine.substring(4).split(",")
                    if (parts.size >= 2) {
                        val batteryRaw = parts[0].toFloatOrNull() ?: 0f
                        val freqFromEsp = parts[1].toFloatOrNull() ?: 0f
                        val batteryCorrected = batteryRaw * 4.10f

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
                    // ★ Filtra messaggi tecnici ripetitivi
                    if (!msg.contains("Soglie:") && !msg.contains("groundRMS")) {
                        consoleFlow.tryEmit("ℹ️ $msg")
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
        totalSamples = 0L
        droppedBatches = 0L
        Log.d("Parser", "Parser resettato")
    }
}