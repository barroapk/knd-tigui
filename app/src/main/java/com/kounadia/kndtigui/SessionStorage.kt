package com.kounadia.kndtigui

import android.content.Context

/** Session du Manager connecte. Le mot de passe n'est jamais stocke. */
object SessionStorage {
    private const val PREFS_NAME = "knd_tigui_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_MANAGER_ID = "manager_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_ROLE = "role"
    private const val KEY_LAST_EMAIL = "last_email"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, token: String, managerId: String, email: String, displayName: String, role: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_MANAGER_ID, managerId)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_ROLE, role)
            .putString(KEY_LAST_EMAIL, email)
            .apply()
    }

    fun getToken(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)
    fun getManagerId(context: Context): String? = prefs(context).getString(KEY_MANAGER_ID, null)
    fun getDisplayName(context: Context): String? = prefs(context).getString(KEY_DISPLAY_NAME, null)
    fun getRole(context: Context): String? = prefs(context).getString(KEY_ROLE, null)
    fun getLastEmail(context: Context): String? = prefs(context).getString(KEY_LAST_EMAIL, null)

    fun isLoggedIn(context: Context): Boolean = !getToken(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_MANAGER_ID)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_ROLE)
            .apply()
    }
}
