package com.personal.pocketagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.pocketagent.automation.AgentAccessibilityService
import com.personal.pocketagent.domain.model.MacroStep
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroLibraryScreen(viewModel: MacroRecorderViewModel = viewModel()) {
    val isRecording by viewModel.isRecording.collectAsState()
    val savedMacros by viewModel.savedMacros.collectAsState()
    val stagedSteps by viewModel.stagedSteps.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var triggerInput by remember { mutableStateOf("") }
    var previewStatus by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom Macro Routines") },
                actions = {
                    IconButton(onClick = {
                        val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        exportLauncher.launch("pocket_agent_backup_$dateTag.json")
                    }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Export Backup")
                    }
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Import Backup")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Section 1: Backup / Restore Quick Action Buttons
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                            exportLauncher.launch("pocket_agent_backup_$dateTag.json")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Export JSON")
                    }

                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Import JSON")
                    }
                }
            }

            // Section 2: Live Screen Recorder Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (isRecording) "Recording live actions... Perform your routine across any app!"
                            else "Record or edit a sequence of actions tied to a voice trigger.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Button(
                            onClick = {
                                if (!isRecording && !AgentAccessibilityService.isRunning()) {
                                    previewStatus = "Please enable 'UI Tap' in Accessibility first!"
                                } else {
                                    viewModel.toggleRecording()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(if (isRecording) Icons.Default.Stop else Icons.Default.FiberSmartRecord, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRecording) "Stop Recording" else "Start Live Screen Recorder")
                        }

                        if (stagedSteps.isNotEmpty() && !isRecording) {
                            Text("Captured Steps (${stagedSteps.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                stagedSteps.forEachIndexed { index, step ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("${index + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(step.action, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                Text(
                                                    text = if (step.target.isNotBlank()) "Target: ${step.target}" else "Value: ${step.value}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            IconButton(onClick = { viewModel.removeStagedStep(index) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Step", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.testMacroSteps(stagedSteps) { previewStatus = it } },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Test Recorded Steps Live")
                            }

                            if (previewStatus.isNotBlank()) {
                                Text(previewStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }

                            OutlinedTextField(
                                value = triggerInput,
                                onValueChange = { triggerInput = it },
                                label = { Text("Voice Trigger (e.g. 'morning routine')") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    val trigger = triggerInput.ifBlank { "custom routine ${savedMacros.size + 1}" }
                                    viewModel.saveMacro(trigger, stagedSteps)
                                    triggerInput = ""
                                    previewStatus = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Save Routine")
                            }
                        }
                    }
                }
            }

            // Section 3: Header for Saved Routines
            item {
                Text(
                    text = "Saved Routines (${savedMacros.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Section 4: Expandable Saved Macros List
            items(savedMacros, key = { it.triggerPhrase }) { macro ->
                var isExpanded by remember { mutableStateOf(false) }
                val steps = remember(macro.stepsJson) {
                    try { Json.decodeFromString<List<MacroStep>>(macro.stepsJson) } catch (_: Exception) { emptyList() }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("\"${macro.triggerPhrase}\"", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${steps.size} step(s) • Tap arrow to view & delete steps", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.testMacroSteps(steps) { previewStatus = it } }) {
                                    Text("▶ Test")
                                }
                                IconButton(onClick = { isExpanded = !isExpanded }) {
                                    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Expand Steps")
                                }
                                IconButton(onClick = { viewModel.deleteMacro(macro.triggerPhrase) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Routine", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Divider()
                                steps.forEachIndexed { sIndex, sStep ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${sIndex + 1}.", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(22.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(sStep.action, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                text = if (sStep.target.isNotBlank()) "Target: ${sStep.target}" else "Value: ${sStep.value}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.removeStepFromSavedMacro(macro.triggerPhrase, sIndex) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Step", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
