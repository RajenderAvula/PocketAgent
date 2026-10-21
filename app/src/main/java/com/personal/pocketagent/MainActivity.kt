package com.personal.pocketagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.personal.pocketagent.ui.AgentScreen
import com.personal.pocketagent.ui.AgentViewModel
import com.personal.pocketagent.ui.MacroLibraryScreen
import com.personal.pocketagent.ui.MacroRecorderViewModel

enum class AgentDestination(val label: String, val icon: ImageVector) {
    AGENT_CHAT("Agent Hub", Icons.Default.SmartToy),
    MACRO_LIBRARY("Routines & Backup", Icons.Default.AutoFixHigh)
}

class MainActivity : ComponentActivity() {

    private val agentViewModel: AgentViewModel by viewModels()
    private val macroViewModel: MacroRecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWidgetIntent(intent)

        setContent {
            MaterialTheme {
                PocketAgentRootApp(
                    agentViewModel = agentViewModel,
                    macroViewModel = macroViewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(EXTRA_START_DICTATION, false)) {
            intent.removeExtra(EXTRA_START_DICTATION)
            agentViewModel.toggleDictation()
        }

        intent.getStringExtra(EXTRA_RUN_COMMAND)?.let { cmd ->
            intent.removeExtra(EXTRA_RUN_COMMAND)
            agentViewModel.submitQuery(cmd, speakReply = true)
        }
    }

    companion object {
        const val EXTRA_START_DICTATION = "extra_start_dictation"
        const val EXTRA_RUN_COMMAND = "extra_run_command"
    }
}

@Composable
fun PocketAgentRootApp(
    agentViewModel: AgentViewModel,
    macroViewModel: MacroRecorderViewModel
) {
    var currentTab by rememberSaveable { mutableStateOf(AgentDestination.AGENT_CHAT) }
    val isRecordingMacro by macroViewModel.isRecording.collectAsState()
    val savedMacros by macroViewModel.savedMacros.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                AgentDestination.entries.forEach { destination ->
                    val selected = currentTab == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = destination },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (destination == AgentDestination.MACRO_LIBRARY) {
                                        if (isRecordingMacro) {
                                            Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                Text("REC")
                                            }
                                        } else if (savedMacros.isNotEmpty()) {
                                            Badge {
                                                Text(savedMacros.size.toString())
                                            }
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            }
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { rootPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(rootPadding)
        ) {
            Crossfade(targetState = currentTab, label = "MainTabCrossfade") { tab ->
                when (tab) {
                    AgentDestination.AGENT_CHAT -> AgentScreen(viewModel = agentViewModel)
                    AgentDestination.MACRO_LIBRARY -> MacroLibraryScreen(viewModel = macroViewModel)
                }
            }
        }
    }
}
