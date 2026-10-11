package com.kounadia.kndtigui

import android.content.Context
import android.content.Intent

/** Session refusee par le serveur (401) : retour a la connexion avec un message clair. */
object SessionExpiry {
    fun handle(context: Context) {
        PushRegistration.unregister(context)
        SessionStorage.clear(context)
        val intent = Intent(context, LoginActivity::class.java)
        intent.putExtra("message", "Votre session a expiré. Reconnectez-vous.")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.startActivity(intent)
    }
}
