package com.TapLinkX3.app.controller

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
    TRACKPAD
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
