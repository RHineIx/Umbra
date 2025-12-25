package com.umbra.hooks.utils

import org.json.JSONArray
import org.json.JSONObject

object AdSanitizer {

    // Fast string check to avoid parsing JSON if clearly not needed
    fun shouldSanitize(jsonString: String?): Boolean {
        if (jsonString.isNullOrEmpty()) return false
        return jsonString.contains("is_promoted") ||
               jsonString.contains("is_third_party_ad")
    }

    fun cleanFeed(jsonResponse: String): String {
        try {
            val root = JSONObject(jsonResponse)
            
            // If data node is missing or not an array, return original
            val dataNode = root.optJSONArray("data") ?: return jsonResponse
            
            val cleanArray = JSONArray()
            var adsFound = false
            val originalLength = dataNode.length()

            for (i in 0 until originalLength) {
                val item = dataNode.optJSONObject(i) ?: continue
                
                val isPromoted = item.optBoolean("is_promoted", false)
                val isThirdParty = item.optBoolean("is_third_party_ad", false)

                if (isPromoted || isThirdParty) {
                    // Mark that we found ads, so we know we must rebuild the JSON
                    adsFound = true
                } else {
                    cleanArray.put(item)
                }
            }

            // MEMORY OPTIMIZATION:
            // If no ads were actually found/removed, return the original string directly.
            // This avoids the expensive 'root.toString()' call which re-serializes 
            // the entire JSON object, saving significant CPU and memory.
            if (!adsFound) {
                return jsonResponse
            }

            // Only update and serialize if changes actually occurred
            root.put("data", cleanArray)
            return root.toString()

        } catch (e: Exception) {
            // Fail-safe: return original response if parsing breaks
            return jsonResponse
        }
    }
}
