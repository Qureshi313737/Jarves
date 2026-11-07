package com.jarves.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.jarves.assistant.service.JarvesNotificationListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Contacts, calls, SMS, WhatsApp and notifications. */
class PhoneTools(private val ctx: Context) {

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun missing(what: String) = ToolOutput.error(
        "Permission for $what is not granted. Ask the user to open Jarves > Setup and allow it."
    )

    data class Contact(val name: String, val number: String)

    private fun findContacts(query: String, limit: Int = 10): List<Contact> {
        if (!granted(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Contact>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val args = arrayOf("%$query%", "%$query%")
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, sel, args,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext() && out.size < limit) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val key = name + num.filter { it.isDigit() }.takeLast(10)
                if (seen.add(key)) out.add(Contact(name, num))
            }
        }
        return out
    }

    /** Returns a phone number for "+91…", "98…" or a contact name; or an error message. */
    private fun resolveNumber(to: String): Pair<String?, String?> {
        val t = to.trim()
        val digits = t.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5 && digits.length >= t.length - 4) return digits to null
        if (!granted(Manifest.permission.READ_CONTACTS)) return null to "Contacts permission is not granted, so I can't look up \"$t\". Ask for the number or for the permission."
        val found = findContacts(t)
        if (found.isEmpty()) return null to "No contact named \"$t\"."
        val exact = found.filter { it.name.equals(t, ignoreCase = true) }
        val distinct = (exact.ifEmpty { found }).distinctBy { it.number.filter { ch -> ch.isDigit() }.takeLast(10) }
        if (distinct.size > 1) {
            return null to "Several contacts match \"$t\": " + distinct.take(6).joinToString("; ") { "${it.name} ${it.number}" } + ". Ask the user which one."
        }
        return distinct[0].number to null
    }

    fun searchContacts(query: String): ToolOutput {
        if (!granted(Manifest.permission.READ_CONTACTS)) return missing("contacts")
        val r = findContacts(query, 20)
        return if (r.isEmpty()) ToolOutput.ok("No contacts match \"$query\".")
        else ToolOutput.ok(r.joinToString("\n") { "${it.name}: ${it.number}" })
    }

    fun call(to: String): ToolOutput {
        val (num, err) = resolveNumber(to)
        if (num == null) return ToolOutput.error(err ?: "Unknown number")
        val uri = Uri.parse("tel:" + Uri.encode(num))
        return try {
            if (granted(Manifest.permission.CALL_PHONE)) {
                ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolOutput.ok("Calling $num.")
            } else {
                ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolOutput.ok("Phone permission not granted, so the dialer was opened with $num — tap the call button (read_screen + tap) to call.")
            }
        } catch (e: Exception) {
            ToolOutput.error("Could not start the call: ${e.message}")
        }
    }

    fun sendSms(to: String, message: String): ToolOutput {
        if (message.isBlank()) return ToolOutput.error("message is empty")
        val (num, err) = resolveNumber(to)
        if (num == null) return ToolOutput.error(err ?: "Unknown number")
        if (!granted(Manifest.permission.SEND_SMS)) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))
                .putExtra("sms_body", message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(i)
                ToolOutput.ok("SMS permission not granted, so the messaging app was opened with the text filled in. Use read_screen and tap Send.")
            } catch (e: Exception) { missing("SMS") }
        }
        return try {
            @Suppress("DEPRECATION")
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)!! else SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            sms.sendMultipartTextMessage(num, null, parts, null, null)
            ToolOutput.ok("SMS sent to $num.")
        } catch (e: Exception) {
            ToolOutput.error("SMS failed: ${e.message}")
        }
    }

    fun openWhatsApp(to: String, message: String): ToolOutput {
        val (num, err) = resolveNumber(to)
        if (num == null) return ToolOutput.error(err ?: "Unknown number")
        var digits = num.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits" // assume India when no country code is given
        if (digits.startsWith("0") && digits.length == 11) digits = "91" + digits.substring(1)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(message))
        val pm = ctx.packageManager
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pm.getLaunchIntentForPackage(it) != null }
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        return try {
            ctx.startActivity(i)
            Thread.sleep(2500)
            ToolOutput.ok("WhatsApp chat with +$digits opened with the message typed. Now call read_screen and tap the Send button to send it.")
        } catch (e: Exception) {
            ToolOutput.error("Could not open WhatsApp: ${e.message}")
        }
    }

    fun readNotifications(app: String, limit: Int): ToolOutput {
        if (!JarvesNotificationListener.connected) {
            return ToolOutput.error("Notification access is off. Ask the user to enable it in Jarves > Setup (Notification access).")
        }
        var list = JarvesNotificationListener.recent()
        if (app.isNotBlank()) {
            val a = app.lowercase()
            list = list.filter { it.appName.lowercase().contains(a) || it.pkg.lowercase().contains(a) }
        }
        if (list.isEmpty()) return ToolOutput.ok("No recent notifications${if (app.isNotBlank()) " from $app" else ""}.")
        val fmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        return ToolOutput.ok(list.take(limit.coerceIn(1, 50)).joinToString("\n") {
            "[${fmt.format(Date(it.time))}] ${it.appName}: ${it.title} — ${it.text}".take(400)
        })
    }

    fun readSms(from: String, limit: Int): ToolOutput {
        if (!granted(Manifest.permission.READ_SMS)) return missing("reading SMS")
        val out = StringBuilder()
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH)
        var n = 0
        val sel = if (from.isNotBlank()) "${Telephony.Sms.ADDRESS} LIKE ?" else null
        val args = if (from.isNotBlank()) arrayOf("%$from%") else null
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            sel, args, Telephony.Sms.DEFAULT_SORT_ORDER,
        )?.use { c ->
            while (c.moveToNext() && n < limit.coerceIn(1, 50)) {
                out.append('[').append(fmt.format(Date(c.getLong(2)))).append("] ")
                    .append(c.getString(0)).append(": ").append(c.getString(1)?.take(500)).append('\n')
                n++
            }
        }
        return ToolOutput.ok(if (n == 0) "No SMS found." else out.toString())
    }
}
