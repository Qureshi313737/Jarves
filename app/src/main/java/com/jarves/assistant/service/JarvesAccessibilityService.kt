package com.jarves.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.jarves.assistant.Core
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Gives Jarves "eyes and hands": read any app's screen, tap, type, scroll, swipe,
 * press system keys and take screenshots. The user enables it in Settings > Accessibility.
 */
class JarvesAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvesAccessibilityService? = null
            private set
    }

    @Volatile private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Core.init(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun currentPackage(): String? =
        try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null } ?: lastPackage

    /** Performs a gesture along [path] and waits until it finishes. */
    fun gesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10_000))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val dispatched = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
        }, null)
        if (!dispatched) return false
        done.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    fun tapAt(x: Float, y: Float, long: Boolean = false): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(p, if (long) 900 else 60)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(p, durationMs)
    }

    /** Screenshot of the whole display (Android 11+). */
    fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val result = AtomicReference<Bitmap?>(null)
        val done = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    result.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                    screenshot.hardwareBuffer.close()
                } catch (e: Exception) {
                    // ignore, returns null
                }
                done.countDown()
            }

            override fun onFailure(errorCode: Int) { done.countDown() }
        })
        done.await(5, TimeUnit.SECONDS)
        return result.get()
    }
}
