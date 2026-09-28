package com.kounadia.kndtigui

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val httpCode: Int, override val message: String) : Exception(message)

/**
 * Appels HTTP vers KND API. Delais longs : le serveur Render (plan gratuit)
 * peut mettre jusqu'a une minute a se reveiller. A appeler hors thread principal.
 */
object ApiClient {
    private const val TIMEOUT_MS = 60000

    fun request(baseUrl: String, method: String, path: String, token: String? = null, body: JSONObject? = null): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (method == "POST") {
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
