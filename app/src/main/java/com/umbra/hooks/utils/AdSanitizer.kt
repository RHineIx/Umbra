package com.umbra.hooks.utils

import org.json.JSONArray
import org.json.JSONObject

object AdSanitizer {

    // Fast string check to avoid parsing JSON if clearly not needed
    // We look for the exact key you found in Reqable
    fun shouldSanitize(jsonString: String?): Boolean {
        if (jsonString.isNullOrEmpty()) return false
        // The "God Mode" key found via Reqable
        return jsonString.contains("is_promoted")
    }

    fun cleanFeed(jsonResponse: String): String {
        try {
            val root = JSONObject(jsonResponse)
            
            // Pinterest feeds are usually inside a "data" array
            val dataNode = root.optJSONArray("data") ?: return jsonResponse
            
            val cleanArray = JSONArray()
            var adsFound = false
            val originalLength = dataNode.length()

            for (i in 0 until originalLength) {
                val item = dataNode.optJSONObject(i) ?: continue
                
                // 1. Check the primary key we found: "is_promoted": true
                val isPromoted = item.optBoolean("is_promoted", false)
                
                // 2. Extra safety check: "is_third_party_ad" (sometimes used)
                val isThirdParty = item.optBoolean("is_third_party_ad", false)

                if (isPromoted || isThirdParty) {
                    // Mark that we found ads, so we know we must rebuild the JSON
                    adsFound = true
                } else {
                    // It's a clean pin, keep it
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

            // Replace the dirty data with clean data
            root.put("data", cleanArray)
            return root.toString()

        } catch (e: Exception) {
            // Fail-safe: If anything goes wrong (bad JSON), return original
            // so the app doesn't crash.
            return jsonResponse
        }
    }
}
