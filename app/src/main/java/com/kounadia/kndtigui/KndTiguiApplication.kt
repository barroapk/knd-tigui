package com.kounadia.kndtigui

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Application KOUNADIA Admin : canal de notifications et tâches de fond. */
class KndTiguiApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val PUSH_CHANNEL_ID = "knd_alerts_v2"
    }

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                PUSH_CHANNEL_ID, "Alertes KOUNADIA", android.app.NotificationManager.IMPORTANCE_HIGH,
            )
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 300, 200, 300)
            channel.setSound(
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build(),
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
