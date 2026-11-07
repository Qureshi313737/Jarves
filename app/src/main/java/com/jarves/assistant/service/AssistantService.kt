package com.jarves.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.jarves.assistant.ChatItem
import com.jarves.assistant.Core
import com.jarves.assistant.MainActivity
import com.jarves.assistant.State
import kotlin.math.abs

/**
 * Keeps Jarves alive while it works inside other apps (Chrome, WhatsApp…),
 * shows a notification with "Talk" / "Stop" buttons and the floating "J" bubble.
 */
class AssistantService : Service(), Core.Listener {

    companion object {
        @Volatile var running = false
            private set
        const val ACTION_LISTEN = "com.jarves.assistant.LISTEN"
        const val ACTION_STOP = "com.jarves.assistant.STOP"
        const val ACTION_REFRESH_BUBBLE = "com.jarves.assistant.REFRESH_BUBBLE"
        const val ACTION_QUIT = "com.jarves.assistant.QUIT"
        private const val CHANNEL = "jarves"
        private const val NOTIF_ID = 7

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, AssistantService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }
    }

    private var bubble: View? = null
    private var bubbleCircle: TextView? = null
    private var bubbleLabel: TextView? = null
    private lateinit var wm: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        goForeground("Jarves ready hai")
        running = true
        Core.addListener(this)
        refreshBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> Core.startListening()
            ACTION_STOP -> Core.stopAll()
            ACTION_REFRESH_BUBBLE -> refreshBubble()
            ACTION_QUIT -> { Core.stopAll(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Core.removeListener(this)
        removeBubble()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)!!
            val ch = NotificationChannel(CHANNEL, "Jarves assistant", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pFlags)
        val talk = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LISTEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pFlags,
        )
        val stop = PendingIntent.getService(this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), pFlags)
        val quit = PendingIntent.getService(this, 3, Intent(this, AssistantService::class.java).setAction(ACTION_QUIT), pFlags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarves")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "🎤 Bolo", talk).build())
            .addAction(Notification.Action.Builder(null, "⏹ Stop", stop).build())
            .addAction(Notification.Action.Builder(null, "Band karo", quit).build())
            .build()
    }

    private var micTyped = false

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                startForeground(NOTIF_ID, n, type)
                micTyped = mic
            } catch (e: Exception) {
                try { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) } catch (e2: Exception) { }
            }
        } else {
            startForeground(NOTIF_ID, n)
            micTyped = true
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, buildNotification(text)) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ Core.Listener

    override fun onState(state: State, detail: String) {
        val label = when (state) {
            State.IDLE -> "Jarves ready hai"
            State.LISTENING -> "🎤 Sun raha hoon…"
            State.THINKING -> detail.ifBlank { "Kaam kar raha hoon…" }
            State.SPEAKING -> "🔊 Bol raha hoon…"
        }
        // Mic permission may have been granted after the service started: upgrade the service type.
        if (state == State.LISTENING && !micTyped) goForeground(label) else updateNotification(label)
        bubbleCircle?.background = circle(colorFor(state))
        bubbleLabel?.let {
            it.text = if (state == State.IDLE) "" else label.take(48)
            it.visibility = if (state == State.IDLE) View.GONE else View.VISIBLE
        }
    }

    override fun onPartialSpeech(text: String) {
        if (text.isNotBlank()) bubbleLabel?.let { it.text = "🎤 $text".take(60); it.visibility = View.VISIBLE }
    }

    override fun onItem(item: ChatItem) {
        if (item.kind == ChatItem.Kind.ASSISTANT || item.kind == ChatItem.Kind.ERROR) {
            bubbleLabel?.let { it.text = item.text.take(80); it.visibility = View.VISIBLE }
        }
    }

    // ------------------------------------------------------------------ floating bubble

    private fun colorFor(s: State) = when (s) {
        State.IDLE -> 0xFF3D5AFE.toInt()
        State.LISTENING -> 0xFFE53935.toInt()
        State.THINKING -> 0xFFFFA000.toInt()
        State.SPEAKING -> 0xFF00C853.toInt()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), 0xFFFFFFFF.toInt())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refreshBubble() {
        val want = Core.prefs.showBubble && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))
        if (want && bubble == null) addBubble() else if (!want) removeBubble()
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun addBubble() {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = resources.displayMetrics.heightPixels / 3

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val c = TextView(this).apply {
            text = "J"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = circle(colorFor(Core.state))
            elevation = dp(6).toFloat()
        }
        row.addView(c, LinearLayout.LayoutParams(dp(52), dp(52)))
        val label = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxWidth = dp(220)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xDD202124.toInt()) }
            visibility = View.GONE
        }
        row.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        c.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(row, lp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (System.currentTimeMillis() - downTime > 600) {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else when (Core.state) {
                        State.IDLE -> Core.startListening()
                        else -> Core.stopAll()
                    }
                }
            }
            true
        }
        label.setOnClickListener { label.visibility = View.GONE }
        try {
            wm.addView(row, lp)
            bubble = row
            bubbleCircle = c
            bubbleLabel = label
        } catch (e: Exception) {
            bubble = null
        }
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
        bubbleCircle = null
        bubbleLabel = null
    }
}
