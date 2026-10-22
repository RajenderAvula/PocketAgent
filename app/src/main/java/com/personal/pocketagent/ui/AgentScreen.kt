package com.personal.pocketagent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.pocketagent.data.document.DocumentReader
import com.personal.pocketagent.data.local.AgentPrefs
import com.personal.pocketagent.data.network.DownloadState
import com.personal.pocketagent.playback.DocumentPlaybackController
import com.personal.pocketagent.system.AgentTileService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(viewModel: AgentViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val messages by viewModel.messages.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val wakeEnabled by viewModel.wakeServiceEnabled.collectAsState()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
    val overlayEnabled by viewModel.overlayEnabled.collectAsState()
    val speechRate by viewModel.speechRate.collectAsState()
    val customWakeWords by viewModel.customWakeWords.collectAsState()
    val activePdfUri by viewModel.activePdfUri.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var wakeWordInput by remember(customWakeWords) { mutableStateOf(customWakeWords) }
    var excludedWordsInput by remember { mutableStateOf(AgentPrefs.getExcludedWordsRaw(context)) }
    var showWakeConfig by remember { mutableStateOf(false) }
    var isReadingDoc by remember { mutableStateOf(false) }

    val documentReader = remember { DocumentReader(context) }
    val playbackController = remember { DocumentPlaybackController.getInstance(context) }

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isReadingDoc = true
            viewModel.setActivePdfUri(uri)
            lifecycleOwner.lifecycleScope.launch {
                val result = documentReader.extractText(uri)
                isReadingDoc = false
                result.fold(
                    onSuccess = { extractedText ->
                        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
                        playbackController.loadDocument(
                            title = fileName,
                            rawText = extractedText,
                            startPosition = 0,
                            uri = uri
                        )
                        playbackController.play()
                    },
                    onFailure = { error ->
                        viewModel.submitQuery("Document error: ${error.localizedMessage}")
                    }
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.toggleDictation() }

    val wakePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.RECORD_AUDIO] == true) viewModel.toggleWakeWordService()
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.RECORD_AUDIO] == true) viewModel.toggleOverlayService()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("PocketAgent Pro") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        FilterChip(
                            selected = wakeEnabled,
                            onClick = {
                                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                wakePermissionLauncher.launch(perms.toTypedArray())
                            },
                            label = { Text(if (wakeEnabled) "Wake: ON" else "Hey Agent") },
                            leadingIcon = { Icon(Icons.Default.Hearing, contentDescription = null) }
                        )

                        FilterChip(
                            selected = accessibilityEnabled,
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            label = { Text(if (accessibilityEnabled) "UI Tap: ON" else "UI Tap") },
                            leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null) }
                        )

                        FilterChip(
                            selected = overlayEnabled,
                            onClick = {
                                if (!Settings.canDrawOverlays(context)) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                } else {
                                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    overlayPermissionLauncher.launch(perms.toTypedArray())
                                }
                            },
                            label = { Text(if (overlayEnabled) "Bubble: ON" else "Bubble") },
                            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) }
                        )
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Assistant Speech Speed: ${"%.2f".format(speechRate)}x", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = speechRate,
                            onValueChange = { viewModel.updateSpeechRate(it) },
                            valueRange = 0.5f..1.5f,
                            steps = 9
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Triggers: ${customWakeWords.split(',').take(2).joinToString(", ") { it.trim() }}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        TextButton(onClick = { showWakeConfig = !showWakeConfig }) {
                            Text(if (showWakeConfig) "Hide" else "Customize")
                        }
                    }

                    if (showWakeConfig) {
                        OutlinedTextField(
                            value = wakeWordInput,
                            onValueChange = { wakeWordInput = it },
                            label = { Text("Wake Phrases (comma-separated)") },
                            placeholder = { Text("hey man, dude, man, jarvis") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = excludedWordsInput,
                            onValueChange = { excludedWordsInput = it },
                            label = { Text("Exclude PDF Words (comma-separated, e.g. [*, http*, Page *)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                viewModel.updateCustomWakeWords(wakeWordInput)
                                AgentPrefs.setExcludedWordsRaw(context, excludedWordsInput)
                                showWakeConfig = false
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Save Preferences")
                        }
                    }

                    when (val ds = downloadState) {
                        is DownloadState.NotDownloaded -> {
                            OutlinedButton(onClick = { viewModel.startOfflineModelDownload() }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Download Offline Gemma 2B Model")
                            }
                        }
                        is DownloadState.Downloading -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Downloading Offline LLM: ${ds.downloadedMb} MB / ${ds.totalMb} MB (${ds.progressPercent}%)", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(progress = { ds.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        is DownloadState.Ready -> {
                            Text("✓ Offline Gemma 2B Model Ready on Device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        is DownloadState.Error -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Error: ${ds.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.startOfflineModelDownload() }) { Text("Retry") }
                            }
                        }
                    }

                    TextButton(
                        onClick = { AgentTileService.promptAddTileToQuickSettings(context) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Add Tile to Quick Settings", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Document Player Card with untruncated scrolling and Close button
            DocumentPlayerCard(
                controller = playbackController,
                onOpenFullscreen = {
                    val uri = playbackController.playerState.value.docUri ?: activePdfUri
                    if (uri != null) viewModel.setActivePdfUri(uri)
                }
            )

            // Fullscreen PDF Viewer Dialog with Zoom, Annotate & Line Jump
            activePdfUri?.let { uri ->
                PdfFullViewerDialog(
                    pdfUri = uri,
                    controller = playbackController,
                    onDismiss = { viewModel.setActivePdfUri(null) }
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
                    ) {
                        if (!msg.isUser && msg.modeBadge.isNotEmpty()) {
                            AssistChip(onClick = {}, label = { Text(msg.modeBadge, style = MaterialTheme.typography.labelSmall) })
                        }
                        Surface(
                            color = if (msg.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(msg.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        docPickerLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "text/plain"
                            )
                        )
                    }
                ) {
                    if (isReadingDoc) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.AttachFile, contentDescription = "Read Document")
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isListening) "Listening..." else "Message or dictate...") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(if (isListening) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Dictate")
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        viewModel.submitQuery(inputText, speakReply = false)
                        inputText = ""
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
