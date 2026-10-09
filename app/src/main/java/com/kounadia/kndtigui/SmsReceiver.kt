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
 * Detecte les SMS Orange Money de RECEPTION de paiement, les parse
 * localement, et les stocke comme evenements PENDING_SYNC en attente
 * d'envoi vers KND API (etape suivante, pas encore implementee ici).
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KND-Tigui-SMS"
        const val PREFS_NAME = "knd_tigui_prefs"
        const val KEY_PAYMENT_EVENTS = "payment_events"
        const val MAX_STORED = 20
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val sender = messages[0].originatingAddress ?: "INCONNU"

        if (sender != "OrangeMoney") {
            Log.i(TAG, "SMS ignore : expediteur non autorise = $sender")
            return
        }

        val timestamp = messages[0].timestampMillis
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }

        val looksLikePaymentReceived = fullBody.contains("Vous avez recu") &&
            fullBody.contains("FCFA") &&
            fullBody.contains("Trans id:", ignoreCase = true)

        if (!looksLikePaymentReceived) {
            Log.i(TAG, "SMS ignore : ne correspond pas au format attendu de reception de paiement")
            return
        }

        val parsed = OrangeMoneySmsParser.parse(fullBody)
        if (parsed == null) {
            Log.w(TAG, "SMS correspond au format mais le parsing a echoue : $fullBody")
            return
        }

        Log.i(TAG, "=== PAIEMENT PARSE ===")
        Log.i(TAG, "Montant: ${parsed.amount}")
        Log.i(TAG, "Expediteur: ${parsed.senderPhone} (${parsed.senderName})")
        Log.i(TAG, "Transaction ID: ${parsed.transactionId}")

        val wasNew = savePaymentEvent(prefs, parsed, timestamp)

        if (wasNew) {
            Toast.makeText(context, "Paiement detecte: ${parsed.amount} FCFA", Toast.LENGTH_LONG).show()
            // Synchronisation en arriere-plan, portee Application (survit
            // a la duree de vie courte de ce BroadcastReceiver).
            (context.applicationContext as? KndTiguiApplication)?.triggerSync()
        } else {
            Log.i(TAG, "Transaction deja connue localement, ignoree : ${parsed.transactionId}")
        }
    }

    /**
     * Retourne false si transactionId existe deja (idempotence locale) -
     * garantit qu'un meme SMS recu deux fois (double reception reseau,
     * reboot, etc.) ne cree jamais deux evenements distincts.
     */
    private fun savePaymentEvent(
        prefs: SharedPreferences,
        parsed: ParsedOrangeMoneyPayment,
        timestamp: Long
    ): Boolean {
        val existing = prefs.getString(KEY_PAYMENT_EVENTS, "[]") ?: "[]"
        val array = try {
            JSONArray(existing)
        } catch (e: Exception) {
            JSONArray()
        }

        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            if (entry.optString("transactionId") == parsed.transactionId) {
                return false
            }
        }

        val newEntry = JSONObject().apply {
            put("amount", parsed.amount)
            put("senderPhone", parsed.senderPhone)
            put("senderName", parsed.senderName)
            put("newBalance", parsed.newBalance)
            put("transactionId", parsed.transactionId)
            put("timestamp", timestamp)
            put("status", "PENDING_SYNC")
        }

        val updated = JSONArray()
        updated.put(newEntry)
        for (i in 0 until minOf(array.length(), MAX_STORED - 1)) {
            updated.put(array.get(i))
        }

        prefs.edit().putString(KEY_PAYMENT_EVENTS, updated.toString()).apply()
        return true
    }
}
