package com.tetranova.prerex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    vm: DetectorViewModelV2,
    onBack: () -> Unit
) {
    // STATI UI
    var selectedOption by remember { mutableStateOf<CalibrationOption?>(null) }
    var manualAngle by remember { mutableFloatStateOf(vm.manualGroundAngleDeg) }
    var displayValue by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf("") }
    var isAirRunning by remember { mutableStateOf(false) }

    // STATI OSSERVABILI
    val isAirCalibrated by vm.hasAirCalibration.collectAsState()
    val isPumpingDone by vm.isPumpingDone.collectAsState()
    val isAutoTracking by vm.isAutoGroundTracking.collectAsState()
    val autoTrackingState by vm.autoTrackingState.collectAsState()
    val displayAutoValue by vm.displayAutoValue.collectAsState()
    val trackingQuality by vm.trackingQuality.collectAsState()
    val isGroundCalibrating = vm.isGroundCalibrating
    val groundAxisDeg = vm.vectorProcessor.groundAxisDeg
    val enterThreshold = vm.vectorProcessor.enterDetectionThreshold
    val tangentialRMS = vm.vectorProcessor.tangentialRMS
    val baselineVector = vm.vectorProcessor.baselineVector

    // STATI LOCALI
    var isPumpingActive by remember { mutableStateOf(false) }
    var isPumpingComplete by remember { mutableStateOf(false) }
    var isManualActive by remember { mutableStateOf(false) }
    var isAirComplete by remember { mutableStateOf(false) }

    // =============================================================
    // LOGICA DI SELEZIONE OPZIONE
    // =============================================================
    fun selectOption(option: CalibrationOption) {
        when (option) {
            CalibrationOption.AIR_BALANCE -> {
                if (isAutoTracking) {
                    vm.toggleAutoGroundTracking()
                }
                if (isPumpingActive) {
                    vm.finishGroundCalibration()
                    isPumpingActive = false
                    isPumpingComplete = false
                }
                isManualActive = false
                isAirComplete = false
                isAirRunning = false
                displayValue = 0f

                if (isAirCalibrated) {
                    vm.resetAirCalibration()
                }

                isAirRunning = true
                selectedOption = option
                statusMessage = "⏳ Calibrazione in corso..."
                vm.performCalibration()
            }

            CalibrationOption.GROUND_AUTO -> {
                if (!isAirCalibrated) {
                    statusMessage = "⚠️ Esegui prima AIR BALANCE"
                    return
                }
                if (!isPumpingDone) {
                    statusMessage = "⚠️ Esegui prima il PUMPING!"
                    return
                }
                isManualActive = false
                isPumpingActive = false
                isPumpingComplete = false

                vm.toggleAutoGroundTracking()
                selectedOption = option
            }

            CalibrationOption.GROUND_PUMPING -> {
                if (!isAirCalibrated) {
                    statusMessage = "⚠️ Esegui prima AIR BALANCE"
                    return
                }
                isManualActive = false
                if (isAutoTracking) {
                    vm.toggleAutoGroundTracking()
                }
                selectedOption = option
                if (!isPumpingActive && !isPumpingComplete) {
                    isPumpingActive = true
                    isPumpingComplete = false
                    statusMessage = "🔄 Pompa la bobina sul terreno..."
                    displayValue = 0f
                    vm.startGroundCalibration()
                } else if (isPumpingActive) {
                    vm.finishGroundCalibration()
                    isPumpingActive = false
                    // FIX: stesso bug della LaunchedEffect - controlla l'esito vero
                    // (isPumpingDone) invece di dichiarare sempre successo.
                    if (isPumpingDone) {
                        isPumpingComplete = true
                        displayValue = groundAxisDeg
                        statusMessage = "✅ Pumping completato! Auto Tracking disponibile"
                    } else {
                        isPumpingComplete = false
                        displayValue = 0f
                        statusMessage = "❌ Pumping fallito - ripeti il movimento"
                    }
                } else if (isPumpingComplete) {
                    isPumpingActive = true
                    isPumpingComplete = false
                    statusMessage = "🔄 Pompa la bobina sul terreno..."
                    displayValue = 0f
                    vm.startGroundCalibration()
                }
            }

            CalibrationOption.GROUND_MANUAL -> {
                if (!isAirCalibrated) {
                    statusMessage = "⚠️ Esegui prima AIR BALANCE"
                    return
                }
                if (!isPumpingDone) {
                    statusMessage = "⚠️ Esegui prima il PUMPING!"
                    return
                }
                if (isAutoTracking) {
                    vm.toggleAutoGroundTracking()
                }
                if (isPumpingActive) {
                    vm.finishGroundCalibration()
                    isPumpingActive = false
                    // FIX: stesso bug — controlla l'esito vero invece di dichiarare
                    // sempre successo.
                    isPumpingComplete = isPumpingDone
                }
                selectedOption = option
                isManualActive = true
                manualAngle = vm.manualGroundAngleDeg
                displayValue = vm.manualGroundAngleDeg
                statusMessage = "✋ Regola il valore con i pulsanti +/-"
            }
        }
    }

    // =============================================================
    // EFFECT: AGGIORNAMENTO IN TEMPO REALE
    // =============================================================

    // Aggiorna display per Pumping
    LaunchedEffect(isGroundCalibrating, vm.pumpingDiagnostics) {
        if (selectedOption == CalibrationOption.GROUND_PUMPING && isGroundCalibrating) {
            val diag = vm.pumpingDiagnostics
            statusMessage = if (diag == null) {
                "🔄 Pompa la bobina sul terreno..."
            } else {
                val pcaText = if (diag.provisionalPcaQuality >= 0f) "%.1f".format(diag.provisionalPcaQuality) else "…"
                "🔄 Campioni: ${diag.sampleCount}/${diag.samplesRequired} · PCA: $pcaText · Passaggi: ${diag.oscillationCount}"
            }
        }
    }

    // Aggiorna display per Auto Tracking - usa lo stato di transizione
    LaunchedEffect(autoTrackingState, displayAutoValue, trackingQuality) {
        if (selectedOption == CalibrationOption.GROUND_AUTO) {
            when (autoTrackingState) {
                AutoTrackingState.IDLE -> {
                    displayValue = 0f
                    statusMessage = "⏹ Auto-Tracking disattivato"
                }
                AutoTrackingState.ACTIVATING -> {
                    displayValue = 0f
                    statusMessage = "⏳ Attivazione in corso..."
                }
                AutoTrackingState.ACTIVE -> {
                    displayValue = displayAutoValue
                    statusMessage = "🤖 Tracking: ${"%.1f".format(displayAutoValue)}° | Qualità: ${"%.0f".format(trackingQuality * 100)}%"
                }
                AutoTrackingState.DEACTIVATING -> {
                    displayValue = displayAutoValue
                    statusMessage = "⏳ Disattivazione in corso..."
                }
            }
        }
    }

    // EFFECT: Completamento Air Balance
    LaunchedEffect(isAirCalibrated) {
        if (isAirCalibrated && isAirRunning) {
            isAirRunning = false
            isAirComplete = true
            displayValue = enterThreshold
            statusMessage = "✅ Air Balance completata! Soglia: ${"%.3f".format(enterThreshold)}"
        }
    }

    // EFFECT: Sync pumping state con ViewModel
    LaunchedEffect(isGroundCalibrating) {
        if (!isGroundCalibrating && isPumpingActive) {
            isPumpingActive = false
            // FIX: isPumpingDone è il vero esito (impostato dal ViewModel in base al
            // risultato reale di computeCalibration()) — prima si dichiarava sempre
            // "completato con successo" a prescindere, anche con 0 campioni validi.
            if (isPumpingDone) {
                isPumpingComplete = true
                displayValue = groundAxisDeg
                statusMessage = "✅ Pumping completato - Auto Tracking disponibile"
            } else {
                isPumpingComplete = false
                displayValue = 0f
                statusMessage = "❌ Pumping fallito - ripeti il movimento"
            }
        }
    }

    // =============================================================
    // UI
    // =============================================================
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrazione") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Air Balance
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAirCalibrated) Color(0xFF0A2A1A) else Color(0xFF2A1A1A)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isAirCalibrated) "✅ Air Balance: OK" else "❌ Air Balance: Necessaria",
                        color = if (isAirCalibrated) Color(0xFF4ECDC4) else Color(0xFFFF6B6B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (isAirCalibrated) {
                        Text(
                            "Soglia: ${"%.3f".format(enterThreshold)}",
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Pumping
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isPumpingDone) Color(0xFF0A2A1A) else Color(0xFF2A1A1A)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isPumpingDone) "✅ Pumping: Completato" else "❌ Pumping: Necessario",
                        color = if (isPumpingDone) Color(0xFF4ECDC4) else Color(0xFFFF6B6B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (isPumpingDone) {
                        Text(
                            "Angolo: ${"%.1f".format(groundAxisDeg)}°",
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Seleziona il tipo di calibrazione:",
                color = Color(0xFFAAAAAA),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Griglia 2x2
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CalibrationOptionCard(
                        option = CalibrationOption.AIR_BALANCE,
                        isSelected = selectedOption == CalibrationOption.AIR_BALANCE,
                        isEnabled = true,
                        isActive = isAirCalibrated,
                        status = if (isAirCalibrated) "✅" else "",
                        onClick = { selectOption(CalibrationOption.AIR_BALANCE) },
                        modifier = Modifier.weight(1f)
                    )
                    CalibrationOptionCard(
                        option = CalibrationOption.GROUND_AUTO,
                        isSelected = selectedOption == CalibrationOption.GROUND_AUTO,
                        isEnabled = isAirCalibrated && isPumpingDone,
                        isActive = isAutoTracking || autoTrackingState == AutoTrackingState.ACTIVATING,
                        status = when {
                            autoTrackingState == AutoTrackingState.ACTIVE -> "🟢"
                            autoTrackingState == AutoTrackingState.ACTIVATING -> "⏳"
                            autoTrackingState == AutoTrackingState.DEACTIVATING -> "⏳"
                            !isPumpingDone -> "🔒"
                            else -> ""
                        },
                        onClick = { selectOption(CalibrationOption.GROUND_AUTO) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CalibrationOptionCard(
                        option = CalibrationOption.GROUND_PUMPING,
                        isSelected = selectedOption == CalibrationOption.GROUND_PUMPING,
                        isEnabled = isAirCalibrated,
                        isActive = isPumpingActive,
                        status = when {
                            isPumpingComplete -> "✅"
                            isPumpingActive -> "🔄"
                            else -> ""
                        },
                        onClick = { selectOption(CalibrationOption.GROUND_PUMPING) },
                        modifier = Modifier.weight(1f)
                    )
                    CalibrationOptionCard(
                        option = CalibrationOption.GROUND_MANUAL,
                        isSelected = selectedOption == CalibrationOption.GROUND_MANUAL,
                        isEnabled = isAirCalibrated && isPumpingDone,
                        isActive = isManualActive,
                        status = if (isManualActive) "✋" else if (!isPumpingDone) "🔒" else "",
                        onClick = { selectOption(CalibrationOption.GROUND_MANUAL) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pannello di controllo
            if (selectedOption != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = selectedOption!!.displayName,
                            color = Color(0xFF64FFDA),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Valore grande
                        when {
                            selectedOption == CalibrationOption.AIR_BALANCE && isAirComplete && isAirCalibrated -> {
                                Text(
                                    text = "✅ COMPLETATA",
                                    color = Color(0xFF4ECDC4),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Soglia: ${"%.3f".format(displayValue)}",
                                    color = Color(0xFFFFD740),
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            selectedOption == CalibrationOption.AIR_BALANCE && isAirRunning -> {
                                Text(
                                    text = "⏳ IN CORSO...",
                                    color = Color(0xFFFFD740),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            selectedOption == CalibrationOption.AIR_BALANCE && !isAirCalibrated -> {
                                Text(
                                    text = "---",
                                    color = Color(0xFF444444),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            // AUTO TRACKING CON STATO DI TRANSIZIONE
                            selectedOption == CalibrationOption.GROUND_AUTO -> {
                                when (autoTrackingState) {
                                    AutoTrackingState.IDLE -> {
                                        Text(
                                            text = "---",
                                            color = Color(0xFF444444),
                                            fontSize = 48.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    AutoTrackingState.ACTIVATING -> {
                                        Text(
                                            text = "⏳",
                                            color = Color(0xFFFFD740),
                                            fontSize = 48.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Attivazione...",
                                            color = Color(0xFF888888),
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    AutoTrackingState.ACTIVE -> {
                                        Text(
                                            text = "${"%.1f".format(displayValue)}°",
                                            color = Color(0xFF4ECDC4),
                                            fontSize = 48.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Qualità: ${"%.0f".format(trackingQuality * 100)}%",
                                            color = Color(0xFF888888),
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    AutoTrackingState.DEACTIVATING -> {
                                        Text(
                                            text = "⏳",
                                            color = Color(0xFFFF6B6B),
                                            fontSize = 48.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Disattivazione...",
                                            color = Color(0xFF888888),
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            // PUMPING
                            selectedOption == CalibrationOption.GROUND_PUMPING && isPumpingActive -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IQPlotWidget(
                                        current = vm.pumpingCurrentSample,
                                        baseline = vm.pumpingBaseline,
                                        isDetected = true,
                                        isFerroso = !vm.pumpingDotAccepted,
                                        threshold = (vm.pumpingDiagnostics?.currentAmplitude ?: 15f) * 0.3f,
                                        exitThreshold = (vm.pumpingDiagnostics?.currentAmplitude ?: 15f) * 0.15f,
                                        groundCenter = IQVector.ZERO,
                                        vectorDistance = vm.pumpingDiagnostics?.currentAmplitude ?: 0f,
                                        modifier = Modifier.size(200.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        Text(
                                            "✅ ${vm.pumpingAcceptedCount}/${vm.groundCalibrator.minSamplesRequired}",
                                            color = Color(0xFF66FFCC), fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            "❌ ${vm.pumpingRejectedCount}",
                                            color = Color(0xFFFF6666), fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            selectedOption == CalibrationOption.GROUND_PUMPING && isPumpingComplete -> {
                                Text(
                                    text = "${"%.1f".format(displayValue)}°",
                                    color = Color(0xFFFFD740),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "✅ Completato - Auto Tracking disponibile",
                                    color = Color(0xFF4ECDC4),
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            selectedOption == CalibrationOption.GROUND_PUMPING && !isPumpingActive && !isPumpingComplete -> {
                                Text(
                                    text = "---",
                                    color = Color(0xFF444444),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            // MANUALE
                            selectedOption == CalibrationOption.GROUND_MANUAL && isManualActive -> {
                                Text(
                                    text = "${"%.1f".format(displayValue)}°",
                                    color = Color(0xFFFFD740),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            manualAngle = (manualAngle - 1f).coerceIn(-45f, 45f)
                                            displayValue = manualAngle
                                            vm.setManualGroundAngle(manualAngle)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF3A1A1A),
                                            contentColor = Color(0xFFFF6666)
                                        ),
                                        modifier = Modifier.size(60.dp, 60.dp),
                                        shape = RoundedCornerShape(30.dp)
                                    ) {
                                        Text("-", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = {
                                            manualAngle = (manualAngle + 1f).coerceIn(-45f, 45f)
                                            displayValue = manualAngle
                                            vm.setManualGroundAngle(manualAngle)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF1A3A2F),
                                            contentColor = Color(0xFF64FFDA)
                                        ),
                                        modifier = Modifier.size(60.dp, 60.dp),
                                        shape = RoundedCornerShape(30.dp)
                                    ) {
                                        Text("+", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    text = "Residuo: ${"%.4f".format(tangentialRMS)}",
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            selectedOption == CalibrationOption.GROUND_MANUAL && !isManualActive -> {
                                Text(
                                    text = "---",
                                    color = Color(0xFF444444),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            !isAirCalibrated && selectedOption != CalibrationOption.AIR_BALANCE -> {
                                Text(
                                    text = "🔒",
                                    color = Color(0xFF444444),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            else -> {
                                Text(
                                    text = "---",
                                    color = Color(0xFF444444),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = statusMessage,
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Pulsante Reset
                        if (selectedOption != CalibrationOption.AIR_BALANCE && isAirCalibrated) {
                            Button(
                                onClick = {
                                    when (selectedOption) {
                                        CalibrationOption.GROUND_AUTO -> {
                                            if (isAutoTracking) {
                                                vm.resetAutoTracker()
                                                statusMessage = "🔄 Tracker resettato"
                                            } else {
                                                statusMessage = "⚠️ Auto-Tracking non attivo"
                                            }
                                        }
                                        CalibrationOption.GROUND_PUMPING -> {
                                            if (isPumpingActive) {
                                                vm.finishGroundCalibration()
                                                isPumpingActive = false
                                                // FIX: stesso bug — controlla l'esito vero
                                                // invece di dichiarare sempre successo.
                                                if (isPumpingDone) {
                                                    isPumpingComplete = true
                                                    displayValue = groundAxisDeg
                                                    statusMessage = "✅ Pumping completato! Auto Tracking disponibile"
                                                } else {
                                                    isPumpingComplete = false
                                                    displayValue = 0f
                                                    statusMessage = "❌ Pumping fallito - ripeti il movimento"
                                                }
                                            } else {
                                                vm.resetGroundBalance()
                                                isPumpingActive = false
                                                isPumpingComplete = false
                                                displayValue = 0f
                                                statusMessage = "↺ Reset effettuato"
                                            }
                                        }
                                        CalibrationOption.GROUND_MANUAL -> {
                                            manualAngle = 0f
                                            displayValue = 0f
                                            vm.setManualGroundAngle(0f)
                                            statusMessage = "↺ Resettato a 0°"
                                        }
                                        else -> {}
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2A1A1A),
                                    contentColor = Color(0xFFFF6666)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    when (selectedOption) {
                                        CalibrationOption.GROUND_AUTO -> if (isAutoTracking) "↺ RESET TRACKER" else "↺ RESET"
                                        CalibrationOption.GROUND_PUMPING -> if (isPumpingActive) "⏹ STOP" else "↺ RESET"
                                        CalibrationOption.GROUND_MANUAL -> "↺ RESET A 0°"
                                        else -> "↺ RESET"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stato attuale
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "📊 STATO ATTUALE",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Angolo:", color = Color(0xFF666666), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "${"%.1f".format(groundAxisDeg)}°",
                            color = Color(0xFF64FFDA),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Baseline I/Q:", color = Color(0xFF666666), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "I=${"%.3f".format(baselineVector.i)} Q=${"%.3f".format(baselineVector.q)}",
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Soglia:", color = Color(0xFF666666), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "%.3f".format(enterThreshold),
                            color = Color(0xFFFFD740),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Residuo:", color = Color(0xFF666666), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "%.4f".format(tangentialRMS),
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (isPumpingDone) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Auto Tracking:", color = Color(0xFF666666), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                if (isAutoTracking) "✅ Attivo" else "⏹ Disattivato",
                                color = if (isAutoTracking) Color(0xFF4ECDC4) else Color(0xFF888888),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================
// ENUM E COMPONENTI
// =============================================================

enum class CalibrationOption(
    val displayName: String,
    val icon: String,
    val description: String
) {
    AIR_BALANCE("Air Balance", "📡", "Calibrazione in aria (prerequisito)"),
    GROUND_AUTO("Auto Tracking", "🤖", "Tracking automatico continuo"),
    GROUND_PUMPING("Pumping", "🔄", "Calibrazione con pompaggio"),
    GROUND_MANUAL("Manuale", "✋", "Regolazione manuale")
}

@Composable
fun CalibrationOptionCard(
    option: CalibrationOption,
    isSelected: Boolean,
    isEnabled: Boolean,
    isActive: Boolean,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        !isEnabled -> Color(0xFF1A1A1A)
        isActive -> Color(0xFF1A3A2F)
        isSelected -> Color(0xFF2A3A2A)
        else -> Color(0xFF141414)
    }

    Card(
        modifier = modifier
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = isEnabled) { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(
            width = if (isSelected || isActive) 2.dp else 1.dp,
            color = when {
                isActive -> Color(0xFF4ECDC4)
                isSelected -> Color(0xFF4ECDC4)
                !isEnabled -> Color(0xFF333333)
                else -> Color(0xFF222222)
            }
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = if (isEnabled) option.icon else "🔒", fontSize = 20.sp)
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            Text(
                text = option.displayName,
                color = if (isEnabled) Color(0xFFE0E0E0) else Color(0xFF444444),
                fontSize = 11.sp,
                fontWeight = if (isSelected || isActive) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            Text(
                text = option.description,
                color = if (isEnabled) Color(0xFF666666) else Color(0xFF333333),
                fontSize = 8.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}