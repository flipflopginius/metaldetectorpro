package com.tetranova.prerex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalTypeSelector(
    selectedType: DepthCalibrator.MetalType,
    depthCalibrator: DepthCalibrator,
    onTypeSelected: (DepthCalibrator.MetalType) -> Unit
) {
    Column {
        Text("Tipo metallo:", color = Color(0xFFAAAAAA), fontSize = 10.sp)

        var expanded by remember { mutableStateOf(false) }

        val types = remember {
            listOf(
                DepthCalibrator.MetalType.FERRO,
                DepthCalibrator.MetalType.RAME,
                DepthCalibrator.MetalType.ALLUMINIO,
                DepthCalibrator.MetalType.STAGNO,
                DepthCalibrator.MetalType.ARGENTO,
                DepthCalibrator.MetalType.ORO,
                DepthCalibrator.MetalType.ALTRO
            )
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            TextField(
                value = "${selectedType.getEmoji()} ${depthCalibrator.getMetalTypeName(selectedType)}",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF2A2A3E),
                    unfocusedContainerColor = Color(0xFF2A2A3E)
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                types.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${type.getEmoji()} ${depthCalibrator.getMetalTypeName(type)}",
                                fontSize = 12.sp
                            )
                        },
                        onClick = {
                            onTypeSelected(type)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}