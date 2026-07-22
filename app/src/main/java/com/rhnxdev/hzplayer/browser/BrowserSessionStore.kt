package com.rhnxdev.hzplayer.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages persisting and restoring open tabs and active tab session.
 */
class BrowserSessionStore private constructor(prefs: SharedPreferences) {

    private val p = prefs

    fun saveSession(tabs: List<BrowserTab>, activeTabId: String?) {
        val array = JSONArray()
        for (tab in tabs) {
            val obj = JSONObject().apply {
                put("id", tab.id)
                put("url", tab.url)
                put("title", tab.title)
            }
            array.put(obj)
        }

        p.edit()
            .putString(KEY_TABS_JSON, array.toString())
            .putString(KEY_ACTIVE_TAB_ID, activeTabId ?: "")
            .apply()
    }

    fun loadSession(): Pair<List<BrowserTab>, String?>? {
        val jsonString = p.getString(KEY_TABS_JSON, null) ?: return null
        if (jsonString.isBlank()) return null

        val activeTabId = p.getString(KEY_ACTIVE_TAB_ID, null)?.ifBlank { null }
        val tabs = mutableListOf<BrowserTab>()

        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "")
                val url = obj.optString("url", "")
                val title = obj.optString("title", "")
                if (id.isNotBlank()) {
                    tabs.add(BrowserTab(id = id, url = url, title = title))
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (tabs.isEmpty()) return null
        return Pair(tabs, activeTabId ?: tabs.first().id)
    }

    fun clearSession() {
        p.edit()
            .remove(KEY_TABS_JSON)
            .remove(KEY_ACTIVE_TAB_ID)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "browser_session"
        private const val KEY_TABS_JSON = "tabs_json"
        private const val KEY_ACTIVE_TAB_ID = "active_tab_id"

        @Volatile private var instance: BrowserSessionStore? = null

        fun get(context: Context): BrowserSessionStore =
            instance ?: synchronized(this) {
                instance ?: BrowserSessionStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
