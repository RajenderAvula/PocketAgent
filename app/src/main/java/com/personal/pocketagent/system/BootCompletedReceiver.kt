package com.personal.pocketagent.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.personal.pocketagent.MainActivity
import com.personal.pocketagent.data.local.AgentPrefs
import com.personal.pocketagent.overlay.FloatingOverlayService
import com.personal.pocketagent.voice.WakeWordService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (!AgentPrefs.isAutoStartOnBoot(context)) return

        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) return

        try {
            if (AgentPrefs.isBubbleEnabled(context) && Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(context, Intent(context, FloatingOverlayService::class.java))
            }
            if (AgentPrefs.isWakeWordEnabled(context)) {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            }
        } catch (_: Exception) {
            postBootRestoreNotification(context)
        }
    }

    private fun postBootRestoreNotification(context: Context) {
        val channelId = "pocket_agent_boot_channel"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "PocketAgent Auto-Start", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 2001, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PocketAgent Ready")
            .setContentText("Tap to resume voice assistant & floating bubble after reboot")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(2001, notification)
    }
}
