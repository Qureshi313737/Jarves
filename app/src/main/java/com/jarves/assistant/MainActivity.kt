package com.jarves.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarves.assistant.service.JarvesAccessibilityService

/** Chat screen: talk or type to Jarves and watch what it does. */
class MainActivity : Activity(), Core.Listener {

    companion object {
        const val EXTRA_LISTEN = "listen"
        private const val REQ_MIC = 11
    }

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var partial: TextView
    private lateinit var input: EditText
    private lateinit var mic: TextView
    private lateinit var warning: LinearLayout
    private var pendingListen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(buildUi())
        Core.transcript.forEach { addItem(it, scrollDown = false) }
        scrollToEnd()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val wantsListen = i.getBooleanExtra(EXTRA_LISTEN, false) ||
            i.action == Intent.ACTION_ASSIST || i.action == "android.intent.action.VOICE_COMMAND"
        if (wantsListen) {
            i.removeExtra(EXTRA_LISTEN)
            listen()
        }
    }

    override fun onResume() {
        super.onResume()
        Core.addListener(this)
        onState(Core.state, Core.stateDetail)
        refreshWarning()
        Core.ensureService()
    }

    override fun onPause() {
        Core.removeListener(this)
        super.onPause()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val ctx = this
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }

        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(Ui.text(ctx, if (Features.isLite(ctx)) "Jarves Lite" else "Jarves", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        status = Ui.text(ctx, "Ready", 13f, Ui.MUTED)
        titleBox.addView(status)
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.button(ctx, "Naya chat", Ui.CARD) { Core.newConversation() })
        header.addView(View(ctx), LinearLayout.LayoutParams(dp(8), 1))
        header.addView(Ui.button(ctx, "⚙ Setup", Ui.CARD) { startActivity(Intent(ctx, SetupActivity::class.java)) })
        root.addView(header)

        // Setup warning
        warning = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(ctx, 0xFF3E2723.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { startActivity(Intent(ctx, SetupActivity::class.java)) }
        }
        root.addView(warning, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // Chat
        scroll = ScrollView(ctx).apply { isFillViewport = true }
        chat = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        partial = Ui.text(ctx, "", 15f, Ui.MUTED).apply { visibility = View.GONE; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        root.addView(partial)

        // Input row
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(ctx).apply {
            hint = "Likhiye ya mic dabaiye…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = Ui.rounded(ctx, Ui.CARD, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(ctx), LinearLayout.LayoutParams(dp(6), 1))
        row.addView(Ui.button(ctx, "➤", Ui.CARD) { send() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(View(ctx), LinearLayout.LayoutParams(dp(6), 1))
        mic = TextView(ctx).apply {
            text = "🎤"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
            setOnClickListener {
                when (Core.state) {
                    State.IDLE -> listen()
                    else -> Core.stopAll()
                }
            }
        }
        row.addView(mic, LinearLayout.LayoutParams(dp(60), dp(60)))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val frame = FrameLayout(ctx)
        frame.addView(root)
        frame.fitsSystemWindows = true
        return frame
    }

    private fun refreshWarning() {
        warning.removeAllViews()
        val problems = ArrayList<String>()
        if (Core.prefs.apiKey.isBlank()) problems.add("• Anthropic API key nahi daali gayi")
        if (Features.screenControl(this) && JarvesAccessibilityService.instance == null) problems.add("• Screen control (Accessibility) band hai")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) problems.add("• Microphone permission nahi hai")
        if (problems.isEmpty()) {
            warning.visibility = View.GONE
            return
        }
        warning.visibility = View.VISIBLE
        warning.addView(Ui.text(this, "Setup poora karein (yahan tap karein):", 14f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        warning.addView(Ui.text(this, problems.joinToString("\n"), 13f, 0xFFFFCCBC.toInt()))
    }

    private fun send() {
        val t = input.text.toString()
        if (t.isBlank()) return
        input.setText("")
        Core.submit(t)
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListen = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        Core.startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && pendingListen) {
            pendingListen = false
            Core.startListening()
        }
        refreshWarning()
    }

    private fun addItem(item: ChatItem, scrollDown: Boolean = true) {
        val ctx = this
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val tv = Ui.text(ctx, item.text, if (item.kind == ChatItem.Kind.STEP) 12.5f else 15.5f)
        tv.setTextIsSelectable(true)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        when (item.kind) {
            ChatItem.Kind.USER -> {
                tv.background = Ui.rounded(ctx, Ui.USER_BUBBLE, 16)
                tv.setPadding(dp(14), dp(10), dp(14), dp(10))
                lp.gravity = Gravity.END
                lp.leftMargin = dp(48)
            }
            ChatItem.Kind.ASSISTANT -> {
                tv.background = Ui.rounded(ctx, Ui.CARD, 16)
                tv.setPadding(dp(14), dp(10), dp(14), dp(10))
                lp.rightMargin = dp(48)
            }
            ChatItem.Kind.STEP -> {
                tv.setTextColor(Ui.MUTED)
                tv.setPadding(dp(8), 0, dp(8), 0)
                lp.topMargin = dp(2)
            }
            ChatItem.Kind.ERROR -> {
                tv.setTextColor(0xFFFF8A80.toInt())
                tv.setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            ChatItem.Kind.INFO -> {
                tv.setTextColor(0xFFFFE082.toInt())
                tv.setPadding(dp(8), dp(4), dp(8), dp(4))
            }
        }
        chat.addView(tv, lp)
        while (chat.childCount > 300) chat.removeViewAt(0)
        if (scrollDown) scrollToEnd()
    }

    private fun scrollToEnd() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    // ------------------------------------------------------------------ Core.Listener

    override fun onItem(item: ChatItem) = addItem(item)

    override fun onState(state: State, detail: String) {
        status.text = when (state) {
            State.IDLE -> "Ready — mic dabakar boliye"
            State.LISTENING -> "🎤 Sun raha hoon…"
            State.THINKING -> detail.ifBlank { "Kaam kar raha hoon…" }
            State.SPEAKING -> "🔊 Bol raha hoon…"
        }
        val (icon, color) = when (state) {
            State.IDLE -> "🎤" to Ui.ACCENT
            State.LISTENING -> "■" to Ui.RED
            State.THINKING -> "■" to 0xFFFFA000.toInt()
            State.SPEAKING -> "■" to Ui.GREEN
        }
        mic.text = icon
        (mic.background as? GradientDrawable)?.setColor(color)
    }

    override fun onPartialSpeech(text: String) {
        partial.text = if (text.isBlank()) "" else "🎤 $text"
        partial.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    override fun onCleared() {
        chat.removeAllViews()
        addItem(ChatItem(ChatItem.Kind.INFO, "Naya conversation shuru."))
    }
}
