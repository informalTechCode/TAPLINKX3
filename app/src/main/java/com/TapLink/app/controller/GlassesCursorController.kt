package com.TapLinkX3.app.controller

import android.util.Log
import android.view.Choreographer
import kotlin.math.abs

/**
 * A headless manager that handles high-frequency Bluetooth inputs, smoothly interpolates through
 * network jitter, and syncs the output perfectly to the display refresh rate (VSYNC).
 */
class GlassesCursorController(private val screenWidth: Int, private val screenHeight: Int) :
        ControllerInputListener {

    private var currentMode = ControllerMode.TRACKPAD

    // 1. The target coordinates (where the Bluetooth tells us to be)
    private var targetX = screenWidth / 2f
    private var targetY = screenHeight / 2f
    private var targetSelect = false
    private var targetSelectUpdated = false

    // 2. The actual smoothed coordinates (where the cursor actually renders)
    private var currentX = targetX
    private var currentY = targetY

    // 3. The smoothing factor (0.1f to 1.0f).
    // 0.45f is the sweet spot: it completely hides Bluetooth packet-batching
    // without feeling laggy or "floaty" to the user.
    private val SMOOTHING_FACTOR = 0.45f

    private var onUpdateListener: ((Float, Float, Boolean) -> Unit)? = null

    // Track what we sent last to avoid spamming the listener when idle
    private var lastSentX = -1f
    private var lastSentY = -1f
    private var lastSentSelect = false

    fun setOnUpdateListener(listener: (Float, Float, Boolean) -> Unit) {
        this.onUpdateListener = listener
    }

    private var isRunning = false

    private val frameCallback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!isRunning) return

                    // 1. Keep the network targets mathematically within the screen bounds
                    targetX = targetX.coerceIn(0f, screenWidth.toFloat())
                    targetY = targetY.coerceIn(0f, screenHeight.toFloat())

                    // 2. Interpolate (Lerp) the current position toward the target
                    // This is the magic line that eats all the network jitter!
                    currentX += (targetX - currentX) * SMOOTHING_FACTOR
                    currentY += (targetY - currentY) * SMOOTHING_FACTOR

                    // Snap to target if the difference is microscopic to stop infinite math
                    if (abs(targetX - currentX) < 0.1f) currentX = targetX
                    if (abs(targetY - currentY) < 0.1f) currentY = targetY

                    // 3. Trigger callback ONLY if the *current* position actually changed
                    if (currentX != lastSentX || currentY != lastSentY || targetSelectUpdated) {
                        val select = targetSelect
                        onUpdateListener?.invoke(currentX, currentY, select)

                        lastSentX = currentX
                        lastSentY = currentY
                        lastSentSelect = select
                        targetSelectUpdated = false
                    }

                    // 4. Schedule the next frame
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }

    fun start() {
        if (!isRunning) {
            isRunning = true

            // If the cursor is currently off-screen or out of sync when we start,
            // reset the current position to the target to avoid a huge initial "swoop"
            currentX = targetX
            currentY = targetY

            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onControllerTrackpadDelta(dx: Float, dy: Float) {
        if (currentMode != ControllerMode.TRACKPAD) return

        targetX = (targetX + dx).coerceIn(0f, screenWidth.toFloat())
        targetY = (targetY + dy).coerceIn(0f, screenHeight.toFloat())

        // Trackpad should feel direct, not eased toward a delayed target.
        currentX = targetX
        currentY = targetY

        if (currentX != lastSentX || currentY != lastSentY) {
            onUpdateListener?.invoke(currentX, currentY, targetSelect)

            lastSentX = currentX
            lastSentY = currentY
            lastSentSelect = targetSelect
        }
    }

    override fun onControllerAirMouseRay(x: Float, y: Float, select: Boolean) {
        if (currentMode != ControllerMode.AIR_MOUSE) return

        targetX = (x * screenWidth).coerceIn(0f, screenWidth.toFloat())
        targetY = (y * screenHeight).coerceIn(0f, screenHeight.toFloat())
        targetSelect = select
        targetSelectUpdated = true

        // If you want air mouse immediate too, bypass the lerp here as well.
        currentX = targetX
        currentY = targetY

        if (currentX != lastSentX || currentY != lastSentY || select != lastSentSelect) {
            onUpdateListener?.invoke(currentX, currentY, select)

            lastSentX = currentX
            lastSentY = currentY
            lastSentSelect = select
            targetSelectUpdated = false
        }
    }

    override fun onControllerModeChanged(mode: ControllerMode) {
        Log.d("CursorController", "Mode changed to: $mode")
        currentMode = mode
    }

    // --- Unused Interface Methods (Handled by MainActivity) ---
    override fun onControllerTap() {}
    override fun onControllerScroll(dy: Float) {}
    override fun onControllerTouch(action: ControllerTouchAction, x: Float, y: Float) {}
    override fun onControllerConnected(name: String, address: String) {}
    override fun onControllerDisconnected() {}
    override fun onControllerKey(key: String) {}
    override fun onControllerGroqApiKey(key: String) {}
    override fun onControllerAiPrompt(prompt: String) {}
}
