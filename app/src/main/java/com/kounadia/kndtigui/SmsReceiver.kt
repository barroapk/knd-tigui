package com.kounadia.kndtigui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.widget.Toast

/**
 * Prototype v0.1 - detection uniquement. Rien n'est envoye a KND API.
 * Objectif : verifier ce que Android expose reellement pour un SMS entrant
 * (expediteur, contenu, timestamp) sur cet appareil precis, avant de
 * construire le filtrage par conversation et l'envoi vers le backend.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KND-Tigui-SMS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (message in messages) {
            val sender = message.originatingAddress ?: "INCONNU"
            val body = message.messageBody ?: ""
            val timestamp = message.timestampMillis

            Log.i(TAG, "=== SMS DETECTE ===")
            Log.i(TAG, "Expediteur (originatingAddress): $sender")
            Log.i(TAG, "Timestamp: $timestamp")
            Log.i(TAG, "Contenu: $body")
            Log.i(TAG, "===================")

            Toast.makeText(
                context,
                "SMS detecte de: $sender",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
