package com.jarves.assistant.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps the latest notifications so Jarves can read messages aloud ("mere naye messages batao"). */
class JarvesNotificationListener : NotificationListenerService() {

    data class Item(val pkg: String, val appName: String, val title: String, val text: String, val time: Long)

    companion object {
        @Volatile var connected = false
            private set
        private val items = CopyOnWriteArrayList<Item>()

        fun recent(): List<Item> = items.reversed()
    }

    override fun onListenerConnected() {
        connected = true
        try {
            activeNotifications?.sortedBy { it.postTime }?.forEach { add(it) }
        } catch (e: Exception) { }
    }

    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { add(it) } }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        val item = Item(sbn.packageName, app, title, text, sbn.postTime)
        items.removeAll { it.pkg == item.pkg && it.title == item.title && it.text == item.text }
        items.add(item)
        while (items.size > 80) items.removeAt(0)
    }
}
