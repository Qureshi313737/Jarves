package com.jarves.assistant

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** Tiny helpers for building the UI in code (no XML layouts). */
object Ui {
    const val BG = 0xFF0E1117.toInt()
    const val CARD = 0xFF1A1F2B.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFF3D5AFE.toInt()
    const val RED = 0xFFE53935.toInt()
    const val GREEN = 0xFF00C853.toInt()
    const val USER_BUBBLE = 0xFF2B3A67.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(color)
    }

    fun text(ctx: Context, s: String, size: Float = 15f, color: Int = TEXT): TextView = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
    }

    fun button(ctx: Context, label: String, color: Int = ACCENT, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 14f
        background = rounded(ctx, color, 20)
        setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
        minHeight = dp(ctx, 40)
        minimumHeight = dp(ctx, 40)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}
