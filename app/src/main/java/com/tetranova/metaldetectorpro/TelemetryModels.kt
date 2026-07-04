package com.tetranova.metaldetectorpro

// MODIFICA CONCORDATA: timestampMs SENZA default value.
// Motivazione: il timestamp deve essere quello di RICEZIONE del campione dall'USB,
// non quello di creazione dell'oggetto. Rimuovendo il default, il compilatore
// forza chiunque crei un TelemetryData a passare esplicitamente il timestamp,
// eliminando il rischio di usare accidentalmente un timestamp inaccurato.
data class TelemetryData(
    val delta: Float,
    val phase: Float,
    val timestampMs: Long  // ← OBBLIGATORIO, nessun default
)

data class DeviceParams(
    val battery: Float = 0f,
    val frequency: Float = 0f,
    val transportMode: String = "UNKNOWN",
    val baseline: Float = 0f,
    val groundPhase: Float = 0f,
    val batteryReady: Boolean = false
)

data class AnalysisResult(
    val vdi: Int = 0,
    val type: String = "IDLE",
    val confidence: Float = 0f,
    val amplitude: Float = 0f,
    val isLocked: Boolean = false,
    val depth: Float = 0f
)

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val deviceName: String) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}