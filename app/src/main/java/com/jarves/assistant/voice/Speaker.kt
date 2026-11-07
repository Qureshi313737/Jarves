package com.jarves.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech. Picks a Hindi voice for Devanagari text and an Indian-English voice otherwise. */
class Speaker(ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var onDone: (() -> Unit)? = null
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish()
        })
    }

    private fun finish() {
        val cb = onDone
        onDone = null
        if (cb != null) main.post(cb)
    }

    fun speak(text: String, done: () -> Unit) {
        val clean = text
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#`>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (!ready || clean.isEmpty()) { done(); return }
        val hasDevanagari = clean.any { it in 'ऀ'..'ॿ' }
        val hasArabic = clean.any { it in '؀'..'ۿ' }
        val locale = when {
            hasDevanagari -> Locale("hi", "IN")
            hasArabic -> Locale("ur", "PK")
            else -> Locale("en", "IN")
        }
        val res = tts.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) tts.language = Locale.getDefault()
        onDone = done
        tts.speak(clean.take(3900), TextToSpeech.QUEUE_FLUSH, null, "jarves-${System.currentTimeMillis()}")
    }

    fun stop() {
        if (tts.isSpeaking) tts.stop()
        finish()
    }
}
