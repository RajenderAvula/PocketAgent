package com.personal.pocketagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Backup & Restore Quick Action Buttons
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

            // Live Screen Recorder Card
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
                        onClick = { viewModel.toggleRecording() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (isRecording) Icons.Default.Stop else Icons.Default.FiberSmartRecord, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRecording) "Stop Recording" else "Start Live Screen Recorder")
                    }

                    // Newly Captured / Staged Steps Editor & Preview Area
                    if (stagedSteps.isNotEmpty() && !isRecording) {
                        Text("Review Recorded Steps (${stagedSteps.size})", style = MaterialTheme.typography.titleSmall)

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
                                            Icon(Icons.Default.Delete, contentDescription = "Remove Step", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { viewModel.testMacroSteps(stagedSteps) { previewStatus = it } },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("▶ Test / Preview Recorded Steps Live")
                        }

                        if (previewStatus.isNotBlank()) {
                            Text(previewStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }

                        OutlinedTextField(
                            value = triggerInput,
                            onValueChange = { triggerInput = it },
                            label = { Text("Voice Trigger (e.g. 'clean routine')") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                viewModel.saveMacro(triggerInput, stagedSteps)
                                triggerInput = ""
                                previewStatus = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Verified Routine")
                        }
                    }
                }
            }

            Text("Saved Routines (${savedMacros.size})", style = MaterialTheme.typography.titleMedium)

            // Saved Macros List with Live Test Run and Delete Options
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(savedMacros, key = { it.triggerPhrase }) { macro ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("\"${macro.triggerPhrase}\"", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Saved routine • Offline ready", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        try {
                                            val steps = Json.decodeFromString<List<com.personal.pocketagent.domain.model.MacroStep>>(macro.stepsJson)
                                            viewModel.testMacroSteps(steps) { status ->
                                                previewStatus = status
                                            }
                                        } catch (_: Exception) {}
                                    }) {
                                        Text("▶ Test")
                                    }

                                    IconButton(onClick = { viewModel.deleteMacro(macro.triggerPhrase) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Routine", tint = MaterialTheme.colorScheme.error)
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
