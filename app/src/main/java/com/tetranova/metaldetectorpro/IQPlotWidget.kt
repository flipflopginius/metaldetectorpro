package com.tetranova.metaldetectorpro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private const val TRAIL_LENGTH = 30
private const val DISC_MIN_GAP = 1f

// ===== FUNZIONI DI ESTENSIONE PER IL DISEGNO =====
private fun DrawScope.drawGrid(cx: Float, cy: Float, scale: Float, maxRange: Float) {
    val gridColor = Color(0xFF2A2A2A)
    val step = maxRange / 2f * scale
    for (sign in listOf(-1f, 1f)) {
        drawLine(gridColor, Offset(cx + sign * step, 0f), Offset(cx + sign * step, size.height))
        drawLine(gridColor, Offset(0f, cy + sign * step), Offset(size.width, cy + sign * step))
    }
    drawLine(Color(0xFF444444), Offset(cx, 0f), Offset(cx, size.height))
    drawLine(Color(0xFF444444), Offset(0f, cy), Offset(size.width, cy))
}

private fun DrawScope.drawColoredTrail(
    trail: List<IQVector>, baseline: IQVector, cx: Float, cy: Float, scale: Float
) {
    if (trail.size < 2) return
    for (i in 1 until trail.size) {
        val v0 = trail[i - 1]; val v1 = trail[i]
        val dI0 = v0.i - baseline.i
        val dQ0 = v0.q - baseline.q
        val dI1 = v1.i - baseline.i
        val dQ1 = v1.q - baseline.q

        val angle = atan2(dQ1, dI1) * (180f / PI.toFloat())
        val hue = ((angle + 90f) / 180f * 0.6f + 0.3f).coerceIn(0f, 1f)
        val color = Color.hsl(hue, 0.8f, 0.5f)
        val alpha = (i.toFloat() / trail.size) * 0.8f

        val x0 = cx + dI0 * scale; val y0 = cy - dQ0 * scale
        val x1 = cx + dI1 * scale; val y1 = cy - dQ1 * scale

        drawLine(
            color = color.copy(alpha = alpha), start = Offset(x0, y0),
            end = Offset(x1, y1), strokeWidth = 1.5.dp.toPx()
        )
    }
}

// ===== COMPOSABLE PRINCIPALE =====
@Composable
fun IQPlotWidget(
    vm: DetectorViewModelV2,
    modifier: Modifier = Modifier
) {
    val trail = remember { mutableListOf<IQVector>() }
    var redrawTrigger by remember { mutableIntStateOf(0) }

    val current = vm.iqCurrent
    val baseline = vm.iqBaseline
    val vr = vm.vectorResult
    val threshold = vm.vectorProcessor.enterDetectionThreshold
    val exitThreshold = vm.vectorProcessor.exitDetectionThreshold
    val groundCenter = vm.vectorProcessor.groundCenter

    LaunchedEffect(current) {
        trail.add(current)
        if (trail.size > TRAIL_LENGTH) trail.removeAt(0)
        redrawTrigger++
    }

    val maxRange = maxOf(
        threshold * 5f,
        (vr?.vectorDistance ?: 0f) * 2.5f,
        groundCenter.magnitude * 1.5f,
        0.05f
    )

    Column(
        modifier = modifier
            .size(220.dp)
            .background(Color(0xFF0D0D0D)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "PIANO I/Q",
                color = Color(0xFF666666), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Scala: ±${"%.2f".format(maxRange)}",
                color = Color(0xFF444444), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Canvas(modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val scale = (size.minDimension / 2f) / maxRange

            drawGrid(cx, cy, scale, maxRange)

            // Soglia di uscita
            drawCircle(
                color = Color(0xFF3A3A3A), radius = exitThreshold * scale,
                center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx())
            )

            // Soglia di entrata
            drawCircle(
                color = Color(0xFF1A4A3A), radius = threshold * scale,
                center = Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx())
            )

            // groundCenter
            val gcPx = cx + groundCenter.i * scale
            val gcPy = cy - groundCenter.q * scale
            drawCircle(
                color = Color(0xFFFFD740).copy(alpha = 0.6f), radius = 4.dp.toPx(),
                center = Offset(gcPx, gcPy)
            )
            drawLine(
                color = Color(0xFFFFD740).copy(alpha = 0.2f),
                start = Offset(cx, cy), end = Offset(gcPx, gcPy),
                strokeWidth = 1.dp.toPx()
            )

            // Trail colorato
            drawColoredTrail(trail, baseline, cx, cy, scale)

            // Punto centrale
            drawCircle(color = Color(0xFF00FF88), radius = 5.dp.toPx(), center = Offset(cx, cy))

            // Vettore corrente
            val isDetected = vr?.isDetected == true
            val isFerroso = vr?.metalType == "FERRO"
            val dotColor = when {
                !isDetected -> Color(0xFF888888)
                isFerroso   -> Color(0xFFFF6666)
                else        -> Color(0xFF66FFCC)
            }

            val dotRadius = if (isDetected) 8.dp.toPx() else 5.dp.toPx()
            val dI = current.i - baseline.i
            val dQ = current.q - baseline.q
            val px = cx + dI * scale
            val py = cy - dQ * scale

            if (isDetected) {
                drawCircle(
                    color = dotColor.copy(alpha = 0.25f), radius = dotRadius * 2.5f,
                    center = Offset(px, py)
                )
                drawLine(
                    color = dotColor.copy(alpha = 0.6f), start = Offset(cx, cy),
                    end = Offset(px, py), strokeWidth = 1.5.dp.toPx()
                )
            }

            drawCircle(color = dotColor, radius = dotRadius, center = Offset(px, py))

            // Scala grafica (solo linea)
            drawLine(
                color = Color(0xFF333333), start = Offset(10f, size.height - 10f),
                end = Offset(10f + 20.dp.toPx(), size.height - 10f),
                strokeWidth = 2.dp.toPx()
            )
        }

        val vr2 = vm.vectorResult
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                "e_tang=%.3f ".format(vr2?.vectorDistance ?: 0f), color = Color(0xFFAAAAAA),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )

            val thetaStabileUI = if (vr2?.isDetected == true) vr2.relativeAngleDeg else 0.0f
            val isFerrosoUI = vr2?.metalType == "FERRO"

            Text(
                "θ=%.1f° ".format(thetaStabileUI),
                color = if (vr2?.isDetected == true) {
                    if (isFerrosoUI) Color(0xFFFF6666) else Color(0xFF64FFDA)
                } else {
                    Color(0xFF555555)
                },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (vr2?.isDetected == true) FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

// ===== ALTRI COMPOSABLE =====
@Composable
fun MainScanDisplayV2(
    vm: DetectorViewModelV2,
    modifier: Modifier = Modifier
) {
    val res = vm.analysis
    val params = vm.params
    val liveDelta = vm.liveDelta
    val phaseDiff = vm.phaseDiff
    val vr = vm.vectorResult
    val isAngleValidUI = vr?.isAngleValid == true
    val sogliaAttuale = vm.vectorProcessor.enterDetectionThreshold
    val maxScale = sogliaAttuale * 5f

    val deltaNorm = (abs(liveDelta) / maxScale).coerceIn(0f, 1f)
    val phaseNorm = vm.phaseNormalized

    var discLow by remember(vm.discLow) { mutableFloatStateOf(vm.discLow) }
    var discHigh by remember(vm.discHigh) { mutableFloatStateOf(vm.discHigh) }
    var phaseHysteresis by remember(vm.phaseHysteresis) { mutableFloatStateOf(vm.phaseHysteresis) }

    val isGroundCalibrating = vm.isGroundCalibrating
    val groundAngleDeg by remember {
        derivedStateOf {
            // ✅ CORRETTO: Usiamo groundPhaseOffsetDeg al posto di groundAngleRad
            vm.vectorProcessor.groundPhaseOffsetDeg
        }
    }

    val groundQuality = vm.pcaQuality
    val mineralization = vm.terrainMineralization
    val reactivity = vm.terrainReactivity
    val stability = vm.terrainStability
    val autoMode = vm.autoTerrainMode
    val groundResidue = vm.vectorProcessor.tangentialRMS

    val batteryVoltage = params.battery
    val batteryAvailable = batteryVoltage > 0f
    val batteryColor = when {
        !batteryAvailable -> Color(0xFF555555)
        batteryVoltage < 14.0f -> Color(0xFFFF6B6B)
        else -> Color(0xFF4ECDC4)
    }

    val batteryFillColor = when {
        !batteryAvailable -> Color(0xFF333333)
        batteryVoltage < 14.0f -> Color(0xFFFF5252)
        else -> Color(0xFF2ECC71)
    }

    val batteryLevel = if (batteryAvailable)
        ((batteryVoltage - 12.0f) / (16.8f - 12.0f)).coerceIn(0f, 1f)
    else 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (batteryAvailable) "BAT: %.1fV ".format(batteryVoltage) else "BAT: ---",
                color = batteryColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            BatteryIcon(
                level = batteryLevel,
                fillColor = batteryFillColor,
                strokeColor = batteryColor,
                modifier = Modifier.size(28.dp, 14.dp)
            )

            val displayType = when (res.type.trim()) {
                "FERRO" -> "FERROSO"
                "NON_FERRO" -> "NON FERROSO"
                else -> res.type.trim()
            }

            val statusText = when {
                res.type.trim() == "IDLE" -> " RICERCA"
                res.isLocked -> "🔒 $displayType"
                displayType.isNotEmpty() -> displayType
                else -> "🔍 RICERCA"
            }

            val statusColor = when {
                res.type.trim() == "IDLE" -> Color(0xFF888888)
                res.isLocked -> Color(0xFF64FFDA)
                else -> Color(0xFFFFD740)
            }

            Text(
                statusText, color = statusColor, fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = if (res.isLocked) FontWeight.ExtraBold else FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // IQ PLOT + METRICHE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            IQPlotWidget(vm = vm, modifier = Modifier.size(200.dp))

            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Qualità Terreno", color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                QualityIndicator(label = "PCA", value = groundQuality, goodThreshold = 5f, unit = "")
                QualityIndicator(label = "Mineral.", value = mineralization, goodThreshold = 2f, unit = "")
                QualityIndicator(label = "Reattività", value = reactivity, goodThreshold = 0.8f, unit = "")
                QualityIndicator(label = "Stabilità", value = stability, goodThreshold = 0.3f, unit = "")

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    "G: ${"%.1f".format(groundAngleDeg)}°",
                    color = if (abs(groundAngleDeg) > 0.5f) Color(0xFF64FFDA) else Color(0xFF666666),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Res: ${"%.4f".format(groundResidue)}",
                    color = Color(0xFF666666),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // METRI con etichette SOTTO
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            // Meter Ampiezza
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AmplitudeMeter(
                    value = deltaNorm,
                    modifier = Modifier.size(100.dp),
                    activeColor = if (res.isLocked) Color(0xFF64FFDA) else Color(0xFF888888)
                )
                Text(
                    "SPINTA: ${"%.4f".format(vr?.vectorDistance ?: 0f)}",
                    fontSize = 9.sp,
                    color = Color(0xFFAAAAAA),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Soglia: ${"%.2f".format(sogliaAttuale)}",
                    fontSize = 8.sp,
                    color = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace
                )
            }

            // Meter Fase
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PhaseMeter(
                    phase = phaseNorm,
                    modifier = Modifier.size(100.dp),
                    activeColor = if (res.isLocked) Color(0xFF64FFDA) else Color(0xFF888888)
                )
                Text(
                    "FASE: ${"%.1f".format(phaseDiff)}°",
                    fontSize = 9.sp,
                    color = if (isAngleValidUI) Color(0xFF64FFDA) else Color(0xFF888888),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Valid: ${if (isAngleValidUI) "✅" else "❌"}",
                    fontSize = 8.sp,
                    color = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // INFO TILE
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            InfoTileWithTooltip("CONF", "${res.confidence.toInt()}%", "Affidabilità classificazione\n0% = incerto, 100% = certo")
            InfoTileWithTooltip("VDI", "${res.vdi}", "Identificazione metallo\n0-30: ferro, 31-60: leghe, 61-99: nobili")
            InfoTileWithTooltip("DEPTH", "${res.depth.toInt()}cm", "Stima profondità bersaglio\nIndicativo: dipende da suolo/target")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // CONTROLLI
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("⚙️ CONTROLLI", color = Color(0xFF888888), fontSize = 11.sp, fontWeight = FontWeight.Bold)

                SafeSensitivitySlider(vm = vm, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Zona Morta IQ: ${"%.1f".format(phaseHysteresis)}°",
                        color = Color(0xFFAAAAAA), fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "ⓘ", color = Color(0xFF666666), fontSize = 10.sp,
                        modifier = Modifier.clickable { /* tooltip */ }
                    )
                }

                Slider(
                    value = phaseHysteresis,
                    onValueChange = {
                        phaseHysteresis = it
                        vm.updatePhaseHysteresis(it)
                    },
                    valueRange = 0f..5f, steps = 50,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF9FA8DA), activeTrackColor = Color(0xFF9FA8DA)
                    )
                )

                Text(
                    "0°=min filtro | 1.5°=stabile | 5°=max freeze",
                    color = Color(0xFF666666), fontSize = 8.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎯 Discriminazione", color = Color(0xFFAAAAAA), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { vm.invertDiscPolarity() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFD740))
                    ) {
                        Text("Inverti polarità", fontSize = 10.sp)
                    }
                }

                DiscriminationBar(
                    discLow = discLow, discHigh = discHigh,
                    modifier = Modifier.fillMaxWidth().height(12.dp).padding(vertical = 2.dp)
                )

                Row {
                    Text("🟡 basso: ${"%.0f".format(discLow)}°", color = Color(0xFFFFD740), fontSize = 10.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text("🔴 alto: ${"%.0f".format(discHigh)}°", color = Color(0xFFFF6666), fontSize = 10.sp)
                }

                Slider(
                    value = discLow,
                    onValueChange = { newLow ->
                        discLow = newLow.coerceIn(1f, discHigh - DISC_MIN_GAP)
                    },
                    onValueChangeFinished = {
                        vm.updateDiscThresholds(discLow, discHigh)
                    },
                    valueRange = 1f..45f,
                    steps = 44,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD740), activeTrackColor = Color(0xFFFFD740))
                )

                Slider(
                    value = discHigh,
                    onValueChange = { newHigh ->
                        discHigh = newHigh.coerceIn(discLow + DISC_MIN_GAP, 45f)
                    },
                    onValueChangeFinished = {
                        vm.updateDiscThresholds(discLow, discHigh)
                    },
                    valueRange = 1f..45f,
                    steps = 44,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF6666), activeTrackColor = Color(0xFFFF6666))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // GROUND BALANCE + AUTO TERRENO
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🌍 GROUND BALANCE", color = Color(0xFF64FFDA), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto", color = if (autoMode) Color(0xFF64FFDA) else Color(0xFF555555), fontSize = 10.sp)
                        Switch(
                            checked = autoMode,
                            onCheckedChange = { vm.autoTerrainMode = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF64FFDA))
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔧 Controllo", color = Color(0xFFAAAAAA), fontSize = 11.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto", color = if (!vm.isManualGroundBalance) Color(0xFF64FFDA) else Color(0xFF555555), fontSize = 10.sp)
                        Switch(
                            checked = vm.isManualGroundBalance,
                            onCheckedChange = { vm.toggleManualGroundBalance() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFD740))
                        )
                        Text("Manuale", color = if (vm.isManualGroundBalance) Color(0xFFFFD740) else Color(0xFF555555), fontSize = 10.sp)
                    }
                }

                if (vm.isManualGroundBalance) {
                    Text(
                        "Angolo G: ${"%.1f".format(vm.manualGroundAngleDeg)}°",
                        color = Color(0xFFFFD740), fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                    Slider(
                        value = vm.manualGroundAngleDeg,
                        onValueChange = { vm.setManualGroundAngle(it) },
                        valueRange = -45f..45f, steps = 90,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD740), activeTrackColor = Color(0xFFFFD740))
                    )
                    Text(
                        "Residuo: ${"%.4f".format(vm.vectorProcessor.tangentialRMS)}",
                        color = Color(0xFF888888), fontSize = 9.sp, fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (isGroundCalibrating) vm.finishGroundCalibration()
                            else vm.startGroundCalibration()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isGroundCalibrating) Color(0xFFFF6B6B) else Color(0xFF1E3A2F),
                            contentColor = if (isGroundCalibrating) Color.White else Color(0xFF64FFDA)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isGroundCalibrating) "⏳ ATTENDERE..." else "🔄 PUMPING", fontSize = if (isGroundCalibrating) 11.sp else 12.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { vm.resetGroundBalance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A), contentColor = Color(0xFFFF6666)),
                        modifier = Modifier.weight(0.6f)
                    ) {
                        Text("↺ RESET", fontSize = 11.sp)
                    }
                }

                when {
                    isGroundCalibrating -> Text("🔄 Pompa la bobina su/giù sul terreno...", color = Color(0xFFFFD740), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    vm.isManualGroundBalance -> Text("✋ Regola l'angolo per minimizzare il residuo", color = Color(0xFFFFD740), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    abs(groundAngleDeg) > 0.5f -> Text("✅ Terreno calibrato a ${"%.1f".format(groundAngleDeg)}°", color = Color(0xFF64FFDA), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    else -> Text("ℹ️ Esegui PUMPING per bilanciare il terreno", color = Color(0xFF666666), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }

                if (autoMode) {
                    Text(
                        "🤖 Auto Terreno attivo: regola soglie in base al terreno",
                        color = Color(0xFF64FFDA), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // GRAFICO STORICO + ISTOGRAMMA
        Card(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ampiezza", color = Color(0xFF888888), fontSize = 9.sp)
                    HistoricalChart(data = vm.historicalData.map { it.first }, color = Color(0xFF64FFDA))
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text("Fase", color = Color(0xFF888888), fontSize = 9.sp)
                    PhaseHistogram(data = vm.phaseHistogram)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { vm.performCalibration() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A2F), contentColor = Color(0xFF64FFDA))
        ) {
            Text("CALIBRA ORA (I/Q)", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ===== COMPONENTI AUSILIARI =====
@Composable
fun QualityIndicator(
    label: String,
    value: Float,
    goodThreshold: Float,
    modifier: Modifier = Modifier,
    unit: String = ""
) {
    val color = when {
        value >= goodThreshold -> Color(0xFF64FFDA)
        value >= goodThreshold * 0.5f -> Color(0xFFFFD740)
        else -> Color(0xFFFF6666)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label:", color = Color(0xFF666666), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(
            "${"%.2f".format(value)}$unit",
            color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
fun HistoricalChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (data.isEmpty()) return@Canvas

        val max = data.maxOrNull()?.let { maxOf(it, 0.01f) } ?: 0.01f
        val step = size.width / data.size

        for (i in 1 until data.size.coerceAtMost(size.width.toInt())) {
            val x0 = (i - 1) * step
            val x1 = i * step
            val y0 = size.height * (1 - data[i - 1] / max)
            val y1 = size.height * (1 - data[i] / max)
            drawLine(color, Offset(x0, y0), Offset(x1, y1), strokeWidth = 1.5.dp.toPx())
        }
    }
}

@Composable
fun PhaseHistogram(
    data: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (data.isEmpty()) return@Canvas

        val bins = 20
        val hist = FloatArray(bins)
        val range = 60f

        for (angle in data) {
            val idx = ((angle + range / 2f) / range * bins).toInt().coerceIn(0, bins - 1)
            hist[idx]++
        }

        val maxCount = hist.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val barWidth = size.width / bins

        for (i in hist.indices) {
            val height = (hist[i] / maxCount) * size.height
            drawRect(
                Color(0xFF64FFDA).copy(alpha = 0.6f),
                Offset(i * barWidth, size.height - height),
                Size(barWidth - 1.dp.toPx(), height)
            )
        }
    }
}

@Composable
fun BatteryIcon(
    level: Float,
    modifier: Modifier = Modifier,
    fillColor: Color = Color(0xFF4CAF50),
    strokeColor: Color = Color(0xFF888888)
) {
    Canvas(modifier = modifier) {
        val width = size.width; val height = size.height
        val strokeWidth = 1.5.dp.toPx()

        drawRoundRect(
            color = strokeColor, topLeft = Offset(0f, 0f),
            size = Size(width * 0.85f, height),
            style = Stroke(width = strokeWidth),
            cornerRadius = CornerRadius(2.dp.toPx())
        )

        drawRect(
            color = strokeColor,
            topLeft = Offset(width * 0.85f, height * 0.3f),
            size = Size(width * 0.15f, height * 0.4f)
        )

        val fillWidth = (width * 0.85f - strokeWidth * 2) * level
        if (fillWidth > 0) drawRoundRect(
            color = fillColor,
            topLeft = Offset(strokeWidth, strokeWidth),
            size = Size(fillWidth, height - strokeWidth * 2),
            cornerRadius = CornerRadius(1.dp.toPx())
        )
    }
}

@Composable
fun AmplitudeMeter(
    value: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFF64FFDA),
    inactiveColor: Color = Color(0xFF2A2A2A)
) {
    Canvas(modifier = modifier.size(100.dp)) {
        drawArc(
            color = inactiveColor, startAngle = 135f, sweepAngle = 270f,
            useCenter = false, style = Stroke(width = 12.dp.toPx())
        )
        drawArc(
            color = activeColor, startAngle = 135f, sweepAngle = value * 270f,
            useCenter = false, style = Stroke(width = 12.dp.toPx())
        )
    }
}

@Composable
fun PhaseMeter(
    phase: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFF64FFDA),
    inactiveColor: Color = Color(0xFF2A2A2A)
) {
    Canvas(modifier = modifier.size(100.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - 12.dp.toPx()
        val strokeWidth = 8.dp.toPx()

        drawArc(
            color = inactiveColor, startAngle = 180f, sweepAngle = 180f,
            useCenter = false, style = Stroke(width = strokeWidth),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2)
        )

        val angle = 270f + (phase * 90f)
        val needleLength = radius - strokeWidth / 2
        val needleEnd = Offset(
            center.x + needleLength * cos(Math.toRadians(angle.toDouble())).toFloat(),
            center.y + needleLength * sin(Math.toRadians(angle.toDouble())).toFloat()
        )

        drawLine(
            color = activeColor, start = center, end = needleEnd,
            strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round
        )

        drawCircle(color = activeColor, radius = 6.dp.toPx(), center = center)
    }
}

@Composable
fun InfoTileWithTooltip(
    label: String,
    value: String,
    tooltip: String,
    modifier: Modifier = Modifier
) {
    var showTooltip by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(70.dp).clickable { showTooltip = !showTooltip }
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 11.sp)
        Text(value, color = Color(0xFFE0E0E0), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        if (showTooltip) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1A1A1A))
                    .padding(4.dp).width(120.dp)
            ) {
                Text(
                    tooltip, color = Color(0xFFCCCCCC), fontSize = 9.sp,
                    lineHeight = 11.sp, fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun DiscriminationBar(
    discLow: Float,
    discHigh: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val range = 45f

        val xLow = (discLow / range) * w
        val xHigh = (discHigh / range) * w

        drawRect(
            color = Color(0xFF555555),
            topLeft = Offset(0f, 0f),
            size = Size(xLow.coerceIn(0f, w), h)
        )

        drawRect(
            color = Color(0xFF1A4A3A),
            topLeft = Offset(xLow.coerceIn(0f, w), 0f),
            size = Size((xHigh - xLow).coerceIn(0f, w), h)
        )

        drawRect(
            color = Color(0xFF4A1A1A),
            topLeft = Offset(xHigh.coerceIn(0f, w), 0f),
            size = Size((w - xHigh).coerceIn(0f, w), h)
        )

        drawLine(
            color = Color(0xFFFFD740),
            start = Offset(xLow.coerceIn(0f, w), 0f),
            end = Offset(xLow.coerceIn(0f, w), h),
            strokeWidth = 2.dp.toPx()
        )

        drawLine(
            color = Color(0xFFFF6666),
            start = Offset(xHigh.coerceIn(0f, w), 0f),
            end = Offset(xHigh.coerceIn(0f, w), h),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
fun SafeSensitivitySlider(
    vm: DetectorViewModelV2,
    modifier: Modifier = Modifier
) {
    val currentSensFromDevice = vm.sensAmpiezza
    var localSliderValue by remember(currentSensFromDevice) { mutableFloatStateOf(currentSensFromDevice) }

    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Text(
            text = "Sensibilità: ${localSliderValue.toInt()} (K=%.2f)".format(vm.vectorProcessor.kVect),
            color = Color(0xFFAAAAAA),
            fontSize = 11.sp
        )

        Slider(
            value = localSliderValue,
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF64FFDA),
                activeTrackColor = Color(0xFF64FFDA)
            ),
            onValueChange = { newValue ->
                localSliderValue = newValue
            },
            onValueChangeFinished = {
                vm.updateSensAmpiezza(localSliderValue)
            }
        )
    }
}