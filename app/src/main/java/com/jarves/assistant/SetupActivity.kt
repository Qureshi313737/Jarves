package com.jarves.assistant

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import com.jarves.assistant.service.AssistantService
import com.jarves.assistant.service.JarvesAccessibilityService
import com.jarves.assistant.service.JarvesNotificationListener

/** API key, preferences and every permission Jarves needs, in one screen. */
class SetupActivity : Activity() {

    private lateinit var permBox: LinearLayout
    private lateinit var memBox: LinearLayout
    private val prefs get() = Core.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshMemories()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(Ui.text(this, title, 18f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@SetupActivity, Ui.CARD)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun spinner(items: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): Spinner {
        val sp = Spinner(this)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items.map { it.second }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as? android.widget.TextView)?.setTextColor(Ui.TEXT) }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        sp.setSelection(items.indexOfFirst { it.first == current }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(items[pos].first)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        return sp
    }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        setTextColor(Ui.TEXT)
        isChecked = value
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        root.addView(Ui.text(this, if (Features.isLite(this)) "Jarves Lite Setup" else "Jarves Setup", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        root.addView(Ui.text(this, "Neeche ki sab cheezein ek baar set kar dein. Jitni zyada permissions denge, Jarves utna zyada kaam kar payega.", 13f, Ui.MUTED))

        // ---- Brain
        section(root, "1. Claude API key (Jarves ka dimaag)")
        val brain = card()
        brain.addView(Ui.text(this, "console.anthropic.com par account banakar API key banayein aur yahan paste karein. Key sirf is phone par save hoti hai.", 13f, Ui.MUTED))
        val key = EditText(this).apply {
            hint = "sk-ant-…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.apiKey)
        }
        brain.addView(key)
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(Ui.button(this, "Save key") {
            prefs.apiKey = key.text.toString()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })
        keyRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        keyRow.addView(Ui.button(this, "API key banayein", Ui.BG) {
            openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
        })
        brain.addView(keyRow)
        brain.addView(Ui.text(this, "Model", 13f, Ui.MUTED).apply { setPadding(0, dp(10), 0, 0) })
        brain.addView(spinner(Prefs.MODELS, prefs.model) { prefs.model = it })
        brain.addView(Ui.text(this, "Effort (low = tez aur sasta, high = zyada soch-samajh kar)", 13f, Ui.MUTED).apply { setPadding(0, dp(10), 0, 0) })
        brain.addView(spinner(Prefs.EFFORTS.map { it to it }, prefs.effort) { prefs.effort = it })
        root.addView(brain)

        // ---- Voice
        section(root, "2. Awaaz")
        val voice = card()
        voice.addView(Ui.text(this, "Aap kis bhasha mein bolenge?", 13f, Ui.MUTED))
        voice.addView(spinner(Prefs.LANGS, prefs.speechLang) { prefs.speechLang = it })
        val name = EditText(this).apply {
            hint = "Aapka naam (optional)"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            setText(prefs.userName)
            setOnFocusChangeListener { _, has -> if (!has) prefs.userName = text.toString() }
        }
        voice.addView(name)
        voice.addView(check("Jawab bolkar sunaye", prefs.speakReplies) { prefs.speakReplies = it })
        voice.addView(check("Conversation mode (jawab ke baad dobara sune)", prefs.conversationMode) { prefs.conversationMode = it })
        voice.addView(check("Floating 'J' bubble dikhaye (dusre apps ke upar)", prefs.showBubble) {
            prefs.showBubble = it
            AssistantService.send(this, AssistantService.ACTION_REFRESH_BUBBLE)
        })
        root.addView(voice)

        // ---- Permissions
        section(root, "3. Permissions")
        root.addView(Ui.text(this, "Android 13+ par: agar Accessibility ya Notification access 'restricted' dikhaye, to Settings > Apps > Jarves > ⋮ (upar right) > 'Allow restricted settings' karein, phir dobara try karein.", 12.5f, 0xFFFFE082.toInt()))
        permBox = card()
        root.addView(permBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // ---- Memory
        section(root, "4. Jarves ki yaaddasht")
        memBox = card()
        root.addView(memBox)

        // ---- Help
        section(root, "Kaise use karein")
        val help = card()
        help.addView(Ui.text(this,
            "• App mein mic dabaiye, ya floating 'J' bubble tap karein, ya notification mein '🎤 Bolo'.\n" +
                "• Quick Settings tile: notification panel neeche kheenchein > edit (✎) > 'Jarves' tile add karein.\n" +
                "• Power/Home button long-press se kholne ke liye: Default apps > Digital assistant app > Jarves chunein.\n" +
                "• Example: \"Chrome kholo aur sabse acchi website se Taj Mahal ki HD image download karke Pictures/Taj folder mein save kar do\"\n" +
                "• Example: \"WhatsApp par Ammi ko message bhejo ki main 10 minute mein pahunch raha hoon\"\n" +
                "• Example: \"Kal subah 6 baje ka alarm lagao aur flashlight band karo\"\n" +
                "• Bubble ko tap = bolo / roko, long-press = app kholo.",
            13.5f))
        root.addView(help)

        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermissions() {
        permBox.removeAllViews()
        if (Features.screenControl(this)) {
            val accOn = JarvesAccessibilityService.instance != null || isAccessibilityEnabled()
            row("Screen control (Accessibility) — apps chalane, tap/type ke liye. SABSE ZAROORI.", accOn) {
                openIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "'Installed apps' / 'Downloaded apps' mein Jarves ko ON karein", Toast.LENGTH_LONG).show()
            }
        } else {
            permBox.addView(Ui.text(this, "ℹ️ Ye Jarves LITE hai: dusre apps ke andar tap/type nahi kar sakta. Poore control ke liye Jarves.apk (full) install karein.", 13f, 0xFFFFE082.toInt()).apply { setPadding(0, 0, 0, dp(8)) })
        }
        row("Microphone — awaaz se command", granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        val filesOk = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        row("All files access — kisi bhi folder mein download/save", filesOk) {
            if (Build.VERSION.SDK_INT >= 30) {
                openIntent(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 2)
            }
        }
        row("Display over other apps — floating bubble aur background mein apps kholna", Settings.canDrawOverlays(this)) {
            openIntent(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val phonePerms = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        row("Contacts aur Call — naam se call/message karna", phonePerms.all { granted(it) }) {
            requestPermissions(phonePerms, 3)
        }
        val locPerms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        row("Location — 'main kahan hoon', nearby", locPerms.any { granted(it) }) { requestPermissions(locPerms, 4) }
        if (Build.VERSION.SDK_INT >= 33) {
            row("Notifications dikhana — Jarves ka status", granted(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
            }
        }
        if (Features.notificationReading(this)) {
            row("Notification access — aaye hue messages padhna", JarvesNotificationListener.connected || isListenerEnabled()) {
                openIntent(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        row("Modify system settings — brightness", Settings.System.canWrite(this)) {
            openIntent(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        val nm = getSystemService(NotificationManager::class.java)!!
        row("Do Not Disturb access — silent mode", nm.isNotificationPolicyAccessGranted) {
            openIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        val pm = getSystemService(PowerManager::class.java)!!
        row("Battery optimization off — Jarves background mein band na ho", pm.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("BatteryLife")
            openIntent(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        row("Default digital assistant — power/home long-press se Jarves", isDefaultAssistant()) {
            openIntent(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            Toast.makeText(this, "'Digital assistant app' mein Jarves chunein", Toast.LENGTH_LONG).show()
        }
    }

    private fun row(label: String, ok: Boolean, onFix: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        r.addView(Ui.text(this, if (ok) "✅" else "❌", 16f), LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(Ui.text(this, label, 13.5f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok) r.addView(Ui.button(this, "Allow") { onFix() })
        permBox.addView(r)
    }

    private fun refreshMemories() {
        memBox.removeAllViews()
        val mem = prefs.memories
        if (mem.isEmpty()) {
            memBox.addView(Ui.text(this, "Abhi kuch yaad nahi. Boliye: \"Yaad rakhna ki meri wallpapers Pictures/Walls mein save karni hain\"", 13f, Ui.MUTED))
            return
        }
        mem.forEachIndexed { i, m -> memBox.addView(Ui.text(this, "${i + 1}. $m", 13.5f)) }
        memBox.addView(Ui.button(this, "Sab bhool jao", Ui.RED) {
            prefs.memories = emptyList()
            refreshMemories()
        })
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, JarvesAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun isDefaultAssistant(): Boolean {
        val a = Settings.Secure.getString(contentResolver, "assistant") ?: return false
        return a.startsWith(packageName)
    }

    private fun openIntent(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: Exception) {
                Toast.makeText(this, "Settings nahi khul payi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus()
    }
}
