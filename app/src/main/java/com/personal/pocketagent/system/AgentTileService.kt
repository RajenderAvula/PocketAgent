package com.personal.pocketagent.system

import android.Manifest
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.personal.pocketagent.MainActivity
import com.personal.pocketagent.data.local.AgentPrefs
import com.personal.pocketagent.overlay.FloatingOverlayService

class AgentTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileVisualState()
    }

    override fun onClick() {
        super.onClick()

        if (!Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            launchIntentAndCollapseShade(overlayIntent)
            return
        }

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            val appIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            launchIntentAndCollapseShade(appIntent)
            return
        }

        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        if (FloatingOverlayService.isRunning) {
            stopService(serviceIntent)
            AgentPrefs.setBubbleEnabled(this, false)
            updateTileState(false)
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
            AgentPrefs.setBubbleEnabled(this, true)
            updateTileState(true)
        }
    }

    private fun syncTileVisualState() {
        updateTileState(FloatingOverlayService.isRunning)
    }

    private fun updateTileState(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Bubble: ON" else "PocketAgent"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) "Tap to hide" else "Tap to launch"
        }
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        tile.updateTile()
    }

    private fun launchIntentAndCollapseShade(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestTileSync(context: Context) {
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, AgentTileService::class.java)
                )
            } catch (_: Exception) {}
        }

        fun promptAddTileToQuickSettings(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                statusBarManager?.requestAddTileService(
                    ComponentName(context, AgentTileService::class.java),
                    "PocketAgent Bubble",
                    Icon.createWithResource(context, android.R.drawable.ic_btn_speak_now),
                    context.mainExecutor
                ) {}
            }
        }
    }
}
