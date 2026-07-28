package com.tetranova.prerex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class CommandType { ACTION, INT_RANGE, FLOAT_RANGE }

data class CommandDef(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val type: CommandType,
    val range: IntRange? = null,
    val rangeFloat: ClosedFloatingPointRange<Float>? = null,
    val unit: String = "",
    val isLocal: Boolean = false,
    val currentValueProvider: () -> Any = { 0 },
    val onValueChanged: ((Any) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(vm: DetectorViewModelV2) {
    val connectionStatus by vm.connectionStatus.collectAsState()
    val isConnected = connectionStatus is ConnectionStatus.Connected
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var selectedCommand by remember { mutableStateOf<CommandDef?>(null) }
    var inputValue by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    // 🔥 Stato per la calibrazione
    var showCalibrationPanel by remember { mutableStateOf(false) }
    var selectedMetalType by remember { mutableStateOf(DepthCalibrator.MetalType.FERRO) }
    var selectedDepth by remember { mutableStateOf(15f) }
    var targetName by remember { mutableStateOf("") }

    val depthCalibrator = vm.depthCalibrator
    val isCalibrating by vm.isDepthCalibrating.collectAsState()
    val fingerprintCount by vm.fingerprintCount.collectAsState()
    val isCalibrated by vm.isDepthCalibrated.collectAsState()

    val commands = remember {
        listOf(
            CommandDef(id = "freq", name = "Frequenza TX", description = "Frequenza di lavoro della bobina (1000-50000 Hz)",
                command = "SETFREQ", type = CommandType.INT_RANGE, range = 1000..50000, unit = "Hz",
                isLocal = false, currentValueProvider = { vm.params.frequency.toInt() },
                onValueChanged = { newVal -> vm.updateFrequency((newVal as Int).toFloat()) }),
            CommandDef(id = "duty", name = "Duty Cycle", description = "Percentuale duty cycle TX (10-90%)",
                command = "SETDUTY", type = CommandType.INT_RANGE, range = 10..90, unit = "%",
                isLocal = false, currentValueProvider = { vm.dutyCycle },
                onValueChanged = { newVal -> vm.updateDutyCycle(newVal as Int) }),
            CommandDef(id = "reboot", name = "Riavvia ESP32", description = "Riavvia il microcontrollore",
                command = "REBOOT", type = CommandType.ACTION, isLocal = false)
        )
    }

    if (showDialog && selectedCommand != null) {
        val cmd = selectedCommand!!
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(cmd.name) },
            text = {
                Column {
                    Text(cmd.description, style = MaterialTheme.typography.bodySmall)
                    if (cmd.type != CommandType.ACTION) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputValue,
                            onValueChange = {
                                inputValue = it
                                inputError = validateInput(it, cmd)
                            },
                            label = { Text(cmd.unit.ifEmpty { "Valore" }) },
                            isError = inputError != null,
                            supportingText = inputError?.let { { Text(it) } },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (cmd.type == CommandType.ACTION || inputError == null) {
                            executeCommand(cmd, inputValue, vm, isConnected, scope)
                            showDialog = false
                        }
                    },
                    enabled = cmd.type == CommandType.ACTION || (inputValue.isNotBlank() && inputError == null)
                ) { Text("Conferma") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Annulla") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // =============================================================
        // CARD INFORMAZIONI CONNESSIONE
        // =============================================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Comandi Metal Detector", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "🔌 USB",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isConnected) Color.Green else Color.Red,
                            modifier = Modifier.width(80.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Firmware: ${vm.params.transportMode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "🔋 ${"%.1f".format(vm.params.battery)}V | 📡 ${vm.params.frequency.toInt()}Hz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(if (isConnected) "✅" else "❌", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }

        // =============================================================
        // SEZIONE CALIBRAZIONE DEPTH
        // =============================================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🎯 Calibrazione Target",
                            color = Color(0xFFFFD740),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = { showCalibrationPanel = !showCalibrationPanel },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64FFDA))
                        ) {
                            Text(
                                if (showCalibrationPanel) "🔽 Nascondi" else "▶️ Mostra",
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Stato rapido
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "📊 ${fingerprintCount} impronte",  // 🔥 USO fingerprintCount
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (isCalibrated) {
                            Text(
                                text = "✅ Calibrato",
                                color = Color(0xFF64FFDA),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (isCalibrating) {
                            Text(
                                text = "⏳ In corso...",
                                color = Color(0xFFFFD740),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (showCalibrationPanel) {
                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = depthCalibrator.getCalibrationStatus(),
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        if (isCalibrated) {
                            Text(
                                text = "✅ Fit: R²=${"%.2f".format(depthCalibrator.getFitQuality())}",
                                color = Color(0xFF64FFDA),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = targetName,
                            onValueChange = { targetName = it },
                            label = { Text("Nome target (es. Moneta 1€)", fontSize = 10.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFD740),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        MetalTypeSelector(
                            selectedType = selectedMetalType,
                            depthCalibrator = depthCalibrator,
                            onTypeSelected = { selectedMetalType = it }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Profondità:", color = Color(0xFFAAAAAA), fontSize = 10.sp)
                            Text("${selectedDepth.toInt()}cm", color = Color(0xFF64FFDA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = selectedDepth,
                            onValueChange = { selectedDepth = it },
                            valueRange = 5f..50f,
                            steps = 9,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFFD740),
                                activeTrackColor = Color(0xFFFFD740)
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (targetName.isNotBlank()) {
                                        vm.startDepthCalibration(targetName, selectedMetalType, selectedDepth)
                                    }
                                },
                                enabled = !isCalibrating && targetName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E3A2F),
                                    contentColor = Color(0xFF64FFDA)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isCalibrating) "⏳ Attendere..." else "🔴 Calibra", fontSize = 10.sp)
                            }

                            Button(
                                onClick = { vm.completeDepthCalibration() },
                                enabled = isCalibrating,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2A1A1A),
                                    contentColor = Color(0xFFFF6B6B)
                                ),
                                modifier = Modifier.weight(0.5f)
                            ) {
                                Text("✅ Fine", fontSize = 10.sp)
                            }

                            Button(
                                onClick = { vm.cancelDepthCalibration() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF3A1A1A),
                                    contentColor = Color(0xFFFF6B6B)
                                ),
                                modifier = Modifier.weight(0.5f)
                            ) {
                                Text("✖ Interrompi", fontSize = 10.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { vm.clearAllFingerprints() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF3A1A1A),
                                    contentColor = Color(0xFFFF6666)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🧹 Cancella impronte ($fingerprintCount)", fontSize = 10.sp)  // 🔥 USO fingerprintCount
                            }

                            Button(
                                onClick = { vm.saveFingerprintsToFile() },
                                enabled = fingerprintCount > 0,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1A3A2F),
                                    contentColor = Color(0xFF64FFDA)
                                ),
                                modifier = Modifier.weight(0.6f)
                            ) {
                                Text("💾 Salva", fontSize = 10.sp)
                            }
                        }

                        if (fingerprintCount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "📋 Ultime impronte:",
                                color = Color(0xFF888888),
                                fontSize = 9.sp
                            )
                            val fingerprints = depthCalibrator.getFingerprints()
                            val lastFingerprints = fingerprints.takeLast(3)
                            for (fp in lastFingerprints) {
                                Text(
                                    text = "  ${fp.metalType.getEmoji()} ${fp.name} @ ${fp.depthCm.toInt()}cm (VDI:${fp.vdi})",
                                    color = Color(0xFF666666),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // =============================================================
        // COMANDI ESP32
        // =============================================================
        items(commands) { cmd ->
            CommandCard(
                command = cmd,
                isConnected = isConnected,
                onAction = {
                    selectedCommand = cmd
                    inputValue = when (val c = cmd.currentValueProvider()) {
                        is Float -> c.toString()
                        is Int -> c.toString()
                        else -> ""
                    }
                    inputError = validateInput(inputValue, cmd)
                    showDialog = true
                }
            )
        }
    }
}


private fun validateInput(value: String, cmd: CommandDef): String? {
    if (value.isBlank()) return "Inserire un valore"
    return when (cmd.type) {
        CommandType.INT_RANGE -> {
            val intVal = value.toIntOrNull()
            val range = cmd.range
            if (intVal == null) "Deve essere un numero intero"
            else if (range != null && intVal !in range) "Valore fuori range (${range.first}-${range.last})"
            else null
        }
        CommandType.FLOAT_RANGE -> {
            val floatVal = value.toFloatOrNull()
            val range = cmd.rangeFloat
            if (floatVal == null) "Deve essere un numero"
            else if (range != null && floatVal !in range) "Valore fuori range (${range.start}-${range.endInclusive})"
            else null
        }
        else -> null
    }
}

private fun executeCommand(cmd: CommandDef, inputValue: String?, vm: DetectorViewModelV2, isConnected: Boolean, scope: CoroutineScope) {
    when (cmd.type) {
        CommandType.ACTION -> {
            if (cmd.isLocal) {
                cmd.onValueChanged?.invoke(true)
                vm.console.add(0, "Azione locale: ${cmd.name}")
            } else if (isConnected) {
                scope.launch { vm.usb.sendCommand(cmd.command) }
                vm.console.add(0, "➡️ Inviato: ${cmd.command}")
            } else vm.console.add(0, "❌ Non connesso")
        }
        CommandType.INT_RANGE -> {
            val value = inputValue?.toIntOrNull() ?: return
            if (cmd.isLocal) {
                cmd.onValueChanged?.invoke(value)
            } else if (isConnected) {
                scope.launch { vm.usb.sendCommand("${cmd.command} $value") }
                cmd.onValueChanged?.invoke(value)
                vm.console.add(0, "➡️ Inviato: ${cmd.command} $value")
            } else vm.console.add(0, "❌ Non connesso")
        }
        CommandType.FLOAT_RANGE -> {
            val value = inputValue?.toFloatOrNull() ?: return
            if (cmd.isLocal) {
                cmd.onValueChanged?.invoke(value)
            } else if (isConnected) {
                scope.launch { vm.usb.sendCommand("${cmd.command} $value") }
                cmd.onValueChanged?.invoke(value)
                vm.console.add(0, "➡️ Inviato: ${cmd.command} $value")
            } else vm.console.add(0, "❌ Non connesso")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandCard(command: CommandDef, isConnected: Boolean, onAction: () -> Unit) {
    val isEspCommand = !command.isLocal
    val isEnabled = command.isLocal || isConnected
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (isEnabled) onAction() },
        enabled = isEnabled,
        colors = CardDefaults.cardColors(
            containerColor = if (isEspCommand) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = command.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    if (isEspCommand) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("ESP") },
                            leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        )
                    }
                }
                Text(text = command.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (command.type != CommandType.ACTION) {
                    Text(text = "Corrente: ${command.currentValueProvider()} ${command.unit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.Default.Settings, null, tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}