package com.jarves.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Speech-to-text using the phone's speech recognizer (Google). Must be used on the main thread. */
class VoiceInput(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listen(
        lang: String,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String?) -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("Is phone par speech recognition available nahi hai. Google app install/update karein.")
            return
        }
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        var delivered = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) onPartial(p)
            }

            override fun onResults(results: Bundle?) {
                if (delivered) return
                delivered = true
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                release()
                onResult(text)
            }

            override fun onError(error: Int) {
                if (delivered) return
                delivered = true
                release()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null // silence: just stop
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission chahiye (Setup screen dekhein)."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition ke liye internet chahiye."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic busy hai, dobara try karein."
                    SpeechRecognizer.ERROR_AUDIO -> "Mic se awaaz nahi mili."
                    else -> null
                }
                onError(msg)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            release()
            onError("Mic start nahi hua: ${e.message}")
        }
    }

    fun cancel() {
        recognizer?.let {
            try { it.cancel() } catch (e: Exception) { }
        }
        release()
    }

    private fun release() {
        recognizer?.let {
            try { it.destroy() } catch (e: Exception) { }
        }
        recognizer = null
    }
}
