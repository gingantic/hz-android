package com.rhnxdev.hzplayer.domain.model

data class StreamHistoryItem(
    val id: Long = 0,
    val url: String,
    val title: String,
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val headersJson: String? = null,
    val pageUrl: String? = null,
    val mimeType: String? = null,
) {
    val headersMap: Map<String, String>
        get() {
            if (headersJson.isNullOrBlank()) return emptyMap()
            return try {
                val obj = org.json.JSONObject(headersJson)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k)
                    if (!k.isNullOrBlank() && !v.isNullOrBlank()) {
                        map[k] = v
                    }
                }
                if (map.isNotEmpty()) return map
                parseJsonFallback(headersJson)
            } catch (_: Exception) {
                parseJsonFallback(headersJson)
            }
        }

    private fun parseJsonFallback(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = """"([^"]+)":\s*"([^"]*)"""".toRegex()
        regex.findAll(json).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            if (key.isNotBlank() && value.isNotBlank()) {
                map[key] = value
            }
        }
        return map
    }
}
