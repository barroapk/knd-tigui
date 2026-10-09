package com.kounadia.kndtigui

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Enregistre / retire le jeton de notification de ce telephone aupres du serveur. */
object PushRegistration {
    private fun path(role: String?, suffix: String): String =
        (if (role == "AGENT") "/agents/push-token" else "/auth/manager/push-token") + suffix

    fun register(context: Context) {
        val app = context.applicationContext as? KndTiguiApplication ?: return
        val session = SessionStorage.getToken(context) ?: return
        val role = SessionStorage.getRole(context)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            app.applicationScope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(context)
                    ApiClient.request(base, "POST", path(role, ""), session, JSONObject().put("token", token))
                } catch (_: Exception) {
                }
            }
        }
    }

    /** A appeler AVANT SessionStorage.clear(), car la route exige la session. */
    fun unregister(context: Context) {
        val app = context.applicationContext as? KndTiguiApplication ?: return
        val session = SessionStorage.getToken(context) ?: return
        val role = SessionStorage.getRole(context)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            app.applicationScope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(context)
                    ApiClient.request(base, "POST", path(role, "/remove"), session, JSONObject().put("token", token))
                } catch (_: Exception) {
                }
            }
        }
    }
}
