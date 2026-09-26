package com.kounadia.kndtigui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prototype v0.1 - detection uniquement. Rien n'est envoye a KND API.
 * Sauvegarde les 10 derniers SMS detectes dans SharedPreferences pour
 * affichage direct dans l'app (pas besoin de adb/logcat pour tester).
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KND-Tigui-SMS"
        const val PREFS_NAME = "knd_tigui_prefs"
        const val KEY_DETECTED_SMS = "detected_sms"
        const val MAX_STORED = 10
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (messages.isEmpty()) return

        // IMPORTANT : un SMS long est fragmente par le reseau en plusieurs
        // SmsMessage distincts qui arrivent ensemble dans le meme intent.
        // Il faut les reconcatener dans l'ordre avant de traiter le texte,
        // sinon on perd des mots-cles (Trans id, montant) au milieu du SMS.
        val sender = messages[0].originatingAddress ?: "INCONNU"

        // Prototype : on ne conserve pour l'instant que les SMS
        // provenant exactement de la source Orange Money.
        if (sender != "OrangeMoney") {
            Log.i(TAG, "SMS ignore : expediteur non autorise = $sender")
            return
        }

        val timestamp = messages[0].timestampMillis
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }

        // Filtre de contenu : ne garder que les SMS de RECEPTION de paiement,
        // pas les soldes, recharges credit, ou paiements EMIS (meme si envoyes
        // par OrangeMoney). Les trois marqueurs doivent tous etre presents.
        val looksLikePaymentReceived = fullBody.contains("Vous avez recu") &&
            fullBody.contains("FCFA") &&
            fullBody.contains("Trans id:")

        if (!looksLikePaymentReceived) {
            Log.i(TAG, "SMS ignore : ne correspond pas au format attendu de reception de paiement")
            return
        }

        Log.i(TAG, "=== SMS DETECTE (${messages.size} fragment(s) recombine(s)) ===")
        Log.i(TAG, "Expediteur: $sender")
        Log.i(TAG, "Timestamp: $timestamp")
        Log.i(TAG, "Contenu complet: $fullBody")

        saveDetectedSms(prefs, sender, fullBody, timestamp)

        Toast.makeText(context, "SMS detecte de: $sender", Toast.LENGTH_LONG).show()
    }

    private fun saveDetectedSms(prefs: SharedPreferences, sender: String, body: String, timestamp: Long) {
        val existing = prefs.getString(KEY_DETECTED_SMS, "[]") ?: "[]"
        val array = try {
            JSONArray(existing)
        } catch (e: Exception) {
            JSONArray()
        }

        val newEntry = JSONObject().apply {
            put("sender", sender)
            put("body", body)
            put("timestamp", timestamp)
        }

        // Nouveau tableau : le plus recent en premier, limite a MAX_STORED
        val updated = JSONArray()
        updated.put(newEntry)
        for (i in 0 until minOf(array.length(), MAX_STORED - 1)) {
            updated.put(array.get(i))
        }

        prefs.edit().putString(KEY_DETECTED_SMS, updated.toString()).apply()
    }
}
