package com.kounadia.kndtigui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cache local des joueurs favoris de l'agent connecte (SharedPreferences).
 * Sert a afficher la liste instantanement ; le serveur reste la reference
 * et met le cache a jour en arriere-plan.
 */
object FavoritesCache {
    private const val PREFS_NAME = "knd_tigui_favorites"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(context: Context): String =
        "list_" + (SessionStorage.getUserId(context) ?: "none")

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

    fun add(context: Context, item: JSONObject) {
        val current = load(context)
        val result = JSONArray()
        result.put(item)
        for (i in 0 until current.length()) {
            val existing = current.getJSONObject(i)
            if (existing.optString("playerId") != item.optString("playerId")) {
                result.put(existing)
            }
        }
        save(context, result)
    }

    fun remove(context: Context, favoriteId: String) {
        val current = load(context)
        val result = JSONArray()
        for (i in 0 until current.length()) {
            val existing = current.getJSONObject(i)
            if (existing.optString("id") != favoriteId) {
                result.put(existing)
            }
        }
        save(context, result)
    }
}
