package com.kounadia.kndtigui

import android.content.Context
import org.json.JSONArray

/** Cache local de l'historique de l'agent connecte, pour un affichage instantane. */
object AgentHistoryCache {
    private const val PREFS_NAME = "knd_tigui_agent_history"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(context: Context): String =
        "history_" + (SessionStorage.getUserId(context) ?: "none")

    fun load(context: Context): JSONArray {
        val raw = prefs(context).getString(key(context), null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun save(context: Context, array: JSONArray) {
        prefs(context).edit().putString(key(context), array.toString()).apply()
    }
}
