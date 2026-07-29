package com.tetranova.prerex

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import androidx.compose.ui.unit.TextUnit

private const val TRAIL_LENGTH = 30
private const val DISC_MIN_GAP = 1f

// Passo di profondità isometrica: distanza (in px, dopo lo scaling già
// esistente) tra due campioni consecutivi del trail lungo l'asse Z (Tempo).
// Regolabile per "aprire"/"comprimere" la visuale temporale senza toccare
// in alcun modo le logiche di 'scale' e 'maxRange' già presenti.
private const val Z_STEP_FACTOR = 0.12f

// Numero di segmenti usati per approssimare, in vista isometrica, un cerchio
// del piano X-Y (soglie di rilevamento). Costante fissa: nessun array viene
// allocato, ogni punto è calcolato e scartato al volo nel ciclo di disegno.
private const val ISO_CIRCLE_SEGMENTS = 24

// Costanti trigonometriche per la proiezione isometrica a 30°, calcolate
// una sola volta al caricamento della classe (non per-frame).
private val ISO_COS30 = cos(PI.toFloat() / 6f)
private val ISO_SIN30 = sin(PI.toFloat() / 6f)

/** Le due modalità grafiche selezionabili dall'utente per il widget 3D. */
private enum class PlotViewMode { ISOMETRICO, ORTOGONALE }

// ===== PROIEZIONE ISOMETRICA (spazio 3D -> schermo 2D) =====
// X = Radiale (dI), Y = Tangenziale (dQ), Z = Tempo (profondità nel trail).
// u = cx + (X - Z) * cos(30°)
// v = cy - Y + (X + Z) * sin(30°)
// Funzioni pure e allocation-free: solo aritmetica in virgola mobile.
private fun isoU(cx: Float, x: Float, z: Float): Float = cx + (x - z) * ISO_COS30
private fun isoV(cy: Float, x: Float, y: Float, z: Float): Float = cy - y + (x + z) * ISO_SIN30

// ===== FUNZIONI DI ESTENSIONE PER IL DISEGNO 2D CLASSICO (piano I/Q) =====
// Logica di scale/maxRange invariata rispetto all'originale. L'unica
// modifica è l'eliminazione di un `listOf(-1f, 1f)` che allocava una List
// autoboxata ad ogni singolo frame di disegno: ora sono due chiamate dirette.

private fun DrawScope.drawGrid(cx: Float, cy: Float, scale: Float, maxRange: Float) {
    val gridColor = Color(0xFF2A2A2A)
    val step = maxRange / 2f * scale
    drawLine(gridColor, Offset(cx - step, 0f), Offset(cx - step, size.height))
    drawLine(gridColor, Offset(cx + step, 0f), Offset(cx + step, size.height))
    drawLine(gridColor, Offset(0f, cy - step), Offset(size.width, cy - step))
    drawLine(gridColor, Offset(0f, cy + step), Offset(size.width, cy + step))
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

// ===== VISTA 3D — ASSONOMETRIA ISOMETRICA =====

/**
 * Disegna la terna di assi isometrici (Radiale, Tangenziale, Tempo) che si
 * intersecano al centro (cx, cy), inclinati di 120° tra loro sullo schermo.
 * Le etichette usano un Paint riutilizzato (creato una sola volta dal
 * chiamante con `remember`): nessuna allocazione per frame.
 */
private fun DrawScope.drawIsometricAxes(cx: Float, cy: Float, axisLen: Float, labelPaint: Paint) {
    val axisColor = Color(0xFF4A4A4A)
    val strokeW = 1.dp.toPx()

    // Asse Radiale (X): Y=0, Z=0
    val radX = isoU(cx, axisLen, 0f)
    val radY = isoV(cy, axisLen, 0f, 0f)
    drawLine(axisColor, Offset(cx, cy), Offset(radX, radY), strokeWidth = strokeW)

    // Asse Tangenziale (Y): X=0, Z=0 -> verticale puro, come nel piano I/Q classico
    val tangX = cx
    val tangY = cy - axisLen
    drawLine(axisColor, Offset(cx, cy), Offset(tangX, tangY), strokeWidth = strokeW)

    // Asse Tempo (Z): X=0, Y=0 -> il passato si allontana verso sinistra
    val timeX = isoU(cx, 0f, axisLen)
    val timeY = isoV(cy, 0f, 0f, axisLen)
    drawLine(axisColor, Offset(cx, cy), Offset(timeX, timeY), strokeWidth = strokeW)

    drawContext.canvas.nativeCanvas.apply {
        drawText("Radiale [G]", radX + 4f, radY, labelPaint)
        drawText("Tangenziale [T]", tangX + 4f, tangY - 4f, labelPaint)
        drawText("Tempo [Z]", timeX - 4f, timeY + 12f, labelPaint)
    }
}

/**
 * Disegna lo storico 'trail' nello spazio isometrico come sequenza di
 * segmenti 3D proiettati. Z rappresenta l'indice temporale: il campione più
 * recente (ultimo elemento della lista) è a Z=0, i più vecchi si allontanano
 * (Z crescente) e vengono sfumati in alpha, esattamente come nella vista 2D
 * originale ma con la profondità temporale resa esplicita.
 */
private fun DrawScope.drawIsometricTrail(
    trail: List<IQVector>, baseline: IQVector, cx: Float, cy: Float, scale: Float, zStep: Float
) {
    if (trail.size < 2) return
    val lastIndex = trail.size - 1
    for (i in 1..lastIndex) {
        val v0 = trail[i - 1]; val v1 = trail[i]
        val x0 = (v0.i - baseline.i) * scale
        val y0 = (v0.q - baseline.q) * scale
        val z0 = (lastIndex - (i - 1)) * zStep
        val x1 = (v1.i - baseline.i) * scale
        val y1 = (v1.q - baseline.q) * scale
        val z1 = (lastIndex - i) * zStep

        val angle = atan2(v1.q - baseline.q, v1.i - baseline.i) * (180f / PI.toFloat())
        val hue = ((angle + 90f) / 180f * 0.6f + 0.3f).coerceIn(0f, 1f)
        val color = Color.hsl(hue, 0.8f, 0.5f)
        val alpha = (i.toFloat() / trail.size) * 0.8f

        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(isoU(cx, x0, z0), isoV(cy, x0, y0, z0)),
            end = Offset(isoU(cx, x1, z1), isoV(cy, x1, y1, z1)),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

/**
 * Approssima, in assonometria isometrica, un cerchio orizzontale del piano
 * X-Y (Z=0) — usato per le soglie di rilevamento — con un poligono a
 * segmenti fissi (ISO_CIRCLE_SEGMENTS). In proiezione isometrica un cerchio
 * diventa geometricamente un'ellisse: questa è un'approssimazione voluta,
 * senza allocare alcun array di punti (ogni vertice è calcolato e scartato
 * subito dopo il disegno del segmento).
 */
private fun DrawScope.drawIsoCircleXY(cx: Float, cy: Float, radius: Float, color: Color, strokeWidth: Float) {
    if (radius <= 0f) return
    val angStep = (2f * PI.toFloat()) / ISO_CIRCLE_SEGMENTS
    var prevX = isoU(cx, radius, 0f)
    var prevY = isoV(cy, radius, 0f, 0f)
    for (k in 1..ISO_CIRCLE_SEGMENTS) {
        val theta = k * angStep
        val px = radius * cos(theta)
        val py = radius * sin(theta)
        val curX = isoU(cx, px, 0f)
        val curY = isoV(cy, px, py, 0f)
        drawLine(color, Offset(prevX, prevY), Offset(curX, curY), strokeWidth = strokeWidth)
        prevX = curX; prevY = curY
    }
}

/** Assembla l'intera vista isometrica sul Canvas a piena area. */
private fun DrawScope.drawIsometricView(
    trail: List<IQVector>,
    current: IQVector,
    baseline: IQVector,
    isDetected: Boolean,
    isFerroso: Boolean,
    threshold: Float,
    exitThreshold: Float,
    groundCenter: IQVector,
    maxRange: Float,
    labelPaint: Paint
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    // scale e maxRange: stessa identica formula del widget 2D originale.
    val scale = (size.minDimension / 2f) / maxRange
    val zStep = maxRange * Z_STEP_FACTOR * scale
    val axisLen = size.minDimension * 0.42f

    drawIsometricAxes(cx, cy, axisLen, labelPaint)

    // Soglie come anelli orizzontali proiettati a Z=0.
    drawIsoCircleXY(cx, cy, exitThreshold * scale, Color(0xFF3A3A3A), 1.dp.toPx())
    drawIsoCircleXY(cx, cy, threshold * scale, Color(0xFF1A4A3A), 1.5.dp.toPx())

    // Ground center, proiettato a Z=0 (stesso riferimento del widget originale).
    val gX = groundCenter.i * scale
    val gY = groundCenter.q * scale
    val gU = isoU(cx, gX, 0f); val gV = isoV(cy, gX, gY, 0f)
    drawLine(Color(0xFFFFD740).copy(alpha = 0.2f), Offset(cx, cy), Offset(gU, gV), strokeWidth = 1.dp.toPx())
    drawCircle(Color(0xFFFFD740).copy(alpha = 0.6f), radius = 4.dp.toPx(), center = Offset(gU, gV))

    // Trail storico nello spazio isometrico (Z crescente = passato).
    drawIsometricTrail(trail, baseline, cx, cy, scale, zStep)

    // Origine = istante corrente, Z = 0.
    drawCircle(Color(0xFF00FF88), radius = 5.dp.toPx(), center = Offset(cx, cy))

    // Vettore corrente, sempre a Z = 0.
    val dotColor = vectorDotColor(isDetected, isFerroso)
    val dotRadius = if (isDetected) 8.dp.toPx() else 5.dp.toPx()
    val dX = (current.i - baseline.i) * scale
    val dY = (current.q - baseline.q) * scale
    val pU = isoU(cx, dX, 0f); val pV = isoV(cy, dX, dY, 0f)
    if (isDetected) {
        drawCircle(dotColor.copy(alpha = 0.25f), radius = dotRadius * 2.5f, center = Offset(pU, pV))
        drawLine(dotColor.copy(alpha = 0.6f), Offset(cx, cy), Offset(pU, pV), strokeWidth = 1.5.dp.toPx())
    }
    drawCircle(dotColor, radius = dotRadius, center = Offset(pU, pV))
}

// ===== VISTA 3D — PIANI ORTOGONALI (split in 3 sottomappe 2D) =====

/** Cornice, micro-griglia centrale ed etichetta di un quadrante ortogonale. */
private fun DrawScope.drawQuadrantFrame(left: Float, top: Float, w: Float, h: Float, label: String, labelPaint: Paint) {
    val borderColor = Color(0xFF333333)
    drawLine(borderColor, Offset(left, top), Offset(left + w, top), strokeWidth = 1.dp.toPx())
    drawLine(borderColor, Offset(left, top), Offset(left, top + h), strokeWidth = 1.dp.toPx())
    val qcx = left + w / 2f
    val qcy = top + h / 2f
    val gridColor = Color(0xFF222222)
    drawLine(gridColor, Offset(qcx, top), Offset(qcx, top + h), strokeWidth = 0.75.dp.toPx())
    drawLine(gridColor, Offset(left, qcy), Offset(left + w, qcy), strokeWidth = 0.75.dp.toPx())
    drawContext.canvas.nativeCanvas.drawText(label, left + 4f, top + 12f, labelPaint)
}

/** Piano XZ: Radiale (orizzontale) vs Tempo (verticale, verso il basso = passato). */
private fun DrawScope.drawTrailXZ(
    trail: List<IQVector>, baseline: IQVector, qcx: Float, qTop: Float, scale: Float, zPxStep: Float
) {
    if (trail.size < 2) return
    val lastIndex = trail.size - 1
    for (i in 1..lastIndex) {
        val v0 = trail[i - 1]; val v1 = trail[i]
        val x0 = qcx + (v0.i - baseline.i) * scale
        val y0 = qTop + (lastIndex - (i - 1)) * zPxStep
        val x1 = qcx + (v1.i - baseline.i) * scale
        val y1 = qTop + (lastIndex - i) * zPxStep
        val alpha = (i.toFloat() / trail.size) * 0.8f
        drawLine(
            color = Color(0xFF64FFDA).copy(alpha = alpha),
            start = Offset(x0, y0), end = Offset(x1, y1),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

/** Piano YZ: Tangenziale (orizzontale) vs Tempo (verticale, verso il basso = passato). */
private fun DrawScope.drawTrailYZ(
    trail: List<IQVector>, baseline: IQVector, qcx: Float, qTop: Float, scale: Float, zPxStep: Float
) {
    if (trail.size < 2) return
    val lastIndex = trail.size - 1
    for (i in 1..lastIndex) {
        val v0 = trail[i - 1]; val v1 = trail[i]
        val x0 = qcx + (v0.q - baseline.q) * scale
        val y0 = qTop + (lastIndex - (i - 1)) * zPxStep
        val x1 = qcx + (v1.q - baseline.q) * scale
        val y1 = qTop + (lastIndex - i) * zPxStep
        val alpha = (i.toFloat() / trail.size) * 0.8f
        drawLine(
            color = Color(0xFFFF6666).copy(alpha = alpha),
            start = Offset(x0, y0), end = Offset(x1, y1),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

/** Colore del punto del vettore corrente, coerente con l'originale 2D. */
private fun vectorDotColor(isDetected: Boolean, isFerroso: Boolean): Color = when {
    !isDetected -> Color(0xFF888888)
    isFerroso   -> Color(0xFFFF6666)
    else        -> Color(0xFF66FFCC)
}

/** Assembla l'intera vista a piani ortogonali (griglia 2x2 di quadranti). */
private fun DrawScope.drawOrthogonalView(
    trail: List<IQVector>,
    current: IQVector,
    baseline: IQVector,
    isDetected: Boolean,
    isFerroso: Boolean,
    threshold: Float,
    exitThreshold: Float,
    groundCenter: IQVector,
    maxRange: Float,
    labelPaint: Paint
) {
    val halfW = size.width / 2f
    val halfH = size.height / 2f
    // scale del sotto-quadrante: stessa formula originale, applicata alla metà canvas.
    val quadScale = (min(halfW, halfH) / 2f) / maxRange
    val lastIndex = (trail.size - 1).coerceAtLeast(1)
    val zPxRange = min(halfW, halfH) * 0.85f
    val zPxStep = zPxRange / lastIndex
    val dotColor = vectorDotColor(isDetected, isFerroso)

    // --- Quadrante 1 (alto-sx): piano XY, il piano I/Q originale scalato ---
    val q1cx = halfW / 2f; val q1cy = halfH / 2f
    drawQuadrantFrame(0f, 0f, halfW, halfH, "XY (I/Q)", labelPaint)
    drawGrid(q1cx, q1cy, quadScale, maxRange)
    drawCircle(Color(0xFF3A3A3A), radius = exitThreshold * quadScale, center = Offset(q1cx, q1cy), style = Stroke(width = 1.dp.toPx()))
    drawCircle(Color(0xFF1A4A3A), radius = threshold * quadScale, center = Offset(q1cx, q1cy), style = Stroke(width = 1.5.dp.toPx()))
    drawColoredTrail(trail, baseline, q1cx, q1cy, quadScale)
    drawCircle(Color(0xFF00FF88), radius = 4.dp.toPx(), center = Offset(q1cx, q1cy))
    val d1x = q1cx + (current.i - baseline.i) * quadScale
    val d1y = q1cy - (current.q - baseline.q) * quadScale
    drawCircle(dotColor, radius = if (isDetected) 6.dp.toPx() else 4.dp.toPx(), center = Offset(d1x, d1y))

    // --- Quadrante 2 (alto-dx): piano XZ, scostamento radiale nel tempo ---
    val q2cx = halfW + halfW / 2f
    drawQuadrantFrame(halfW, 0f, halfW, halfH, "XZ (Radiale/Tempo)", labelPaint)
    drawTrailXZ(trail, baseline, q2cx, 6f, quadScale, zPxStep)
    drawCircle(Color(0xFF00FF88), radius = 3.dp.toPx(), center = Offset(q2cx, 6f))

    // --- Quadrante 3 (basso-sx): piano YZ, stabilità tangenziale del target nel tempo ---
    val q3cx = halfW / 2f; val q3Top = halfH
    drawQuadrantFrame(0f, halfH, halfW, halfH, "YZ (Tang./Tempo)", labelPaint)
    drawTrailYZ(trail, baseline, q3cx, q3Top + 6f, quadScale, zPxStep)
    drawCircle(Color(0xFF00FF88), radius = 3.dp.toPx(), center = Offset(q3cx, q3Top + 6f))

    // --- Quadrante 4 (basso-dx): riservato a sviluppi futuri ---
    drawQuadrantFrame(halfW, halfH, halfW, halfH, "—", labelPaint)
}

// ===== COMPOSABLE PRINCIPALE =====

@Composable
fun IQPlotWidget(
    current: IQVector,
    baseline: IQVector,
    isDetected: Boolean,
    isFerroso: Boolean,
    threshold: Float,
    exitThreshold: Float,
    groundCenter: IQVector,
    modifier: Modifier = Modifier,
    vectorDistance: Float = 0f,
    relativeAngleDeg: Float = 0f
) {
    val trail = remember { mutableListOf<IQVector>() }
    var redrawTrigger by remember { mutableIntStateOf(0) }

    // Stato interno: modalità di visualizzazione 3D attiva.
    var viewMode by remember { mutableStateOf(PlotViewMode.ISOMETRICO) }

    // Paint per le etichette disegnate sul Canvas: creato UNA sola volta
    // tramite `remember` e riutilizzato ad ogni frame -> zero allocazioni
    // e zero pressione sul Garbage Collector durante il disegno.
    val labelPaint = remember {
        Paint().apply {
            color = Color(0xFF888888).toArgb()
            textSize = 20f
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
    }

    LaunchedEffect(current) {
        trail.add(current)
        if (trail.size > TRAIL_LENGTH) trail.removeAt(0)
        redrawTrigger++
    }

    val maxRange = maxOf(
        threshold * 5f,
        vectorDistance * 2.5f,
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Interruttore grafico ISO / ORTO: due Text cliccabili, quella
            // attiva evidenziata in colore acceso e grassetto.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ISO",
                    color = if (viewMode == PlotViewMode.ISOMETRICO) Color(0xFF64FFDA) else Color(0xFF555555),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (viewMode == PlotViewMode.ISOMETRICO) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { viewMode = PlotViewMode.ISOMETRICO }
                )
                Text(" / ", color = Color(0xFF444444), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "ORTO",
                    color = if (viewMode == PlotViewMode.ORTOGONALE) Color(0xFF64FFDA) else Color(0xFF555555),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (viewMode == PlotViewMode.ORTOGONALE) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { viewMode = PlotViewMode.ORTOGONALE }
                )
            }
            Text(
                "Scala: ±${"%.2f".format(maxRange)}",
                color = Color(0xFF444444), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Canvas(modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            when (viewMode) {
                PlotViewMode.ISOMETRICO -> drawIsometricView(
                    trail, current, baseline, isDetected, isFerroso,
                    threshold, exitThreshold, groundCenter, maxRange, labelPaint
                )
                PlotViewMode.ORTOGONALE -> drawOrthogonalView(
                    trail, current, baseline, isDetected, isFerroso,
                    threshold, exitThreshold, groundCenter, maxRange, labelPaint
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                "e_tang=%.3f ".format(vectorDistance), color = Color(0xFFAAAAAA),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
            val thetaStabileUI = if (isDetected) relativeAngleDeg else 0.0f
            Text(
                "θ=%.1f° ".format(thetaStabileUI),
                color = if (isDetected) {
                    if (isFerroso) Color(0xFFFF6666) else Color(0xFF64FFDA)
                } else {
                    Color(0xFF555555)
                },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isDetected) FontWeight.Bold else FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

// ===== MAIN SCAN DISPLAY =====

@Composable
fun MainScanDisplayV2(
    vm: DetectorViewModelV2,
    modifier: Modifier = Modifier,
    onNavigateToCalibration: () -> Unit = {}
) {
    val res = vm.analysis
    val params = vm.params
    val liveDelta = vm.liveDelta
    val phaseDiff = vm.phaseDiff
    val vr = vm.vectorResult
    val isAngleValidUI = vr?.isAngleValid == true
    val sogliaAttuale = vm.vectorProcessor.enterDetectionThreshold / vm.vectorProcessor.kVect
    val maxScale = sogliaAttuale * 5f
    val deltaNorm = (abs(liveDelta) / maxScale).coerceIn(0f, 1f)
    val phaseNorm = vm.phaseNormalized

    var discLow by remember(vm.discLow) { mutableFloatStateOf(vm.discLow) }
    var discHigh by remember(vm.discHigh) { mutableFloatStateOf(vm.discHigh) }
    var phaseHysteresis by remember(vm.phaseHysteresis) { mutableFloatStateOf(vm.phaseHysteresis) }

    val groundAngleDeg = vm.vectorProcessor.groundPhaseOffsetDeg

    val groundQuality = vm.pcaQuality
    val mineralization = vm.terrainMineralization
    val reactivity = vm.terrainReactivity
    val stability = vm.terrainStability
    val groundResidue = vm.vectorProcessor.tangentialRMS

    val batteryVoltage = params.battery
    val batteryAvailable = batteryVoltage > 0f

    val batteryColor = when {
        !batteryAvailable -> Color(0xFF555555)
        batteryVoltage < 13.6f -> Color(0xFFFF6B6B)
        batteryVoltage < 14.8f -> Color(0xFFFFD740)
        else -> Color(0xFF4ECDC4)
    }

    val batteryFillColor = when {
        !batteryAvailable -> Color(0xFF333333)
        batteryVoltage < 13.6f -> Color(0xFFFF5252)
        batteryVoltage < 14.8f -> Color(0xFFFFD740)
        else -> Color(0xFF2ECC71)
    }

    val batteryLevel = if (batteryAvailable) {
        val minVoltage = 12.0f
        val maxVoltage = 16.8f
        ((batteryVoltage - minVoltage) / (maxVoltage - minVoltage)).coerceIn(0f, 1f)
    } else 0f

    // AutoTracking states
    val isAutoTracking by vm.isAutoGroundTracking.collectAsState()
    val trackingQuality by vm.trackingQuality.collectAsState()
    val trackingStatus by vm.trackingStatus.collectAsState()
    val trackerUpdates by vm.trackerUpdates.collectAsState()

    // G.ID (lettura continua indipendente del terreno) / G.SET (riferimento
    // calibrato al Pumping) — permettono di decidere QUANDO attivare il
    // tracking, come nei detector commerciali di fascia alta.
    val gId by vm.groundIdDeg.collectAsState()
    val gSet by vm.groundSetDeg.collectAsState()
    val prediction by vm.currentPrediction.collectAsState()
    val gDrift = abs(gId - gSet).let { if (it > 180f) 360f - it else it }
    val gDivergenceThreshold = vm.vectorProcessor.groundIdDivergenceThresholdDeg
    val gIdColor = when {
        gDrift > gDivergenceThreshold -> Color(0xFFFF6666)
        gDrift > gDivergenceThreshold * 0.5f -> Color(0xFFFFD740)
        else -> Color(0xFF64FFDA)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // =============================================================
        // HEADER
        // =============================================================
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

            // PREDIZIONE NEL HEADER
            val currentPrediction = prediction
            val displayType = when (res.type.trim()) {
                "FERRO" -> "FERROSO"
                "NON_FERRO" -> "NON FERROSO"
                else -> res.type.trim()
            }

            val statusText = when {
                res.type.trim() == "IDLE" -> "🔍 RICERCA"
                res.isLocked && currentPrediction != null -> "🔒 ${currentPrediction.fingerprint.name} (${currentPrediction.similarity.toInt()}%)"
                res.isLocked -> "🔒 $displayType"
                displayType.isNotEmpty() -> displayType
                else -> "🔍 RICERCA"
            }

            val statusColor = when {
                res.type.trim() == "IDLE" -> Color(0xFF888888)
                res.isLocked && currentPrediction != null -> {
                    try {
                        Color(currentPrediction.fingerprint.metalType.getColor())
                    } catch (e: Exception) {
                        Color(0xFF64FFDA)
                    }
                }
                res.isLocked -> Color(0xFF64FFDA)
                else -> Color(0xFFFFD740)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    statusText, color = statusColor, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = if (res.isLocked) FontWeight.ExtraBold else FontWeight.Bold
                )
                Text(
                    "G.ID %.1f° / G.SET %.1f°".format(gId, gSet),
                    color = gIdColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // IQ PLOT + METRICHE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            IQPlotWidget(
                current = vm.iqCurrent,
                baseline = vm.iqBaseline,
                isDetected = vr?.isDetected == true,
                isFerroso = vr?.metalType == "FERRO",
                threshold = vm.vectorProcessor.enterDetectionThreshold,
                exitThreshold = vm.vectorProcessor.exitDetectionThreshold,
                groundCenter = vm.vectorProcessor.groundCenter,
                vectorDistance = vr?.vectorDistance ?: 0f,
                relativeAngleDeg = vr?.relativeAngleDeg ?: 0f,
                modifier = Modifier.size(200.dp)
            )
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

        // METRI
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

        // =============================================================
        // INFO TILE
        // =============================================================
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            InfoTileWithTooltip("CONF", "${res.confidence.toInt()}%", "Affidabilità classificazione\n0% = incerto, 100% = certo")

            // TARGET PREDICTION (se disponibile)
            val currentPrediction = prediction
            if (currentPrediction != null && res.isLocked) {
                InfoTileWithTooltip(
                    "TARGET",
                    "${currentPrediction.fingerprint.name}",
                    "${currentPrediction.similarity.toInt()}%\nTarget riconosciuto\nTipo: ${currentPrediction.fingerprint.metalType}",
                    fontSize = 18.sp
                )
            } else {
                InfoTileWithTooltip("VDI", "${res.vdi}", "Identificazione metallo\n0-30: ferro, 31-60: leghe, 61-99: nobili", fontSize = 22.sp)
            }

            InfoTileWithTooltip("DEPTH", "${res.depth.toInt()}cm", "Stima profondità bersaglio\nScala 0-50cm\nIndicativo: dipende da suolo/target", fontSize = 22.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // CONTROLLI
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                var showDiscControls by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚙️ CONTROLLI", color = Color(0xFF888888), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = { showDiscControls = !showDiscControls },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64FFDA))
                    ) {
                        Text(
                            if (showDiscControls) "🔽 Nascondi Disc." else "▶️ Mostra Disc.",
                            fontSize = 10.sp
                        )
                    }
                }

                SafeSensitivitySlider(vm = vm, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Isteresi Fase: ${"%.1f".format(vm.phaseHysteresis)}°",
                        color = Color(0xFFAAAAAA), fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "ⓘ", color = Color(0xFF666666), fontSize = 10.sp,
                        modifier = Modifier.clickable { /* tooltip */ }
                    )
                }
                Slider(
                    value = vm.phaseHysteresis,
                    onValueChange = { vm.updatePhaseHysteresis(it) },
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

                if (showDiscControls) {
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
        }

        Spacer(modifier = Modifier.height(8.dp))

        // =============================================================
        // AUTO-TRACKING
        // =============================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2A1A))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🤖 AUTO-TRACKING", color = Color(0xFF4ECDC4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Off", color = if (!isAutoTracking) Color(0xFF4ECDC4) else Color(0xFF555555), fontSize = 10.sp)
                        Switch(
                            checked = isAutoTracking,
                            onCheckedChange = { vm.toggleAutoGroundTracking() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF4ECDC4),
                                uncheckedThumbColor = Color(0xFF888888)
                            )
                        )
                        Text("On", color = if (isAutoTracking) Color(0xFF4ECDC4) else Color(0xFF555555), fontSize = 10.sp)
                    }
                }

                if (isAutoTracking) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Qualità: ${"%.0f".format(trackingQuality * 100)}%",
                            color = when {
                                trackingQuality > 0.8f -> Color(0xFF4ECDC4)
                                trackingQuality > 0.5f -> Color(0xFFFFD740)
                                else -> Color(0xFFFF6666)
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Aggiornamenti: $trackerUpdates",
                            color = Color(0xFF888888),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        trackingStatus,
                        color = Color(0xFF888888),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { vm.resetAutoTracker() },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFD740))
                        ) {
                            Text("🔄 Reset Tracker", fontSize = 9.sp)
                        }
                    }

                    Text(
                        "💡 Il tracker aggiorna automaticamente baseline e asse quando non rileva bersagli",
                        color = Color(0xFF666666),
                        fontSize = 8.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } else {
                    Text(
                        "💡 Attiva per il tracking continuo del terreno in movimento",
                        color = Color(0xFF666666),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // =============================================================
        // CALIBRAZIONE — unico punto d'accesso a pumping/ground balance/
        // discriminazione avanzata, ora tutte raccolte nella schermata dedicata.
        // =============================================================
        Button(
            onClick = onNavigateToCalibration,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A2F), contentColor = Color(0xFF64FFDA))
        ) {
            Text("🔧 CALIBRAZIONE TERRENO", fontWeight = FontWeight.Bold)
        }
        Text(
            if (abs(groundAngleDeg) > 0.5f)
                "Terreno calibrato a ${"%.1f".format(groundAngleDeg)}° · tocca per pumping o bilanciamento manuale"
            else
                "Terreno non ancora calibrato · tocca per eseguire il pumping",
            color = Color(0xFF666666),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

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
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp
) {
    var showTooltip by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(70.dp).clickable { showTooltip = !showTooltip }
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 11.sp)
        Text(
            value,
            color = Color(0xFFE0E0E0),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("MIN (meno falsi positivi)", color = Color(0xFF666666), fontSize = 8.sp)
            Text("MAX (più portata)", color = Color(0xFF666666), fontSize = 8.sp)
        }
    }
}