package com.TapLinkX3.app.controller

import android.util.Log
import android.view.Choreographer

/**
 * A headless manager that handles high-frequency Bluetooth inputs and keeps cursor updates
 * aligned with the active controller mode.
 */
class GlassesCursorController(private val screenWidth: Int, private val screenHeight: Int) :
        ControllerInputListener {

    private var currentMode = ControllerMode.TRACKPAD
    private val cursorWidth = minOf(screenWidth.toFloat(), LEFT_SCREEN_WIDTH)

    // 1. The target coordinates (where the Bluetooth tells us to be)
    private var targetX = cursorWidth / 2f
    private var targetY = screenHeight / 2f
    private var targetSelect = false
    private var targetSelectUpdated = false

    // 2. The actual coordinates rendered by the cursor
    private var currentX = targetX
    private var currentY = targetY

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

                    // Keep the network targets mathematically within the screen bounds.
                    targetX = targetX.coerceIn(0f, cursorWidth)
                    targetY = targetY.coerceIn(0f, screenHeight.toFloat())

                    if (currentX != lastSentX || currentY != lastSentY || targetSelectUpdated) {
                        val select = targetSelect
                        onUpdateListener?.invoke(currentX, currentY, select)

                        lastSentX = currentX
                        lastSentY = currentY
                        lastSentSelect = select
                        targetSelectUpdated = false
                    }

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
        onControllerTrackpadGesture(ControllerTrackpadAction.MOVE, dx, dy, 1)
    }

    override fun onControllerTrackpadGesture(
            action: ControllerTrackpadAction,
            dx: Float,
            dy: Float,
            pointerCount: Int
    ) {
        if (currentMode != ControllerMode.TRACKPAD) return
        if (action != ControllerTrackpadAction.MOVE || pointerCount >= 2) return

        targetX = (targetX + dx).coerceIn(0f, cursorWidth)
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

        targetX = (x * cursorWidth).coerceIn(0f, cursorWidth)
        targetY = (y * screenHeight).coerceIn(0f, screenHeight.toFloat())
        targetSelect = select

        // Air mouse should follow the latest phone rotation sample immediately. Smoothing already
        // happens at the phone sensor/Bluetooth pacing layer, so lerping here adds visible lag.
        currentX = targetX
        currentY = targetY

        if (currentX != lastSentX || currentY != lastSentY || select != lastSentSelect) {
            onUpdateListener?.invoke(currentX, currentY, select)
            lastSentX = currentX
            lastSentY = currentY
            lastSentSelect = select
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

    private companion object {
        private const val LEFT_SCREEN_WIDTH = 640f
    }
}
