package com.jarves.assistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** All user settings, stored in SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("jarves_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** Claude model id. */
    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v).apply()

    /** low | medium | high */
    var effort: String
        get() = sp.getString("effort", "medium") ?: "medium"
        set(v) = sp.edit().putString("effort", v).apply()

    /** Speech recognition language, e.g. hi-IN or en-IN. */
    var speechLang: String
        get() = sp.getString("speech_lang", "hi-IN") ?: "hi-IN"
        set(v) = sp.edit().putString("speech_lang", v).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak_replies", true)
        set(v) = sp.edit().putBoolean("speak_replies", v).apply()

    /** After Jarves finishes speaking, start listening again automatically. */
    var conversationMode: Boolean
        get() = sp.getBoolean("conversation_mode", false)
        set(v) = sp.edit().putBoolean("conversation_mode", v).apply()

    var showBubble: Boolean
        get() = sp.getBoolean("show_bubble", true)
        set(v) = sp.edit().putBoolean("show_bubble", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    /** Long-term facts the assistant should remember about the user. */
    var memories: List<String>
        get() {
            val raw = sp.getString("memories", "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                List(arr.length()) { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) = sp.edit().putString("memories", JSONArray(v).toString()).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (smartest, default)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (faster)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (fastest, cheapest)",
        )
        val EFFORTS = listOf("low", "medium", "high")
        val LANGS = listOf(
            "hi-IN" to "Hindi / Hinglish",
            "en-IN" to "English (India)",
            "en-US" to "English (US)",
            "ur-PK" to "Urdu",
        )
    }
}
