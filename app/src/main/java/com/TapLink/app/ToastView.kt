package com.TapLinkX3.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class ToastView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val messageView: TextView

    init {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            setMargins(0, 0, 0, 80) // Raise above bottom navigation
        }

        messageView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 16, 32, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000")) // Semi-transparent black
                cornerRadius = 32f
            }
            gravity = Gravity.CENTER
        }

        addView(messageView)
        visibility = View.GONE
        elevation = 2000f // Ensure it's very high z-index
    }

    fun show(message: String, duration: Long = 2000L) {
        messageView.text = message
        visibility = View.VISIBLE
        bringToFront()

        // Cancel any previous hide callbacks
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, duration)
    }

    private val hideRunnable = Runnable {
        visibility = View.GONE
    }
}
