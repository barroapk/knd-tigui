package com.kounadia.kndtigui

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Portee de coroutine au niveau application - survit a la duree de vie
 * courte d'un BroadcastReceiver (quelques secondes) ou d'une Activity
 * fermee. Necessaire car le serveur Render peut mettre 30-60s a se
 * reveiller, largement au-dela de ce qu'un BroadcastReceiver autorise.
 *
 * syncInProgress evite que plusieurs sources de declenchement (SMS recu,
 * onResume, bouton manuel) ne lancent des synchronisations concurrentes
 * sur les memes evenements stockes.
 */
class KndTiguiApplication : Application() {
    private val TAG = "KND-Tigui-App"
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncInProgress = AtomicBoolean(false)

    companion object {
        const val PUSH_CHANNEL_ID = "knd_alerts"
    }

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                PUSH_CHANNEL_ID, "Alertes KND", android.app.NotificationManager.IMPORTANCE_HIGH,
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun triggerSync() {
        if (!syncInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "Synchronisation deja en cours, declenchement ignore")
            return
        }

        applicationScope.launch {
            try {
                SyncService.syncPendingEvents(applicationContext)
            } finally {
                syncInProgress.set(false)
            }
        }
    }
}
