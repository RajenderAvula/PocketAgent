package com.personal.pocketagent.widget

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.personal.pocketagent.MainActivity
import com.personal.pocketagent.data.local.AgentDatabase
import com.personal.pocketagent.data.local.AgentPrefs
import com.personal.pocketagent.data.local.MacroEntity
import com.personal.pocketagent.overlay.FloatingOverlayService

class PocketAgentWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AgentDatabase.get(context)
        val topMacros = db.macroDao().getTopMacrosForWidget()
        val isBubbleActive = FloatingOverlayService.isRunning

        provideContent {
            GlanceTheme {
                WidgetContent(topMacros = topMacros, isBubbleActive = isBubbleActive)
            }
        }
    }

    @Composable
    private fun WidgetContent(topMacros: List<MacroEntity>, isBubbleActive: Boolean) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(20.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text("🤖 PocketAgent", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Text("Offline + Online AI", style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 11.sp))
                }

                Button(text = "🎙️ Dictate", onClick = actionRunCallback<LaunchVoiceDictationCallback>())
                Spacer(modifier = GlanceModifier.width(6.dp))
                Button(text = if (isBubbleActive) "🫧 ON" else "🫧 Bubble", onClick = actionRunCallback<ToggleBubbleWidgetCallback>())
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (topMacros.isEmpty()) {
                    QuickCommandChip(label = "⚡ Notes", command = "show my notes")
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    QuickCommandChip(label = "💾 Backup", command = "back up my data")
                } else {
                    topMacros.forEachIndexed { index, macro ->
                        if (index > 0) Spacer(modifier = GlanceModifier.width(6.dp))
                        QuickCommandChip(label = "⚡ ${macro.triggerPhrase.take(14)}", command = macro.triggerPhrase)
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickCommandChip(label: String, command: String) {
        Box(
            modifier = GlanceModifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clickable(actionRunCallback<RunWidgetCommandCallback>(actionParametersOf(KEY_WIDGET_COMMAND to command))),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
    }

    companion object {
        val KEY_WIDGET_COMMAND = ActionParameters.Key<String>("widget_command_key")
        suspend fun refreshWidgets(context: Context) { PocketAgentWidget().updateAll(context) }
    }
}

class LaunchVoiceDictationCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_START_DICTATION, true)
        }
        context.startActivity(intent)
    }
}

class ToggleBubbleWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            return
        }

        val serviceIntent = Intent(context, FloatingOverlayService::class.java)
        if (FloatingOverlayService.isRunning) {
            context.stopService(serviceIntent)
            AgentPrefs.setBubbleEnabled(context, false)
        } else {
            ContextCompat.startForegroundService(context, serviceIntent)
            AgentPrefs.setBubbleEnabled(context, true)
        }
        PocketAgentWidget.refreshWidgets(context)
    }
}

class RunWidgetCommandCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[PocketAgentWidget.KEY_WIDGET_COMMAND] ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_RUN_COMMAND, command)
        }
        context.startActivity(intent)
    }
}

class PocketAgentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PocketAgentWidget()
}
