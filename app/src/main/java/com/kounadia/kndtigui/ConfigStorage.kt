package com.kounadia.kndtigui

import android.content.Context

/**
 * Stockage local de la configuration serveur (URL + token appareil).
 * Jamais code en dur dans le code source - saisi une fois par le manager
 * lors de la configuration initiale de ce telephone.
 */
object ConfigStorage {
    private const val PREFS_NAME = "knd_tigui_config"
    private const val KEY_API_BASE_URL = "api_base_url"
    private const val KEY_DEVICE_TOKEN = "device_token"

    fun getApiBaseUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_BASE_URL, null)
    }

    fun getDeviceToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_TOKEN, null)
    }

    fun saveConfig(context: Context, apiBaseUrl: String, deviceToken: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_API_BASE_URL, apiBaseUrl.trimEnd('/'))
            .putString(KEY_DEVICE_TOKEN, deviceToken.trim())
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        return !getApiBaseUrl(context).isNullOrBlank() && !getDeviceToken(context).isNullOrBlank()
    }
}
