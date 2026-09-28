package com.kounadia.kndtigui

import android.content.Context

/**
 * Configuration serveur de ce telephone. L'URL est publique (prerenseignee),
 * seul le token appareil est un secret : il est saisi une fois, jamais
 * ecrit dans le code source.
 */
object ConfigStorage {
    const val DEFAULT_API_BASE_URL = "https://knd-api-5bgz.onrender.com"
    private const val PREFS_NAME = "knd_tigui_config"
    private const val KEY_API_BASE_URL = "api_base_url"
    private const val KEY_DEVICE_TOKEN = "device_token"

    fun getApiBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_API_BASE_URL, null)
        return if (saved.isNullOrBlank()) DEFAULT_API_BASE_URL else saved
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
        return !getDeviceToken(context).isNullOrBlank()
    }
}
