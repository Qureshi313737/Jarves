package com.jarves.assistant

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.jarves.assistant.service.JarvesAccessibilityService
import com.jarves.assistant.service.JarvesNotificationListener

/**
 * Which optional components this build contains. The FULL edition declares the accessibility
 * service and notification listener; the LITE edition leaves them out so it installs even on
 * phones where Google Play Protect blocks sideloaded apps that ask for those powers.
 */
object Features {
    private fun declared(ctx: Context, cls: Class<*>): Boolean = try {
        ctx.packageManager.getServiceInfo(ComponentName(ctx, cls), PackageManager.GET_META_DATA)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun screenControl(ctx: Context): Boolean = declared(ctx, JarvesAccessibilityService::class.java)

    fun notificationReading(ctx: Context): Boolean = declared(ctx, JarvesNotificationListener::class.java)

    fun isLite(ctx: Context): Boolean = !screenControl(ctx)
}
