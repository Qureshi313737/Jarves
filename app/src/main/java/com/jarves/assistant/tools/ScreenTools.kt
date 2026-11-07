package com.jarves.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.jarves.assistant.service.JarvesAccessibilityService
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Screen reading and control on top of [JarvesAccessibilityService]. */
class ScreenTools(private val ctx: Context) {

    /** Elements from the most recent read_screen, indexed by their number. */
    private var elements: List<AccessibilityNodeInfo> = emptyList()

    private fun service(): JarvesAccessibilityService? = JarvesAccessibilityService.instance

    private val notEnabled = ToolOutput.error(
        "Screen control is OFF. Tell the user to open Jarves > Setup and enable 'Jarves' in Accessibility settings " +
            "(on Android 13+ they may first need App info > ⋮ > 'Allow restricted settings')."
    )

    private fun screenSize(): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    fun readScreen(): ToolOutput {
        val s = service() ?: return notEnabled
        return ToolOutput.ok(dump(s))
    }

    private fun dump(s: JarvesAccessibilityService, maxItems: Int = 180): String {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { s.rootInActiveWindow?.let { roots.add(it) } } catch (e: Exception) { }
        if (roots.isEmpty()) {
            try {
                for (w in s.windows) w.root?.let { roots.add(it) }
            } catch (e: Exception) { }
        }
        if (roots.isEmpty()) return "Could not read the screen (no active window). Try again after a moment, or use take_screenshot."

        val (sw, sh) = screenSize()
        val screenRect = Rect(0, 0, sw, sh)
        val out = StringBuilder()
        val pkg = roots[0].packageName?.toString() ?: "?"
        out.append("App: ").append(pkg).append(" (").append(appLabel(pkg)).append(") | screen ").append(sw).append('x').append(sh).append('\n')
        val list = ArrayList<AccessibilityNodeInfo>()
        val r = Rect()

        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (list.size >= maxItems || depth > 60) return
            if (!n.isVisibleToUser) return
            n.getBoundsInScreen(r)
            val onScreen = Rect.intersects(r, screenRect) && r.width() > 0 && r.height() > 0
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
            val interesting = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable ||
                text.isNotEmpty() || desc.isNotEmpty()
            if (onScreen && interesting) {
                list.add(n)
                val idx = list.size
                val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
                out.append('[').append(idx).append("] ").append(cls)
                if (text.isNotEmpty()) out.append(" \"").append(text.take(90).replace('\n', ' ')).append('"')
                if (desc.isNotEmpty() && desc != text) out.append(" (desc: ").append(desc.take(70).replace('\n', ' ')).append(')')
                if (hint.isNotEmpty() && hint != text) out.append(" {hint: ").append(hint.take(50)).append('}')
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("click")
                if (n.isLongClickable) flags.add("longclick")
                if (n.isEditable) flags.add("input")
                if (n.isScrollable) flags.add("scroll")
                if (n.isCheckable) flags.add(if (n.isChecked) "ON" else "OFF")
                if (n.isFocused) flags.add("focused")
                if (!n.isEnabled) flags.add("disabled")
                if (n.isSelected) flags.add("selected")
                if (flags.isNotEmpty()) out.append(" [").append(flags.joinToString(",")).append(']')
                n.viewIdResourceName?.substringAfter(":id/")?.let { if (it.length < 40) out.append(" #").append(it) }
                out.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                visit(c, depth + 1)
            }
        }
        for (root in roots) visit(root, 0)
        if (list.isEmpty()) out.append("(no readable elements — the app may draw its own UI; use take_screenshot)\n")
        if (list.size >= maxItems) out.append("(list truncated; scroll to see more)\n")
        elements = list
        return out.toString()
    }

    private fun appLabel(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun element(n: Int?): AccessibilityNodeInfo? {
        if (n == null) return null
        return elements.getOrNull(n - 1)?.takeIf { try { it.refresh() } catch (e: Exception) { false } }
    }

    private fun findByText(s: JarvesAccessibilityService, text: String): AccessibilityNodeInfo? {
        val root = s.rootInActiveWindow ?: return null
        val found = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        if (found.isEmpty()) return null
        val t = text.lowercase()
        return found.sortedWith(compareBy<AccessibilityNodeInfo>(
            { n -> val nt = (n.text ?: n.contentDescription)?.toString()?.lowercase()?.trim(); if (nt == t) 0 else 1 },
            { n -> if (n.isClickable) 0 else 1 },
        )).first()
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (if (long) cur.isLongClickable else cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun afterAction(s: JarvesAccessibilityService, msg: String, delayMs: Long = 1200): ToolOutput {
        Thread.sleep(delayMs)
        return ToolOutput.ok(msg + "\n\nScreen now:\n" + dump(s, 120))
    }

    fun tap(a: JSONObject, long: Boolean): ToolOutput {
        val s = service() ?: return notEnabled
        val idx = a.optIntOrNull("element")
        val text = a.optString("text")
        val node = when {
            idx != null -> element(idx) ?: return ToolOutput.error("Element $idx not found — call read_screen again.")
            text.isNotEmpty() -> findByText(s, text)
            else -> null
        }
        if (node == null) {
            if (a.has("x") && a.has("y")) {
                val ok = s.tapAt(a.getInt("x").toFloat(), a.getInt("y").toFloat(), long)
                return if (ok) afterAction(s, "Tapped at ${a.getInt("x")},${a.getInt("y")}.") else ToolOutput.error("Gesture failed.")
            }
            return ToolOutput.error(if (text.isNotEmpty()) "No visible element with text \"$text\". Use read_screen." else "Give element, text or x/y.")
        }
        val target = clickableAncestor(node, long)
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val label = (node.text ?: node.contentDescription ?: "element").toString().take(40)
        if (target != null && target.performAction(action)) {
            return afterAction(s, "Tapped \"$label\".")
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        val ok = s.tapAt(r.exactCenterX(), r.exactCenterY(), long)
        return if (ok) afterAction(s, "Tapped \"$label\" at ${r.centerX()},${r.centerY()}.") else ToolOutput.error("Could not tap \"$label\".")
    }

    fun typeText(text: String, idx: Int?, submit: Boolean): ToolOutput {
        val s = service() ?: return notEnabled
        var node: AccessibilityNodeInfo? = if (idx != null) element(idx) else null
        if (node == null) node = try { s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
        if (node == null) {
            dump(s)
            node = elements.firstOrNull { it.isEditable }
        }
        if (node == null) return ToolOutput.error("No input field found. Tap the search/text box first.")
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(300)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ToolOutput.error("This field did not accept text. Try tapping it first, or use another field.")
        }
        var msg = "Typed \"${text.take(60)}\"."
        if (submit) msg += " " + pressEnter(s, node)
        return afterAction(s, msg, if (submit) 1800 else 600)
    }

    private fun pressEnter(s: JarvesAccessibilityService, node: AccessibilityNodeInfo?): String {
        if (Build.VERSION.SDK_INT >= 30) {
            val n = node ?: try { s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
            if (n != null && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return "Pressed enter."
        }
        return "Could not press enter automatically — tap the search/send button instead."
    }

    fun scroll(direction: String, idx: Int?): ToolOutput {
        val s = service() ?: return notEnabled
        var node = element(idx)
        if (node == null) {
            dump(s)
            val r = Rect()
            node = elements.filter { it.isScrollable }.maxByOrNull { it.getBoundsInScreen(r); r.width() * r.height() }
        }
        if (node != null) {
            val dirAction = when (direction) {
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            }
            if (node.actionList.any { it.id == dirAction.id } && node.performAction(dirAction.id)) {
                return afterAction(s, "Scrolled $direction.", 900)
            }
            val fallback = if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (node.performAction(fallback)) return afterAction(s, "Scrolled $direction.", 900)
        }
        // No scrollable node (e.g. web content / custom views): swipe instead.
        val (w, h) = screenSize()
        val cx = w / 2f
        val cy = h / 2f
        val ok = when (direction) {
            "up" -> s.swipe(cx, h * 0.3f, cx, h * 0.75f, 350)
            "left" -> s.swipe(w * 0.2f, cy, w * 0.8f, cy, 350)
            "right" -> s.swipe(w * 0.8f, cy, w * 0.2f, cy, 350)
            else -> s.swipe(cx, h * 0.75f, cx, h * 0.3f, 350)
        }
        return if (ok) afterAction(s, "Swiped to scroll $direction.", 900) else ToolOutput.error("Scroll failed.")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): ToolOutput {
        val s = service() ?: return notEnabled
        val ok = s.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration)
        return if (ok) afterAction(s, "Swiped.", 800) else ToolOutput.error("Swipe failed.")
    }

    fun pressKey(key: String): ToolOutput {
        val s = service() ?: return notEnabled
        if (key == "enter") return afterAction(s, pressEnter(s, null), 1500)
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return ToolOutput.error("Needs Android 9+")
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return ToolOutput.error("Needs Android 9+")
            else -> return ToolOutput.error("Unknown key $key")
        }
        return if (s.performGlobalAction(action)) afterAction(s, "Pressed $key.", 1000) else ToolOutput.error("Could not press $key.")
    }

    fun screenshot(): ToolOutput {
        val s = service() ?: return notEnabled
        if (Build.VERSION.SDK_INT < 30) return ToolOutput.error("Screenshots for the assistant need Android 11+. Use read_screen.")
        val bmp = s.screenshot() ?: return ToolOutput.error("Screenshot failed (some apps block screenshots, e.g. banking apps).")
        val maxSide = 1280f
        val scale = minOf(1f, maxSide / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val factor = 1f / scale
        return ToolOutput(
            "Screenshot ${scaled.width}x${scaled.height} of a ${bmp.width}x${bmp.height} screen. " +
                "To tap a point seen in the image, multiply its coordinates by ${"%.3f".format(factor)}.",
            imageJpegBase64 = b64,
        )
    }

    fun waitSeconds(sec: Double): ToolOutput {
        val ms = (sec.coerceIn(0.2, 15.0) * 1000).toLong()
        Thread.sleep(ms)
        val s = service()
        return if (s != null) ToolOutput.ok("Waited ${sec}s.\n\nScreen now:\n" + dump(s, 120)) else ToolOutput.ok("Waited ${sec}s.")
    }
}
