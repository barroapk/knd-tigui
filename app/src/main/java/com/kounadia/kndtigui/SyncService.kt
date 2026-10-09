package com.kounadia.kndtigui

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Synchronise les evenements PENDING_SYNC/SYNC_FAILED stockes localement
 * vers KND API. Backoff pense pour un serveur Render (plan gratuit) qui
 * peut mettre 30-60s a se reveiller apres inactivite - jamais de retry
 * agressif qui marteleraıt le serveur pendant son reveil.
 */
object SyncService {
    private const val TAG = "KND-Tigui-Sync"
    private const val CONNECT_TIMEOUT_MS = 20000
    private const val READ_TIMEOUT_MS = 20000
    private val RETRY_DELAYS_MS = listOf(0L, 5000L, 15000L, 30000L)

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun syncPendingEvents(context: Context) {
        if (!ConfigStorage.isConfigured(context)) {
            Log.w(TAG, "Synchronisation ignoree : configuration serveur absente")
            return
        }

        val baseUrl = ConfigStorage.getApiBaseUrl(context)!!
        val deviceToken = ConfigStorage.getDeviceToken(context)!!

        val prefs = context.getSharedPreferences(SmsReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(SmsReceiver.KEY_PAYMENT_EVENTS, "[]") ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }

        var anyChanged = false

        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val status = entry.optString("status", "")

            if (status != "PENDING_SYNC" && status != "SYNC_FAILED") {
                continue
            }

            val success = attemptSyncWithBackoff(baseUrl, deviceToken, entry)
            entry.put("status", if (success) "SYNCED" else "SYNC_FAILED")
            anyChanged = true
        }

        if (anyChanged) {
            prefs.edit().putString(SmsReceiver.KEY_PAYMENT_EVENTS, array.toString()).apply()
        }
    }

    private suspend fun attemptSyncWithBackoff(
        baseUrl: String,
        deviceToken: String,
        entry: JSONObject,
    ): Boolean {
        val transactionId = entry.optString("transactionId", "?")

        for ((index, delayMs) in RETRY_DELAYS_MS.withIndex()) {
            if (delayMs > 0) {
                Log.i(TAG, "Attente ${delayMs}ms avant tentative ${index + 1} pour $transactionId")
                delay(delayMs)
            }

            val result = sendPaymentEvent(baseUrl, deviceToken, entry)
            if (result) {
                Log.i(TAG, "Synchronise avec succes: $transactionId (tentative ${index + 1})")
                return true
            }
            Log.w(TAG, "Echec tentative ${index + 1} pour $transactionId")
        }

        Log.e(TAG, "Toutes les tentatives ont echoue pour $transactionId - reste SYNC_FAILED")
        return false
    }

    /**
     * Un seul essai reseau. Accepte comme succes RECEIVED et ALREADY_RECEIVED
     * (deja connu du serveur = objectif atteint, ne pas reessayer indefiniment).
     */
    private fun sendPaymentEvent(baseUrl: String, deviceToken: String, entry: JSONObject): Boolean {
        return try {
            val url = URL("$baseUrl/webhooks/orange-money-payment")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Device-Token", deviceToken)
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            val timestamp = entry.optLong("timestamp", System.currentTimeMillis())
            val body = JSONObject().apply {
                put("amount", entry.optDouble("amount"))
                put("senderPhone", entry.optString("senderPhone"))
                put("senderName", entry.optString("senderName"))
                put("newBalance", entry.optDouble("newBalance"))
                put("transactionId", entry.optString("transactionId"))
                put("receivedAt", isoFormat.format(Date(timestamp)))
                put("rawMessage", entry.optString("rawMessage", ""))
            }

            OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode == 200 || responseCode == 201
        } catch (e: Exception) {
            Log.w(TAG, "Erreur reseau: ${e.message}")
            false
        }
    }
}
