package com.kounadia.kndtigui

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val httpCode: Int, override val message: String) : Exception(message)

/**
 * Appels HTTP vers KND API. A appeler hors thread principal.
 * PATCH est envoye en POST + X-HTTP-Method-Override, corps compris.
 */
object ApiClient {
    private const val TIMEOUT_MS = 60000

    fun request(baseUrl: String, method: String, path: String, token: String? = null, body: JSONObject? = null): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            val sendsBody = method == "POST" || method == "PATCH"
            connection.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") {
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (sendsBody) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val payload = (body ?: JSONObject()).toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw ApiException(code, extractMessage(text, code))
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    /** Reveille le serveur (plan gratuit Render). */
    fun warmUp(baseUrl: String) {
        try {
            val connection = URL("$baseUrl/").openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
        }
    }

    private fun extractMessage(text: String, code: Int): String {
        return try {
            val json = JSONObject(text)
            when (val m = json.opt("message")) {
                is String -> m
                is JSONArray -> (0 until m.length()).joinToString(", ") { m.optString(it) }
                else -> "Erreur $code"
            }
        } catch (e: Exception) {
            "Erreur $code"
        }
    }
}
