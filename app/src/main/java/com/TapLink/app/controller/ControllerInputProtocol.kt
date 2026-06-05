package com.TapLinkX3.app.controller

import java.util.LinkedHashMap

object ControllerReliableMessageDeduper {
    private const val CACHE_SIZE = 256
    private val recentMessageIds =
            object : LinkedHashMap<String, Unit>(CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
                        size > CACHE_SIZE
            }

    fun shouldDispatch(messageId: String?): Boolean {
        if (messageId.isNullOrEmpty()) return true
        synchronized(recentMessageIds) {
            if (recentMessageIds.containsKey(messageId)) return false
            recentMessageIds[messageId] = Unit
        }
        return true
    }
}

interface ControllerInputListener {
    fun onControllerConnected(name: String, address: String)
    fun onControllerDisconnected()
    fun onControllerModeChanged(mode: ControllerMode)
    fun onControllerKey(key: String)
    fun onControllerGroqApiKey(key: String)
    fun onControllerAirMouseRay(x: Float, y: Float, select: Boolean)
    fun onControllerTrackpadDelta(dx: Float, dy: Float)
    fun onControllerTrackpadGesture(
            action: ControllerTrackpadAction,
            dx: Float,
            dy: Float,
            pointerCount: Int
    )
    fun onControllerScroll(dy: Float)
    fun onControllerTap()
    fun onControllerTouch(action: ControllerTouchAction, x: Float, y: Float)
    fun onControllerAiPrompt(prompt: String)
}

enum class ControllerMode {
    AIR_MOUSE,
    TRACKPAD,
    META
}

enum class ControllerTrackpadAction {
    DOWN,
    MOVE,
    POINTER,
    UP,
    CANCEL
}

enum class ControllerTouchAction {
    DOWN,
    MOVE,
    UP,
    CANCEL
}
