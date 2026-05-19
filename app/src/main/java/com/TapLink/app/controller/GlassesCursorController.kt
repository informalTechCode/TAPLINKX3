package com.TapLinkX3.app.controller

import android.util.Log
import android.view.Choreographer
import kotlin.math.sqrt

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

                    if (currentMode == ControllerMode.AIR_MOUSE) {
                        updateAirMouseFrame()
                    } else if (currentMode == ControllerMode.TRACKPAD) {
                        updateTrackpadFrame()
                    }

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

        when (action) {
            ControllerTrackpadAction.MOVE -> {
                if (pointerCount >= 2) return

                targetX = (targetX + dx).coerceIn(0f, cursorWidth)
                targetY = (targetY + dy).coerceIn(0f, screenHeight.toFloat())

                if (!isRunning) {
                    snapToTargetAndEmit()
                }
            }
            ControllerTrackpadAction.UP,
            ControllerTrackpadAction.CANCEL -> {
                // Movement is relative, so snap at gesture end to prevent any smoothed tail.
                snapToTargetAndEmit()
            }
            ControllerTrackpadAction.DOWN,
            ControllerTrackpadAction.POINTER -> Unit
        }
    }

    override fun onControllerAirMouseRay(x: Float, y: Float, select: Boolean) {
        if (currentMode != ControllerMode.AIR_MOUSE) return

        targetX = (x * cursorWidth).coerceIn(0f, cursorWidth)
        targetY = (y * screenHeight).coerceIn(0f, screenHeight.toFloat())
        targetSelect = select
        targetSelectUpdated = true

        if (!isRunning) {
            currentX = targetX
            currentY = targetY
            onUpdateListener?.invoke(currentX, currentY, select)
            lastSentX = currentX
            lastSentY = currentY
            lastSentSelect = select
            targetSelectUpdated = false
        }
    }

    private fun updateAirMouseFrame() {
        val dx = targetX - currentX
        val dy = targetY - currentY
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < AIR_MOUSE_SNAP_DISTANCE_PX) {
            currentX = targetX
            currentY = targetY
            return
        }

        val alpha =
                when {
                    distance >= AIR_MOUSE_FAST_DISTANCE_PX -> 0.94f
                    distance >= AIR_MOUSE_MEDIUM_DISTANCE_PX -> 0.78f
                    distance >= AIR_MOUSE_SMALL_DISTANCE_PX -> 0.58f
                    else -> 0.34f
                }

        currentX += dx * alpha
        currentY += dy * alpha
    }

    private fun updateTrackpadFrame() {
        val dx = targetX - currentX
        val dy = targetY - currentY
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < TRACKPAD_SNAP_DISTANCE_PX) {
            currentX = targetX
            currentY = targetY
            return
        }

        val alpha =
                when {
                    distance >= TRACKPAD_FAST_DISTANCE_PX -> 0.92f
                    distance >= TRACKPAD_MEDIUM_DISTANCE_PX -> 0.78f
                    distance >= TRACKPAD_SMALL_DISTANCE_PX -> 0.62f
                    else -> 0.42f
                }

        currentX += dx * alpha
        currentY += dy * alpha
    }

    private fun snapToTargetAndEmit() {
        currentX = targetX
        currentY = targetY

        if (currentX != lastSentX || currentY != lastSentY) {
            onUpdateListener?.invoke(currentX, currentY, targetSelect)

            lastSentX = currentX
            lastSentY = currentY
            lastSentSelect = targetSelect
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

    private companion object {
        private const val LEFT_SCREEN_WIDTH = 640f
        private const val AIR_MOUSE_SNAP_DISTANCE_PX = 0.35f
        private const val AIR_MOUSE_SMALL_DISTANCE_PX = 4f
        private const val AIR_MOUSE_MEDIUM_DISTANCE_PX = 18f
        private const val AIR_MOUSE_FAST_DISTANCE_PX = 72f
        private const val TRACKPAD_SNAP_DISTANCE_PX = 0.25f
        private const val TRACKPAD_SMALL_DISTANCE_PX = 3f
        private const val TRACKPAD_MEDIUM_DISTANCE_PX = 12f
        private const val TRACKPAD_FAST_DISTANCE_PX = 48f
    }
}
