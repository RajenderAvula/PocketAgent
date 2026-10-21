package com.personal.pocketagent.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.personal.pocketagent.MainActivity
import com.personal.pocketagent.automation.AgentAccessibilityService
import com.personal.pocketagent.automation.OverlayStateController
import com.personal.pocketagent.data.local.AgentDatabase
import com.personal.pocketagent.data.local.AgentPrefs
import com.personal.pocketagent.data.local.NoteEntity
import com.personal.pocketagent.data.network.NetworkMonitor
import com.personal.pocketagent.domain.engine.CloudLlmEngine
import com.personal.pocketagent.domain.engine.HybridAgentRouter
import com.personal.pocketagent.domain.engine.OfflineLlmEngine
import com.personal.pocketagent.domain.tools.ToolExecutor
import com.personal.pocketagent.system.AgentTileService
import com.personal.pocketagent.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {

    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private val lifecycleOwner = ComposeOverlayLifecycleOwner()

    private lateinit var db: AgentDatabase
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var router: HybridAgentRouter
    private lateinit var voiceController: VoiceController

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AgentPrefs.setBubbleEnabled(this, true)
        AgentTileService.requestTileSync(this)

        db = AgentDatabase.get(applicationContext)
        networkMonitor = NetworkMonitor(applicationContext)
        router = HybridAgentRouter(networkMonitor, CloudLlmEngine(), OfflineLlmEngine(applicationContext))
        toolExecutor = ToolExecutor(applicationContext, db.noteDao(), router)

        voiceController = VoiceController(
            context = applicationContext,
            onResult = { executeSpokenCommand(it) },
            onListeningStateChange = { OverlayStateController.onListeningChanged(it) }
        )

        startOverlayForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lifecycleOwner.onCreate()
        showOverlayWindow()
    }

    private fun startOverlayForegroundNotification() {
        val channelId = "pocket_agent_overlay_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "PocketAgent Floating Bubble", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PocketAgent Floating Bubble")
            .setContentText("Tap Mic to speak or Eye to read screen")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1002, notification)
        }
    }

    private fun togglePushToTalk() {
        if (OverlayStateController.uiState.value.isListening) {
            voiceController.stopListening()
        } else {
            voiceController.startListening(!networkMonitor.isOnline())
        }
    }

    private fun readAndSummarizeCurrentScreen(customInstruction: String? = null) {
        val accessibility = AgentAccessibilityService.instance
        if (accessibility == null) {
            val msg = "Enable 'UI Tap' Accessibility first to read screen."
            OverlayStateController.updateStatus(msg)
            voiceController.speak(msg)
            return
        }

        OverlayStateController.onScreenAnalysisStarted()

        overlayScope.launch {
            val screenText = accessibility.extractVisibleScreenText()
            val instruction = customInstruction ?: "Summarize key points in 3 bullets or answer any question shown."
            val (summary, badge) = router.analyzeScreenContent(screenText, instruction)
            OverlayStateController.onScreenAnalysisResult(summary, badge)
            voiceController.speak(summary)
        }
    }

    private fun saveSummaryToNotes(summaryText: String) {
        if (summaryText.isBlank()) return
        overlayScope.launch {
            db.noteDao().insertNote(NoteEntity(content = "[Screen Summary]\n$summaryText"))
            OverlayStateController.updateStatus("Saved summary to Notes!")
        }
    }

    private fun executeSpokenCommand(spokenText: String) {
        if (spokenText.isBlank()) return
        OverlayStateController.updateStatus("\"$spokenText\"")

        overlayScope.launch {
            val (action, _) = router.routeInput(spokenText, db.macroDao())
            if (action.tool == "analyze_screen") {
                readAndSummarizeCurrentScreen(action.args["instruction"])
                return@launch
            }
            val reply = toolExecutor.execute(action)
            OverlayStateController.updateStatus(reply)
            voiceController.speak(reply)
        }
    }

    private fun showOverlayWindow() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        val view = ComposeView(this).apply {
            lifecycleOwner.attachToView(this)
            setContent {
                MaterialTheme {
                    FloatingBubbleContent(
                        onDrag = { dx, dy ->
                            params.x += dx.roundToInt()
                            params.y += dy.roundToInt()
                            windowManager.updateViewLayout(this, params)
                        },
                        onToggleMic = { togglePushToTalk() },
                        onReadScreen = { readAndSummarizeCurrentScreen() },
                        onSaveSummary = { saveSummaryToNotes(it) },
                        onOpenApp = {
                            val launchIntent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(launchIntent)
                        },
                        onDismissOverlay = { stopSelf() }
                    )
                }
            }
        }

        composeView = view
        windowManager.addView(view, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        AgentPrefs.setBubbleEnabled(this, false)
        AgentTileService.requestTileSync(this)
        voiceController.shutdown()
        overlayScope.cancel()
        composeView?.let { windowManager.removeView(it) }
        composeView = null
        lifecycleOwner.onDestroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var isRunning: Boolean = false
            private set
    }
}

@Composable
private fun FloatingBubbleContent(
    onDrag: (Float, Float) -> Unit,
    onToggleMic: () -> Unit,
    onReadScreen: () -> Unit,
    onSaveSummary: (String) -> Unit,
    onOpenApp: () -> Unit,
    onDismissOverlay: () -> Unit
) {
    val uiState by OverlayStateController.uiState.collectAsState()
    var isExpanded by remember { mutableStateOf(true) }

    val containerColor = when {
        uiState.isListening -> Color(0xFF7F1D1D)
        uiState.isAnalyzingScreen -> Color(0xFF312E81)
        uiState.isMacroRunning -> Color(0xFF1E293B)
        else -> Color(0xEE111827)
    }

    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier.shadow(10.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)),
            color = containerColor,
            contentColor = Color.White
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { if (isExpanded) onOpenApp() else isExpanded = true },
                    modifier = Modifier.size(36.dp).background(Color(0xFF374151), shape = CircleShape)
                ) {
                    Icon(Icons.Default.SmartToy, contentDescription = "Open App", tint = Color.White, modifier = Modifier.size(20.dp))
                }

                if (!uiState.isMacroRunning) {
                    IconButton(
                        onClick = onToggleMic,
                        modifier = Modifier.size(36.dp).background(if (uiState.isListening) Color(0xFFEF4444) else Color(0xFF10B981), shape = CircleShape)
                    ) {
                        Icon(if (uiState.isListening) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "PTT", tint = Color.White, modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = onReadScreen,
                        modifier = Modifier.size(36.dp).background(if (uiState.isAnalyzingScreen) Color(0xFF6366F1) else Color(0xFF2563EB), shape = CircleShape)
                    ) {
                        if (uiState.isAnalyzingScreen) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.Visibility, contentDescription = "Read Screen", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                AnimatedVisibility(visible = isExpanded || uiState.isMacroRunning || uiState.isListening) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.widthIn(max = 125.dp)) {
                            if (uiState.stepProgress.isNotEmpty()) {
                                Text("STEP ${uiState.stepProgress}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                            }
                            Text(uiState.statusText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                        }

                        if (uiState.isMacroRunning) {
                            Button(
                                onClick = { OverlayStateController.requestEmergencyStop() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            IconButton(onClick = onDismissOverlay, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = uiState.screenSummary.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                color = Color(0xF51E293B),
                contentColor = Color.White
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Screen Insight", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF93C5FD))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { onSaveSummary(uiState.screenSummary) }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = "Save", tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { OverlayStateController.clearScreenSummary() }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        uiState.screenSummary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Color.White,
                        modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
