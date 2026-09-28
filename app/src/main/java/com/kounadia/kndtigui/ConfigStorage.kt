package com.kounadia.kndtigui

import android.content.Context

/**
 * Configuration de ce telephone. L'URL du serveur est fixe. Le token appareil
 * est cree et enregistre automatiquement quand l'admin configure ce telephone
 * comme hote SMS : il n'est jamais saisi ni affiche.
 */
object ConfigStorage {
    const val DEFAULT_API_BASE_URL = "https://knd-api-5bgz.onrender.com"
    private const val PREFS_NAME = "knd_tigui_config"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_HOST_LABEL = "host_label"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiBaseUrl(context: Context): String = DEFAULT_API_BASE_URL

    fun getDeviceToken(context: Context): String? = prefs(context).getString(KEY_DEVICE_TOKEN, null)
    fun getDeviceId(context: Context): String? = prefs(context).getString(KEY_DEVICE_ID, null)
    fun getHostLabel(context: Context): String = prefs(context).getString(KEY_HOST_LABEL, "") ?: ""

    fun saveHost(context: Context, deviceId: String, deviceToken: String, label: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_HOST_LABEL, label)
            .apply()
    }

    fun clearHost(context: Context) {
        prefs(context).edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_HOST_LABEL)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = !getDeviceToken(context).isNullOrBlank()
}
