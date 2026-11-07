package com.jarves.assistant.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.jarves.assistant.Core
import com.jarves.assistant.service.JarvesAccessibilityService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of running one tool. */
data class ToolOutput(val text: String, val isError: Boolean = false, val imageJpegBase64: String? = null) {
    companion object {
        fun ok(text: String) = ToolOutput(text)
        fun error(text: String) = ToolOutput(text, isError = true)
    }
}

/** Declares every phone tool for Claude and routes calls to the implementations. */
class ToolRegistry(private val ctx: Context) {

    private val screen = ScreenTools(ctx)
    private val files = FileTools(ctx)
    private val phone = PhoneTools(ctx)
    private val device = DeviceTools(ctx)

    private val defs: List<Tool> by lazy { buildDefinitions() }

    fun definitions(): List<Tool> = defs

    fun execute(name: String, a: JSONObject): ToolOutput = when (name) {
        // Screen control (accessibility)
        "read_screen" -> screen.readScreen()
        "tap" -> screen.tap(a, long = false)
        "long_press" -> screen.tap(a, long = true)
        "type_text" -> screen.typeText(a.optString("text"), a.optIntOrNull("element"), a.optBoolean("submit", false))
        "scroll" -> screen.scroll(a.optString("direction", "down"), a.optIntOrNull("element"))
        "swipe" -> screen.swipe(a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"), a.optLong("duration_ms", 400))
        "press_key" -> screen.pressKey(a.optString("key"))
        "take_screenshot" -> screen.screenshot()
        "wait" -> screen.waitSeconds(a.optDouble("seconds", 2.0))
        // Apps & web
        "open_app" -> device.openApp(a.optString("name"))
        "list_apps" -> device.listApps(a.optString("filter"))
        "open_url" -> device.openUrl(a.optString("url"), a.optString("browser", "default"))
        "play_youtube" -> device.playYoutube(a.optString("query"))
        "open_maps" -> device.openMaps(a.optString("query"), a.optBoolean("navigate", false))
        // Files
        "download_file" -> files.download(a.optString("url"), a.optString("folder", "Download"), a.optString("file_name"))
        "list_files" -> files.list(a.optString("path"))
        "search_files" -> files.search(a.optString("query"), a.optString("path"))
        "create_folder" -> files.mkdir(a.optString("path"))
        "move_file" -> files.move(a.optString("from"), a.optString("to"), copy = false)
        "copy_file" -> files.move(a.optString("from"), a.optString("to"), copy = true)
        "rename_file" -> files.rename(a.optString("path"), a.optString("new_name"))
        "delete_file" -> files.delete(a.optString("path"), a.optBoolean("confirmed_by_user", false))
        "read_text_file" -> files.readText(a.optString("path"))
        "write_text_file" -> files.writeText(a.optString("path"), a.optString("content"), a.optBoolean("append", false))
        "open_file" -> files.open(a.optString("path"))
        "share_file" -> files.share(a.optString("path"), a.optString("app"))
        // Phone & messages
        "search_contacts" -> phone.searchContacts(a.optString("query"))
        "make_call" -> phone.call(a.optString("to"))
        "send_sms" -> phone.sendSms(a.optString("to"), a.optString("message"))
        "send_whatsapp" -> phone.openWhatsApp(a.optString("to"), a.optString("message"))
        "read_notifications" -> phone.readNotifications(a.optString("app"), a.optInt("limit", 15))
        "read_sms" -> phone.readSms(a.optString("from"), a.optInt("limit", 10))
        // Device
        "device_status" -> device.status()
        "set_volume" -> device.setVolume(a.optString("stream", "media"), a.optInt("level_percent", 50))
        "set_brightness" -> device.setBrightness(a.optInt("level_percent", -1), a.optBoolean("auto", false))
        "flashlight" -> device.flashlight(a.optBoolean("on", true))
        "set_ringer_mode" -> device.ringerMode(a.optString("mode"))
        "media_control" -> device.mediaControl(a.optString("action"))
        "open_settings" -> device.openSettings(a.optString("page"), a.optString("package"))
        "set_alarm" -> device.setAlarm(a.getInt("hour"), a.getInt("minute"), a.optString("label"), a.optJSONArray("days"))
        "set_timer" -> device.setTimer(a.getInt("seconds"), a.optString("label"))
        "add_calendar_event" -> device.addCalendarEvent(a)
        "open_camera" -> device.openCamera(a.optString("mode", "photo"))
        "set_clipboard" -> device.setClipboard(a.optString("text"))
        "get_location" -> device.location()
        "remember" -> remember(a.optString("fact"))
        "forget" -> forget(a.optInt("number", -1))
        else -> ToolOutput.error("Unknown tool: $name")
    }

    /** Short human-readable line shown in the chat while a tool runs. */
    fun describe(name: String, a: JSONObject): String = when (name) {
        "read_screen" -> "👀 Screen padh raha hoon"
        "tap" -> "👆 Tap: " + (a.optString("text").ifEmpty { a.optString("element").ifEmpty { "${a.optInt("x")},${a.optInt("y")}" } })
        "long_press" -> "👆 Long press"
        "type_text" -> "⌨️ Type: " + a.optString("text").take(40)
        "scroll" -> "↕️ Scroll ${a.optString("direction")}"
        "swipe" -> "↔️ Swipe"
        "press_key" -> "🔘 ${a.optString("key")}"
        "take_screenshot" -> "📸 Screenshot dekh raha hoon"
        "wait" -> "⏳ Ruk raha hoon"
        "open_app" -> "📱 App khol raha hoon: ${a.optString("name")}"
        "open_url" -> "🌍 Khol raha hoon: ${a.optString("url").take(60)}"
        "download_file" -> "⬇️ Download kar raha hoon → ${a.optString("folder")}"
        "make_call" -> "📞 Call: ${a.optString("to")}"
        "send_sms" -> "✉️ SMS: ${a.optString("to")}"
        "send_whatsapp" -> "💬 WhatsApp: ${a.optString("to")}"
        "remember" -> "🧠 Yaad rakh raha hoon"
        else -> "⚙️ $name"
    }

    /** Small context line prefixed to each user command. */
    fun contextHeader(): String {
        val time = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val acc = JarvesAccessibilityService.instance != null
        val fg = JarvesAccessibilityService.instance?.currentPackage() ?: "unknown"
        val allFiles = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        return "[Context — time: $time; foreground app: $fg; screen control: ${if (acc) "on" else "OFF (ask user to enable Jarves in Accessibility settings)"}; all-files access: ${if (allFiles) "on" else "off (downloads go to public Download/Pictures via MediaStore)"}]\n"
    }

    private fun remember(fact: String): ToolOutput {
        if (fact.isBlank()) return ToolOutput.error("fact is empty")
        Core.prefs.memories = Core.prefs.memories + fact.trim()
        return ToolOutput.ok("Saved to memory.")
    }

    private fun forget(n: Int): ToolOutput {
        val m = Core.prefs.memories.toMutableList()
        if (n < 1 || n > m.size) return ToolOutput.error("No memory number $n (have ${m.size}).")
        val removed = m.removeAt(n - 1)
        Core.prefs.memories = m
        return ToolOutput.ok("Forgot: $removed")
    }

    // ---------------------------------------------------------------- schema

    private fun buildDefinitions(): List<Tool> = listOf(
        tool("read_screen", "Read what is currently on the phone screen via accessibility: the foreground app and a numbered list of visible elements (text, buttons, input fields, their positions). Use the numbers with tap/type_text/scroll."),
        tool("tap", "Tap an element on screen. Give the element number from read_screen, or visible text to find, or x/y pixel coordinates. Returns the updated screen.",
            listOf(), "element" to int("Element number from read_screen"), "text" to str("Visible text or description of the element to tap"),
            "x" to int("X pixel coordinate"), "y" to int("Y pixel coordinate")),
        tool("long_press", "Long-press an element (number, text, or x/y). Returns the updated screen.",
            listOf(), "element" to int("Element number"), "text" to str("Visible text"), "x" to int("X"), "y" to int("Y")),
        tool("type_text", "Type text into an input field (element number, or the focused field if omitted). Replaces existing text. Set submit=true to press Enter/Search afterwards.",
            listOf("text"), "text" to str("Text to type"), "element" to int("Input element number"), "submit" to bool("Press enter/search after typing")),
        tool("scroll", "Scroll the screen or a scrollable element.", listOf("direction"),
            "direction" to enumStr("Direction", "up", "down", "left", "right"), "element" to int("Scrollable element number (optional)")),
        tool("swipe", "Swipe gesture between two pixel coordinates.", listOf("x1", "y1", "x2", "y2"),
            "x1" to int("start x"), "y1" to int("start y"), "x2" to int("end x"), "y2" to int("end y"), "duration_ms" to int("duration, default 400")),
        tool("press_key", "Press a system key / global action.", listOf("key"),
            "key" to enumStr("Key", "back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot", "enter", "power_dialog")),
        tool("take_screenshot", "Look at the current screen as an image (use when read_screen is not enough). Coordinates in the image map to screen pixels as described in the result."),
        tool("wait", "Wait for a page or app to load.", listOf("seconds"), "seconds" to num("Seconds to wait (max 15)")),

        tool("open_app", "Open an installed app by its name (e.g. Chrome, WhatsApp, YouTube, Settings, Camera, Gallery).", listOf("name"), "name" to str("App name")),
        tool("list_apps", "List installed apps (optionally filtered by a word).", listOf(), "filter" to str("Optional filter")),
        tool("open_url", "Open a web page / link. browser='chrome' opens it in Google Chrome.", listOf("url"),
            "url" to str("Full URL"), "browser" to enumStr("Which browser", "default", "chrome")),
        tool("play_youtube", "Search and open a video on YouTube.", listOf("query"), "query" to str("What to play")),
        tool("open_maps", "Show a place in Google Maps, or start navigation to it.", listOf("query"),
            "query" to str("Place or address"), "navigate" to bool("Start turn-by-turn navigation")),

        tool("download_file", "Download a file or image from a direct URL and save it into a folder on the phone (folder is created if needed). For images you get a preview to verify it. If the URL is a web page instead of a file, the download is rejected — find the direct file URL first.",
            listOf("url", "folder"), "url" to str("Direct URL of the file/image"),
            "folder" to str("Folder relative to internal storage, e.g. 'Pictures/Cars' or 'Download'"),
            "file_name" to str("Optional file name (extension added automatically)")),
        tool("list_files", "List files and folders in a directory (relative to internal storage, '' = root).", listOf(), "path" to str("Folder path")),
        tool("search_files", "Find files by name (case-insensitive substring) under a folder (default: whole storage).", listOf("query"),
            "query" to str("Part of the file name"), "path" to str("Folder to search in")),
        tool("create_folder", "Create a folder.", listOf("path"), "path" to str("Folder path")),
        tool("move_file", "Move a file or folder.", listOf("from", "to"), "from" to str("Source path"), "to" to str("Destination folder or full path")),
        tool("copy_file", "Copy a file or folder.", listOf("from", "to"), "from" to str("Source path"), "to" to str("Destination folder or full path")),
        tool("rename_file", "Rename a file or folder.", listOf("path", "new_name"), "path" to str("Path"), "new_name" to str("New name")),
        tool("delete_file", "Delete a file or folder. Only call with confirmed_by_user=true after the user clearly asked for or confirmed this deletion.",
            listOf("path", "confirmed_by_user"), "path" to str("Path"), "confirmed_by_user" to bool("The user explicitly asked/confirmed")),
        tool("read_text_file", "Read a text file (first 20000 characters).", listOf("path"), "path" to str("Path")),
        tool("write_text_file", "Create or overwrite (or append to) a text file.", listOf("path", "content"),
            "path" to str("Path"), "content" to str("Text"), "append" to bool("Append instead of overwrite")),
        tool("open_file", "Open a file with the matching app (image viewer, PDF reader, music player…).", listOf("path"), "path" to str("Path")),
        tool("share_file", "Open the share sheet for a file, optionally directly to an app such as WhatsApp.", listOf("path"),
            "path" to str("Path"), "app" to str("Optional app name, e.g. WhatsApp")),

        tool("search_contacts", "Search the phone's contacts by name or number.", listOf("query"), "query" to str("Name or number")),
        tool("make_call", "Place a phone call to a number or a contact name.", listOf("to"), "to" to str("Phone number or contact name")),
        tool("send_sms", "Send an SMS text message to a number or contact name.", listOf("to", "message"),
            "to" to str("Phone number or contact name"), "message" to str("Message text")),
        tool("send_whatsapp", "Open a WhatsApp chat with the message pre-filled. Afterwards call read_screen and tap the Send button to actually send.", listOf("to", "message"),
            "to" to str("Phone number (with country code) or contact name"), "message" to str("Message text")),
        tool("read_notifications", "Read recent notifications (messages, alerts) the phone received.", listOf(),
            "app" to str("Optional app name filter, e.g. WhatsApp"), "limit" to int("How many (default 15)")),
        tool("read_sms", "Read recent SMS messages from the inbox.", listOf(),
            "from" to str("Optional sender/contact filter"), "limit" to int("How many (default 10)")),

        tool("device_status", "Get battery, charging, storage, network, volume, brightness, phone model and Android version."),
        tool("set_volume", "Set a volume level.", listOf("stream", "level_percent"),
            "stream" to enumStr("Which volume", "media", "ring", "alarm", "notification", "call"), "level_percent" to int("0-100")),
        tool("set_brightness", "Set screen brightness (0-100) or auto brightness.", listOf(),
            "level_percent" to int("0-100"), "auto" to bool("Turn on auto brightness")),
        tool("flashlight", "Turn the flashlight/torch on or off.", listOf("on"), "on" to bool("true = on")),
        tool("set_ringer_mode", "Set ringer mode.", listOf("mode"), "mode" to enumStr("Mode", "normal", "vibrate", "silent")),
        tool("media_control", "Control music/video playback in any app.", listOf("action"),
            "action" to enumStr("Action", "play_pause", "play", "pause", "next", "previous", "stop")),
        tool("open_settings", "Open a settings page (then use screen tools to toggle things like Wi-Fi, Bluetooth, hotspot, airplane mode).", listOf("page"),
            "page" to enumStr("Page", "main", "wifi", "bluetooth", "internet_panel", "mobile_data", "hotspot", "airplane", "display", "sound", "battery", "storage", "apps", "app_details", "location", "notifications", "accessibility", "date_time", "language", "security", "nfc", "developer", "default_apps", "all_files_access", "overlay"),
            "package" to str("Package name, for app_details")),
        tool("set_alarm", "Set an alarm.", listOf("hour", "minute"),
            "hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Label"),
            "days" to mapOf("type" to "array", "items" to mapOf("type" to "integer"), "description" to "Repeat days 1=Sunday…7=Saturday")),
        tool("set_timer", "Start a countdown timer.", listOf("seconds"), "seconds" to int("Length in seconds"), "label" to str("Label")),
        tool("add_calendar_event", "Open the calendar to add an event (then tap Save via screen tools if needed).", listOf("title", "start"),
            "title" to str("Title"), "start" to str("Start local time, format yyyy-MM-ddTHH:mm"), "end" to str("End time, same format"),
            "location" to str("Location"), "description" to str("Notes")),
        tool("open_camera", "Open the camera app (then tap the shutter via screen tools to capture).", listOf(),
            "mode" to enumStr("Mode", "photo", "video", "selfie")),
        tool("set_clipboard", "Copy text to the clipboard.", listOf("text"), "text" to str("Text")),
        tool("get_location", "Get the phone's current approximate location and address."),
        tool("remember", "Save a fact about the user for future conversations.", listOf("fact"), "fact" to str("The fact to remember")),
        tool("forget", "Remove a remembered fact by its number.", listOf("number"), "number" to int("Memory number")),
    )

    private fun tool(name: String, desc: String, required: List<String> = emptyList(), vararg props: Pair<String, Map<String, Any>>): Tool {
        val p = Tool.InputSchema.Properties.builder()
        for ((k, v) in props) p.putAdditionalProperty(k, JsonValue.from(v))
        return Tool.builder()
            .name(name)
            .description(desc)
            .inputSchema(Tool.InputSchema.builder().properties(p.build()).required(required).build())
            .build()
    }

    private fun str(d: String): Map<String, Any> = mapOf("type" to "string", "description" to d)
    private fun int(d: String): Map<String, Any> = mapOf("type" to "integer", "description" to d)
    private fun num(d: String): Map<String, Any> = mapOf("type" to "number", "description" to d)
    private fun bool(d: String): Map<String, Any> = mapOf("type" to "boolean", "description" to d)
    private fun enumStr(d: String, vararg v: String): Map<String, Any> = mapOf("type" to "string", "enum" to v.toList(), "description" to d)
}

fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
