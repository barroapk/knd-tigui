package com.kounadia.kndtigui

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class KndMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        if (SessionStorage.isLoggedIn(this)) PushRegistration.register(this)
    }

    // Appelee quand l'app est ouverte ; en arriere-plan, Android affiche seul la notification.
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: return
        val body = message.notification?.body ?: ""
        val notification = NotificationCompat.Builder(this, KndTiguiApplication.PUSH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        } catch (_: SecurityException) {
        }
    }
}
