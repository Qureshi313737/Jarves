package com.jarves.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jarves.assistant.agent.Agent
import com.jarves.assistant.service.AssistantService
import com.jarves.assistant.voice.Speaker
import com.jarves.assistant.voice.VoiceInput
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One line in the chat transcript. */
data class ChatItem(val kind: Kind, val text: String) {
    enum class Kind { USER, ASSISTANT, STEP, ERROR, INFO }
}

enum class State { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Process-wide hub: owns the agent, the voice input/output and the transcript,
 * and tells registered UIs (activity, floating bubble, notification) what is going on.
 */
object Core {
    interface Listener {
        fun onItem(item: ChatItem) {}
        fun onState(state: State, detail: String) {}
        fun onPartialSpeech(text: String) {}
        fun onCleared() {}
    }

    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()

    val transcript = CopyOnWriteArrayList<ChatItem>()
    @Volatile var state: State = State.IDLE
        private set
    @Volatile var stateDetail: String = ""
        private set

    private lateinit var agent: Agent
    private lateinit var voice: VoiceInput
    private lateinit var speaker: Speaker
    @Volatile private var busy = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        agent = Agent(app, prefs)
        speaker = Speaker(app)
        voice = VoiceInput(app)
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun post(item: ChatItem) {
        transcript.add(item)
        while (transcript.size > 300) transcript.removeAt(0)
        main.post { listeners.forEach { it.onItem(item) } }
    }

    fun setState(s: State, detail: String = "") {
        state = s
        stateDetail = detail
        main.post { listeners.forEach { it.onState(s, detail) } }
    }

    val isBusy: Boolean get() = busy

    /** Start listening to the microphone; the recognized text is sent to the agent. */
    fun startListening() {
        main.post {
            if (busy) return@post
            speaker.stop()
            ensureService()
            setState(State.LISTENING, "Sun raha hoon…")
            voice.listen(
                lang = prefs.speechLang,
                onPartial = { p -> listeners.forEach { it.onPartialSpeech(p) } },
                onResult = { text ->
                    listeners.forEach { it.onPartialSpeech("") }
                    if (text.isBlank()) setState(State.IDLE) else submit(text)
                },
                onError = { msg ->
                    listeners.forEach { it.onPartialSpeech("") }
                    setState(State.IDLE)
                    if (msg != null) post(ChatItem(ChatItem.Kind.INFO, msg))
                },
            )
        }
    }

    fun stopListening() { main.post { voice.cancel(); if (state == State.LISTENING) setState(State.IDLE) } }

    /** Send a typed or spoken command to the agent. */
    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (busy) {
            post(ChatItem(ChatItem.Kind.INFO, "Abhi pichla kaam chal raha hai. Stop dabakar naya command dein."))
            return
        }
        if (prefs.apiKey.isBlank()) {
            post(ChatItem(ChatItem.Kind.ERROR, "Pehle Settings mein apni Anthropic API key daalein."))
            return
        }
        busy = true
        ensureService()
        post(ChatItem(ChatItem.Kind.USER, t))
        setState(State.THINKING, "Soch raha hoon…")
        worker.execute {
            var reply: String? = null
            try {
                reply = agent.run(t, object : Agent.Callbacks {
                    override fun onText(text: String) { /* final text is posted below */ }
                    override fun onStep(text: String) {
                        post(ChatItem(ChatItem.Kind.STEP, text))
                        setState(State.THINKING, text)
                    }
                })
                if (!reply.isNullOrBlank()) post(ChatItem(ChatItem.Kind.ASSISTANT, reply))
            } catch (e: Throwable) {
                post(ChatItem(ChatItem.Kind.ERROR, "Error: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                busy = false
            }
            val r = reply
            main.post {
                if (!r.isNullOrBlank() && prefs.speakReplies) {
                    setState(State.SPEAKING, "Bol raha hoon…")
                    speaker.speak(r) {
                        setState(State.IDLE)
                        // If the user stays silent, listening simply times out and Jarves goes idle.
                        if (prefs.conversationMode) startListening()
                    }
                } else {
                    setState(State.IDLE)
                }
            }
        }
    }

    /** Stop whatever is happening: listening, the agent loop, and speech. */
    fun stopAll() {
        agent.cancel()
        main.post {
            voice.cancel()
            speaker.stop()
            if (!busy) setState(State.IDLE)
        }
    }

    fun newConversation() {
        agent.reset()
        transcript.clear()
        main.post { listeners.forEach { it.onCleared() } }
    }

    fun ensureService() {
        if (AssistantService.running) return
        val i = Intent(app, AssistantService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) {
            // Starting from the background can be refused; the agent still works while the app is open.
        }
    }
}
