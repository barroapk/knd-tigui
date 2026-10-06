package com.kounadia.kndtigui

import android.content.Context

/**
 * Session KND-Tigui.
 *
 * Le mot de passe n'est jamais stocké.
 * La session est commune aux comptes MANAGER/ADMIN et AGENT.
 */
object SessionStorage {
    private const val PREFS_NAME = "knd_tigui_session"

    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_ROLE = "role"
    private const val KEY_IDENTIFIER = "identifier"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        token: String,
        userId: String,
        identifier: String,
        displayName: String,
        role: String,
    ) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_ROLE, role)
            .putString(KEY_IDENTIFIER, identifier)
            .apply()
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun getUserId(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)

    // Compatibilite avec ManagerActivity existante.
    fun getManagerId(context: Context): String? =
        getUserId(context)


    fun getDisplayName(context: Context): String? =
        prefs(context).getString(KEY_DISPLAY_NAME, null)

    fun getRole(context: Context): String? =
        prefs(context).getString(KEY_ROLE, null)

    fun getIdentifier(context: Context): String? =
        prefs(context).getString(KEY_IDENTIFIER, null)

    // Compatibilite avec ManagerActivity existante.
    fun getLastEmail(context: Context): String? =
        getIdentifier(context)


    fun isLoggedIn(context: Context): Boolean =
        !getToken(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
