package com.jarves.assistant.tools

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Apps, links, device settings, alarms and other phone functions. */
class DeviceTools(private val ctx: Context) {

    private fun start(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ apps & links

    fun openApp(name: String): ToolOutput {
        if (name.isBlank()) return ToolOutput.error("name is empty")
        val matches = rankApps(ctx, name)
        if (matches.isEmpty()) return ToolOutput.error("No installed app matches \"$name\". Use list_apps to see installed apps, or open its website.")
        val best = matches[0]
        val launch = ctx.packageManager.getLaunchIntentForPackage(best.second)
            ?: return ToolOutput.error("${best.first} cannot be launched.")
        if (!start(launch)) return ToolOutput.error("Could not open ${best.first}.")
        Thread.sleep(1800)
        return ToolOutput.ok("Opened ${best.first} (${best.second}). Use read_screen to see it.")
    }

    fun listApps(filter: String): ToolOutput {
        val apps = launchableApps(ctx)
        val f = filter.lowercase().trim()
        val list = if (f.isEmpty()) apps else apps.filter { it.first.lowercase().contains(f) || it.second.contains(f) }
        return ToolOutput.ok("${list.size} apps:\n" + list.joinToString("\n") { "${it.first} — ${it.second}" })
    }

    fun openUrl(url: String, browser: String): ToolOutput {
        var u = url.trim()
        if (u.isEmpty()) return ToolOutput.error("url is empty")
        if (!u.contains("://")) u = "https://$u"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(u))
        if (browser == "chrome") {
            val chrome = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev")
                .firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }
            if (chrome != null) i.setPackage(chrome)
        }
        if (!start(i)) {
            i.setPackage(null)
            if (!start(i)) return ToolOutput.error("No app can open this link.")
        }
        Thread.sleep(2500)
        return ToolOutput.ok("Opened $u${if (browser == "chrome") " in Chrome" else ""}.")
    }

    fun playYoutube(query: String): ToolOutput {
        val yt = "com.google.android.youtube"
        val i = Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra("query", query)
        if (start(i)) {
            Thread.sleep(2500)
            return ToolOutput.ok("YouTube search for \"$query\" opened. Use read_screen and tap a video to play it.")
        }
        return openUrl("https://m.youtube.com/results?search_query=" + Uri.encode(query), "default")
    }

    fun openMaps(query: String, navigate: Boolean): ToolOutput {
        val uri = if (navigate) Uri.parse("google.navigation:q=" + Uri.encode(query)) else Uri.parse("geo:0,0?q=" + Uri.encode(query))
        val i = Intent(Intent.ACTION_VIEW, uri)
        if (start(Intent(i).setPackage("com.google.android.apps.maps")) || start(i)) {
            return ToolOutput.ok(if (navigate) "Navigation to $query started." else "Showing $query on the map.")
        }
        return openUrl("https://www.google.com/maps/search/" + Uri.encode(query), "default")
    }

    // ------------------------------------------------------------------ device state

    fun status(): ToolOutput {
        val sb = StringBuilder()
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            sb.append("Battery: ${level * 100 / scale.coerceAtLeast(1)}%${if (plugged) " (charging)" else ""}\n")
        }
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        sb.append("Storage: ${FileTools.humanSize(stat.availableBytes)} free of ${FileTools.humanSize(stat.totalBytes)}\n")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "connected"
        }
        sb.append("Network: $net\n")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun pct(s: Int) = am.getStreamVolume(s) * 100 / am.getStreamMaxVolume(s).coerceAtLeast(1)
        sb.append("Volume: media ${pct(AudioManager.STREAM_MUSIC)}%, ring ${pct(AudioManager.STREAM_RING)}%, alarm ${pct(AudioManager.STREAM_ALARM)}%\n")
        sb.append("Ringer: ${when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }}\n")
        try {
            val b = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            sb.append("Brightness: ${b * 100 / 255}%\n")
        } catch (e: Exception) { }
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        sb.append("Time: ${SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.ENGLISH).format(java.util.Date())}")
        return ToolOutput.ok(sb.toString())
    }

    fun setVolume(stream: String, percent: Int): ToolOutput {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        val v = (percent.coerceIn(0, 100) * max + 50) / 100
        return try {
            am.setStreamVolume(s, v, AudioManager.FLAG_SHOW_UI)
            ToolOutput.ok("$stream volume set to ${percent.coerceIn(0, 100)}%.")
        } catch (e: SecurityException) {
            openPolicyAccess()
            ToolOutput.error("Changing this volume needs Do-Not-Disturb access; the settings page was opened for the user.")
        }
    }

    fun setBrightness(percent: Int, auto: Boolean): ToolOutput {
        if (!Settings.System.canWrite(ctx)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return ToolOutput.error("Jarves needs 'Modify system settings' permission; that page was opened — ask the user to allow it, then try again.")
        }
        val cr = ctx.contentResolver
        if (auto) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return ToolOutput.ok("Auto brightness on.")
        }
        if (percent < 0) return ToolOutput.error("Give level_percent or auto=true")
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent.coerceIn(0, 100) * 255 / 100).coerceAtLeast(1))
        return ToolOutput.ok("Brightness set to ${percent.coerceIn(0, 100)}%.")
    }

    fun flashlight(on: Boolean): ToolOutput {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return ToolOutput.error("This phone has no flashlight.")
            cm.setTorchMode(id, on)
            ToolOutput.ok("Flashlight ${if (on) "on" else "off"}.")
        } catch (e: Exception) {
            ToolOutput.error("Flashlight failed (camera may be in use): ${e.message}")
        }
    }

    fun ringerMode(mode: String): ToolOutput {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val m = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return try {
            am.ringerMode = m
            ToolOutput.ok("Ringer set to $mode.")
        } catch (e: SecurityException) {
            openPolicyAccess()
            ToolOutput.error("Silent mode needs Do-Not-Disturb access; the settings page was opened — ask the user to allow Jarves, then try again.")
        }
    }

    private fun openPolicyAccess() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun mediaControl(action: String): ToolOutput {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ToolOutput.ok("Sent $action to the media player.")
    }

    fun openSettings(page: String, pkg: String): ToolOutput {
        val action = when (page) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet_panel" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "mobile_data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "app_details" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications" -> if (Build.VERSION.SDK_INT >= 26) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "date_time" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "default_apps" -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            "all_files_access" -> if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            "overlay" -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            else -> Settings.ACTION_SETTINGS
        }
        val i = Intent(action)
        when (page) {
            "app_details" -> i.data = Uri.parse("package:" + pkg.ifBlank { ctx.packageName })
            "all_files_access", "overlay" -> i.data = Uri.parse("package:" + ctx.packageName)
            "notifications" -> if (Build.VERSION.SDK_INT >= 26) i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg.ifBlank { ctx.packageName })
        }
        if (!start(i) && !start(Intent(Settings.ACTION_SETTINGS))) return ToolOutput.error("Could not open settings.")
        Thread.sleep(1500)
        return ToolOutput.ok("Opened $page settings. Use read_screen to continue.")
    }

    // ------------------------------------------------------------------ clock & calendar

    fun setAlarm(hour: Int, minute: Int, label: String, days: JSONArray?): ToolOutput {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (days != null && days.length() > 0) {
            val list = ArrayList<Int>()
            for (k in 0 until days.length()) list.add(days.getInt(k))
            i.putExtra(AlarmClock.EXTRA_DAYS, list)
        }
        return if (start(i)) ToolOutput.ok("Alarm set for %02d:%02d.".format(hour, minute)) else ToolOutput.error("No clock app accepted the alarm.")
    }

    fun setTimer(seconds: Int, label: String): ToolOutput {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (start(i)) ToolOutput.ok("Timer started for $seconds seconds.") else ToolOutput.error("No clock app accepted the timer.")
    }

    fun addCalendarEvent(a: JSONObject): ToolOutput {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH)
        val start = try { fmt.parse(a.optString("start"))?.time } catch (e: Exception) { null }
            ?: return ToolOutput.error("start must look like 2026-11-08T10:00")
        val end = try { fmt.parse(a.optString("end"))?.time } catch (e: Exception) { null } ?: (start + 60 * 60 * 1000)
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.optString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        if (a.optString("location").isNotBlank()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
        if (a.optString("description").isNotBlank()) i.putExtra(CalendarContract.Events.DESCRIPTION, a.optString("description"))
        if (!start(i)) return ToolOutput.error("No calendar app found.")
        Thread.sleep(2000)
        return ToolOutput.ok("Calendar opened with the event filled in. Use read_screen and tap Save.")
    }

    fun openCamera(mode: String): ToolOutput {
        val i = if (mode == "video") Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA) else Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (mode == "selfie") {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        if (!start(i)) return ToolOutput.error("Could not open the camera.")
        Thread.sleep(2000)
        return ToolOutput.ok("Camera opened in $mode mode. Use read_screen/take_screenshot and tap the shutter to capture.")
    }

    fun setClipboard(text: String): ToolOutput {
        Handler(Looper.getMainLooper()).post {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Jarves", text))
        }
        return ToolOutput.ok("Copied to clipboard.")
    }

    @Suppress("DEPRECATION")
    fun location(): ToolOutput {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return ToolOutput.error("Location permission not granted. Ask the user to allow it in Jarves > Setup.")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        try {
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
        } catch (e: SecurityException) { }
        val loc = best ?: return ToolOutput.error("No recent location available. Location may be off — open_settings page location.")
        val sb = StringBuilder("Lat ${loc.latitude}, Lng ${loc.longitude} (±${loc.accuracy.toInt()} m)")
        try {
            val addr = Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            if (addr != null) sb.append("\nAddress: ").append(addr.getAddressLine(0))
        } catch (e: Exception) { }
        return ToolOutput.ok(sb.toString())
    }

    companion object {
        @Volatile private var appCache: List<Pair<String, String>>? = null
        @Volatile private var appCacheTime = 0L

        /** (label, package) of every app with a launcher icon. */
        fun launchableApps(ctx: Context): List<Pair<String, String>> {
            val now = System.currentTimeMillis()
            appCache?.let { if (now - appCacheTime < 60_000) return it }
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(i, 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            appCache = list
            appCacheTime = now
            return list
        }

        private val ALIASES = mapOf(
            "chrome" to "com.android.chrome", "google chrome" to "com.android.chrome",
            "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
            "settings" to "com.android.settings", "play store" to "com.android.vending",
            "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
            "photos" to "com.google.android.apps.photos", "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana", "telegram" to "org.telegram.messenger",
        )

        fun rankApps(ctx: Context, name: String): List<Pair<String, String>> {
            val q = name.lowercase().trim().removeSuffix(" app")
            val apps = launchableApps(ctx)
            ALIASES[q]?.let { pkg -> apps.firstOrNull { it.second == pkg }?.let { return listOf(it) } }
            fun score(a: Pair<String, String>): Int {
                val l = a.first.lowercase()
                return when {
                    l == q -> 0
                    l.startsWith(q) -> 1
                    l.split(' ').any { it == q } -> 2
                    l.contains(q) -> 3
                    a.second.lowercase().contains(q.replace(" ", "")) -> 4
                    else -> 99
                }
            }
            return apps.map { it to score(it) }.filter { it.second < 99 }.sortedBy { it.second }.map { it.first }
        }

        fun findPackage(ctx: Context, name: String): String? = rankApps(ctx, name).firstOrNull()?.second
    }
}
