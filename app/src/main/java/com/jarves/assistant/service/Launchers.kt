package com.jarves.assistant.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.jarves.assistant.MainActivity

/* Ways to summon Jarves quickly: default digital assistant (long-press home/power) and a Quick Settings tile. */

private fun listenIntent(ctx: Context) = Intent(ctx, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_LISTEN, true)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Lets the user pick Jarves as "Digital assistant app". */
class JarvesVoiceInteractionService : VoiceInteractionService()

class JarvesSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvesSession(this)
}

class JarvesSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            startAssistantActivity(listenIntent(context))
        } catch (e: Exception) {
            context.startActivity(listenIntent(context))
        }
        hide()
    }
}

/** Required by the system for an assistant app; Jarves uses the phone's own recognizer instead. */
class JarvesRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) { }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Quick Settings tile: one tap from the notification shade to talk to Jarves. */
class JarvesTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val i = listenIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
