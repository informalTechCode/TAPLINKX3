package com.TapLinkX3.controller

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {
    private val controllerServer by lazy { TapLinkBluetoothControllerServer(this) }
    private val phoneGroqClient = PhoneGroqClient()
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    private lateinit var recenterButton: Button
    private lateinit var trackpad: View
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var phoneKeyboardInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var aiInput: EditText
    private lateinit var aiResponseText: TextView
    private lateinit var aiAskButton: Button
    private var suppressKeyboardTextChange = false

    private var trackpadSensitivity = DEFAULT_TRACKPAD_SENSITIVITY
    private var airMouseSensitivity = 1.0f

    private var mode = TapLinkBluetoothControllerServer.ControllerMode.TRACKPAD
    private var hasBaseline = false
    private var baselineYaw = 0f
    private var baselinePitch = 0f
    private var lastPadX = 0f
    private var lastPadY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var totalTouchDistance = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTrackpadSensitivity =
                prefs.getFloat(KEY_TRACKPAD_SENSITIVITY, DEFAULT_TRACKPAD_SENSITIVITY)
        trackpadSensitivity =
                if (abs(savedTrackpadSensitivity - LEGACY_TRACKPAD_SENSITIVITY) < 0.001f ||
                                abs(savedTrackpadSensitivity - PREVIOUS_DEFAULT_TRACKPAD_SENSITIVITY) < 0.001f
                ) {
                    DEFAULT_TRACKPAD_SENSITIVITY
                } else {
                    savedTrackpadSensitivity
                }
        if (trackpadSensitivity != savedTrackpadSensitivity) {
            prefs.edit().putFloat(KEY_TRACKPAD_SENSITIVITY, trackpadSensitivity).apply()
        }
        airMouseSensitivity = prefs.getFloat(KEY_AIR_MOUSE_SENSITIVITY, 1.0f)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor =
                sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        setContentView(buildContentView())
        controllerServer.onConnectionChanged = { connected ->
            runOnUiThread {
                trackpad.alpha = if (connected) 1f else 0.5f
                val colorStr = if (connected) "#10b981" else "#3b82f6"
                val shape =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.parseColor(colorStr))
                        }
                startButton.background =
                        android.graphics.drawable.RippleDrawable(
                                android.content.res.ColorStateList.valueOf(
                                        Color.argb(76, 255, 255, 255)
                                ),
                                shape,
                                null
                        )
                startButton.text = if (connected) "✓" else "ᛒ"
                if (connected) {
                    sendSavedGroqApiKeyIfPresent()
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        controllerServer.onStatusChanged = { status -> runOnUiThread { statusText.text = status } }
        controllerServer.onKeyboardVisibilityRequested = { visible ->
            runOnUiThread { setPhoneKeyboardVisible(visible) }
        }
        controllerServer.onGroqApiKeyReceived = { key ->
            runOnUiThread {
                if (key.isNotBlank()) {
                    val currentKey = prefs.getString(KEY_GROQ_API_KEY, "")
                    if (currentKey.isNullOrBlank()) {
                        prefs.edit().putString(KEY_GROQ_API_KEY, key).apply()
                        apiKeyInput.setText(key)
                        Toast.makeText(
                                        this@MainActivity,
                                        "Groq API key synced from glasses",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
            }
        }
        if (hasBluetoothPermission()) {
            startBluetoothController()
        } else {
            ensureBluetoothPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerServer.stop()
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                            grantResults.all {
                                it == android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
            ) {
                startBluetoothController()
            }
        }
    }

    // Trackpad movement keeps fractional carry between packets, but is emitted immediately so
    // cursor motion follows the finger instead of waiting for the next phone display frame.
    private var pendingTrackpadDx = 0f
    private var pendingTrackpadDy = 0f
    private var hasPendingTrackpadDelta = false
    private var maxPointerCountInGesture = 1

    // Air mouse duplicate suppression and frame-paced output.
    private var lastSentAirMouseX = Float.NaN
    private var lastSentAirMouseY = Float.NaN
    private var lastSentAirMouseSelect = false
    private var pendingAirMouseX = 0.5f
    private var pendingAirMouseY = 0.5f
    private var pendingAirMouseSelect = false
    private var hasPendingAirMouse = false

    private val AIR_MOUSE_MIN_DELTA = 0.00075f

    // VSYNC-aligned input dispatcher with Bluetooth throttling.
    private val frameCallback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (mode == TapLinkBluetoothControllerServer.ControllerMode.TRACKPAD &&
                                    hasPendingTrackpadDelta
                    ) {
                        flushPendingTrackpadDelta()
                    }

                    if (mode == TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE &&
                                    hasPendingAirMouse
                    ) {
                        hasPendingAirMouse = false

                        val shouldSend =
                                lastSentAirMouseX.isNaN() ||
                                        abs(pendingAirMouseX - lastSentAirMouseX) >=
                                                AIR_MOUSE_MIN_DELTA ||
                                        abs(pendingAirMouseY - lastSentAirMouseY) >=
                                                AIR_MOUSE_MIN_DELTA ||
                                        pendingAirMouseSelect != lastSentAirMouseSelect

                        if (shouldSend) {
                            lastSentAirMouseX = pendingAirMouseX
                            lastSentAirMouseY = pendingAirMouseY
                            lastSentAirMouseSelect = pendingAirMouseSelect
                            controllerServer.sendAirMouseRay(
                                    pendingAirMouseX,
                                    pendingAirMouseY,
                                    pendingAirMouseSelect
                            )
                        }
                    }

                    Choreographer.getInstance().postFrameCallback(this)
                }
            }

    private fun updateSensorAndTimerState() {
        val isAirMouse = mode == TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE

        sensorManager.unregisterListener(this@MainActivity)
        Choreographer.getInstance().removeFrameCallback(frameCallback)

        if (isAirMouse) {
            rotationSensor?.let {
                sensorManager.registerListener(
                        this@MainActivity,
                        it,
                        SensorManager.SENSOR_DELAY_FASTEST
                )
            }
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onResume() {
        super.onResume()
        updateSensorAndTimerState()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this@MainActivity)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if ((event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
                        event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) ||
                        mode != TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE
        ) {
            return
        }

        val orientation = FloatArray(3)
        val matrix = FloatArray(9)

        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)

        val yaw = orientation[0]
        val pitch = orientation[1]

        if (!hasBaseline) {
            baselineYaw = yaw
            baselinePitch = pitch
            hasBaseline = true

            lastSentAirMouseX = Float.NaN
            lastSentAirMouseY = Float.NaN
            lastSentAirMouseSelect = false
        }

        val yawRange = AIR_MOUSE_YAW_RANGE / airMouseSensitivity
        val pitchRange = AIR_MOUSE_PITCH_RANGE / airMouseSensitivity

        val rawX = (0.5f + angleDelta(yaw, baselineYaw) / yawRange).coerceIn(0f, 1f)
        val rawY = (0.5f + (pitch - baselinePitch) / pitchRange).coerceIn(0f, 1f)
        val select = false

        pendingAirMouseX = rawX
        pendingAirMouseY = rawY
        pendingAirMouseSelect = select
        hasPendingAirMouse = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun createModernButton(label: String, colorStr: String = "#3b82f6"): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            isAllCaps = false
            val shape =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor(colorStr))
                        cornerRadius = dp(12).toFloat()
                    }
            background =
                    android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(
                                    Color.argb(76, 255, 255, 255)
                            ),
                            shape,
                            null
                    )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(4).toFloat()
            stateListAnimator = null
        }
    }

    private fun createCircleButton(label: String, colorStr: String): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            isAllCaps = false
            val shape =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(colorStr))
                    }
            background =
                    android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(
                                    Color.argb(76, 255, 255, 255)
                            ),
                            shape,
                            null
                    )
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
        }
    }

    private fun createModernEditText(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94a3b8"))
            val shape =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#1e293b"))
                        setStroke(dp(1), Color.parseColor("#334155"))
                        cornerRadius = dp(12).toFloat()
                    }
            background = shape
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
    }

    private fun buildContentView(): View {
        val root =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(12), dp(20), dp(20))
                    setBackgroundColor(Color.parseColor("#0f172a"))
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            )
                }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(
                    insets.left + dp(20),
                    insets.top + dp(12),
                    insets.right + dp(20),
                    insets.bottom + dp(20)
            )
            windowInsets
        }

        val headerRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

        val headerText =
                TextView(this).apply {
                    text = "TapLink X3 Controller"
                    setTextColor(Color.parseColor("#f8fafc"))
                    textSize = 24f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
        headerRow.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))

        val settingsButton =
                createCircleButton("⚙", "#475569").apply {
                    setOnClickListener { showSettingsDialog() }
                }
        headerRow.addView(
                settingsButton,
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) }
        )

        startButton = createCircleButton("ᛒ", "#3b82f6")
        startButton.setOnClickListener { startBluetoothController() }
        headerRow.addView(startButton, LinearLayout.LayoutParams(dp(40), dp(40)))

        root.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        statusText =
                TextView(this).apply {
                    text = "Start Bluetooth, then open TapLink X3 on the paired glasses."
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 14f
                }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val apiKeyRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
        apiKeyInput =
                createModernEditText("Groq API key").apply {
                    setSingleLine(true)
                    setText(
                            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(KEY_GROQ_API_KEY, "")
                    )
                }
        apiKeyRow.addView(
                apiKeyInput,
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) }
        )

        apiKeyRow.addView(
                createModernButton("Save", "#3b82f6").apply {
                    setOnClickListener { saveAndSendGroqApiKey() }
                },
                LinearLayout.LayoutParams(dp(80), dp(52))
        )
        root.addView(apiKeyRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        root.addView(
                buildAiPanel(),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        )

        val modeRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
        val radioGroup =
                RadioGroup(this).apply {
                    orientation = RadioGroup.HORIZONTAL
                    addView(
                            RadioButton(this@MainActivity).apply {
                                text = "Trackpad"
                                setTextColor(Color.parseColor("#e2e8f0"))
                                id = View.generateViewId()
                                isChecked = true
                                setPadding(dp(8), dp(8), dp(16), dp(8))
                            }
                    )
                    addView(
                            RadioButton(this@MainActivity).apply {
                                text = "Air mouse"
                                setTextColor(Color.parseColor("#e2e8f0"))
                                id = View.generateViewId()
                                setPadding(dp(8), dp(8), dp(16), dp(8))
                            }
                    )
                    setOnCheckedChangeListener { group, checkedId ->
                        flushPendingTrackpadDelta()
                        val checked = group.findViewById<RadioButton>(checkedId)
                        mode =
                                if (checked.text.toString().contains("Air")) {
                                    hasBaseline = false
                                    TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE
                                } else {
                                    TapLinkBluetoothControllerServer.ControllerMode.TRACKPAD
                                }
                        controllerServer.setMode(mode)
                        updateModeChrome()
                        updateSensorAndTimerState()
                    }
                }
        modeRow.addView(radioGroup, LinearLayout.LayoutParams(0, -2, 1f))
        recenterButton =
                createModernButton("Recenter", "#64748b").apply {
                    visibility = View.GONE
                    setOnClickListener {
                        hasBaseline = false
                        Toast.makeText(
                                        this@MainActivity,
                                        "Air mouse recentered",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
        modeRow.addView(recenterButton, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val trackpadContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        trackpad =
                FrameLayout(this).apply {
                    val shape =
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(Color.parseColor("#1e293b"))
                                setStroke(dp(2), Color.parseColor("#334155"))
                                cornerRadius = dp(24).toFloat()
                            }
                    background = shape
                    isClickable = true
                    setOnTouchListener(::handleTrackpadTouch)
                    addView(
                            TextView(this@MainActivity).apply {
                                text = "Trackpad Area"
                                setTextColor(Color.parseColor("#64748b"))
                                textSize = 20f
                                gravity = Gravity.CENTER
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            },
                            FrameLayout.LayoutParams(-1, -1)
                    )
                }
        trackpadContainer.addView(
                trackpad,
                LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(8) }
        )

        val scrollBar =
                FrameLayout(this).apply {
                    val shape =
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(Color.parseColor("#1e293b"))
                                setStroke(dp(2), Color.parseColor("#334155"))
                                cornerRadius = dp(16).toFloat()
                            }
                    background = shape
                    isClickable = true
                    addView(
                            TextView(this@MainActivity).apply {
                                text = "↕\nScroll"
                                setTextColor(Color.parseColor("#64748b"))
                                textSize = 14f
                                gravity = Gravity.CENTER
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            },
                            FrameLayout.LayoutParams(-1, -1)
                    )

                    var scrollStartY = 0f
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                scrollStartY = event.y
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dy = event.y - scrollStartY
                                scrollStartY = event.y
                                controllerServer.sendScroll(-dy * trackpadSensitivity)
                                true
                            }
                            else -> true
                        }
                    }
                }
        trackpadContainer.addView(scrollBar, LinearLayout.LayoutParams(dp(72), -1))

        root.addView(
                trackpadContainer,
                LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(16) }
        )

        keyboardPanel =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    visibility = View.GONE
                    setPadding(0, 0, 0, dp(12))
                }
        phoneKeyboardInput =
                object : EditText(this) {
                            override fun onCreateInputConnection(
                                    outAttrs: android.view.inputmethod.EditorInfo
                            ): android.view.inputmethod.InputConnection? {
                                val ic = super.onCreateInputConnection(outAttrs) ?: return null
                                return object :
                                        android.view.inputmethod.InputConnectionWrapper(ic, true) {
                                    override fun deleteSurroundingText(
                                            beforeLength: Int,
                                            afterLength: Int
                                    ): Boolean {
                                        if (beforeLength == 1 && afterLength == 0) {
                                            sendKeyEvent(
                                                    android.view.KeyEvent(
                                                            android.view.KeyEvent.ACTION_DOWN,
                                                            android.view.KeyEvent.KEYCODE_DEL
                                                    )
                                            )
                                            sendKeyEvent(
                                                    android.view.KeyEvent(
                                                            android.view.KeyEvent.ACTION_UP,
                                                            android.view.KeyEvent.KEYCODE_DEL
                                                    )
                                            )
                                            return true
                                        }
                                        return super.deleteSurroundingText(
                                                beforeLength,
                                                afterLength
                                        )
                                    }
                                }
                            }
                        }
                        .apply {
                            hint = "Type for glasses"
                            setTextColor(Color.WHITE)
                            setHintTextColor(Color.parseColor("#94a3b8"))
                            val shape =
                                    android.graphics.drawable.GradientDrawable().apply {
                                        setColor(Color.parseColor("#1e293b"))
                                        setStroke(dp(1), Color.parseColor("#334155"))
                                        cornerRadius = dp(12).toFloat()
                                    }
                            background = shape
                            setPadding(dp(16), dp(12), dp(16), dp(12))

                            setSingleLine(false)
                            minLines = 1
                            maxLines = 3

                            setOnKeyListener { _, keyCode, event ->
                                if (keyCode == android.view.KeyEvent.KEYCODE_DEL &&
                                                event.action == android.view.KeyEvent.ACTION_DOWN
                                ) {
                                    controllerServer.sendKey("backspace")
                                    true
                                } else {
                                    false
                                }
                            }

                            addTextChangedListener(
                                    object : TextWatcher {
                                        private var beforeText = ""

                                        override fun beforeTextChanged(
                                                s: CharSequence?,
                                                start: Int,
                                                count: Int,
                                                after: Int
                                        ) {
                                            beforeText = s?.toString().orEmpty()
                                        }

                                        override fun onTextChanged(
                                                s: CharSequence?,
                                                start: Int,
                                                before: Int,
                                                count: Int
                                        ) = Unit

                                        override fun afterTextChanged(s: Editable?) {
                                            if (suppressKeyboardTextChange) return
                                            val currentText = s?.toString().orEmpty()
                                            if (currentText.length > beforeText.length) {
                                                val added = currentText.substring(beforeText.length)
                                                added.forEach { char ->
                                                    controllerServer.sendKey(
                                                            if (char == '\n') "enter"
                                                            else char.toString()
                                                    )
                                                }
                                            }
                                            suppressKeyboardTextChange = true
                                            s?.clear()
                                            suppressKeyboardTextChange = false
                                        }
                                    }
                            )
                        }
        keyboardPanel.addView(
                phoneKeyboardInput,
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
        )

        keyboardPanel.addView(
                createModernButton("Enter", "#8b5cf6").apply {
                    setOnClickListener { controllerServer.sendKey("enter") }
                },
                LinearLayout.LayoutParams(dp(80), dp(52)).apply { rightMargin = dp(8) }
        )

        keyboardPanel.addView(
                createModernButton("Close", "#ef4444").apply {
                    setOnClickListener {
                        controllerServer.sendKey("hideKeyboard")
                        setPhoneKeyboardVisible(false)
                    }
                },
                LinearLayout.LayoutParams(dp(80), dp(52))
        )
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, -2))

        val actionRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
        val toggleMaskButton =
                createModernButton("Toggle Screen", "#eab308").apply {
                    setOnClickListener { controllerServer.sendKey("toggleMask") }
                }
        actionRow.addView(toggleMaskButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(actionRow, LinearLayout.LayoutParams(-1, -2))

        updateModeChrome()
        return root
    }

    private fun buildAiPanel(): View {
        val panel =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val shape =
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(Color.parseColor("#1e293b"))
                                setStroke(dp(1), Color.parseColor("#334155"))
                                cornerRadius = dp(16).toFloat()
                            }
                    background = shape
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }

        val headerRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

        headerRow.addView(
                TextView(this).apply {
                    text = "TapLink X3 AI"
                    setTextColor(Color.parseColor("#f8fafc"))
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
        )

        panel.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        aiResponseText =
                TextView(this).apply {
                    text =
                            "Enter a prompt and tap Ask. The TapLink X3 AI window will automatically open on the glasses and answer there."
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 14f
                    maxLines = 7
                }
        panel.addView(
                aiResponseText,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        )

        val inputRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
        aiInput =
                createModernEditText("Ask TapLink X3 AI").apply {
                    setSingleLine(false)
                    minLines = 1
                    maxLines = 3
                }
        inputRow.addView(
                aiInput,
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
        )

        aiAskButton =
                createModernButton("Ask", "#8b5cf6").apply { setOnClickListener { askPhoneAi() } }
        inputRow.addView(
                aiAskButton,
                LinearLayout.LayoutParams(dp(80), dp(52)).apply { rightMargin = dp(8) }
        )

        val clearBtn =
                createModernButton("Clear", "#64748b").apply {
                    setOnClickListener {
                        aiInput.setText("")
                        aiResponseText.text = "Prompt cleared."
                    }
                }
        inputRow.addView(clearBtn, LinearLayout.LayoutParams(dp(80), dp(52)))
        panel.addView(inputRow, LinearLayout.LayoutParams(-1, -2))

        return panel
    }

    private fun startBluetoothController() {
        if (!hasBluetoothPermission()) {
            ensureBluetoothPermission()
            return
        }
        if (controllerServer.start()) {
            controllerServer.setMode(mode)
        }
    }

    private fun saveAndSendGroqApiKey() {
        val key = apiKeyInput.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Enter a Groq API key first", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GROQ_API_KEY, key)
                .apply()
        controllerServer.sendGroqApiKey(key)
        Toast.makeText(
                        this,
                        if (controllerServer.isConnected()) "Groq API key sent to glasses"
                        else "Groq API key saved; start Bluetooth to send it",
                        Toast.LENGTH_SHORT
                )
                .show()
    }

    private fun askPhoneAi() {
        val key =
                apiKeyInput.text.toString().trim().ifBlank {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(KEY_GROQ_API_KEY, "")
                            .orEmpty()
                            .trim()
                }
        val message = aiInput.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Enter and save a Groq API key first", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isBlank()) {
            Toast.makeText(this, "Ask a question first", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GROQ_API_KEY, key)
                .apply()
        controllerServer.sendGroqApiKey(key)

        // Send AI prompt directly to the glasses
        controllerServer.sendAiPrompt(message)

        aiInput.setText("")
        aiResponseText.text = "Prompt sent to glasses: \"$message\""
    }

    private fun sendSavedGroqApiKeyIfPresent() {
        val key =
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_GROQ_API_KEY, null)
                        ?.trim()
        if (!key.isNullOrBlank()) {
            controllerServer.sendGroqApiKey(key)
        }
    }

    private fun handleTrackpadTouch(view: View, event: MotionEvent): Boolean {
        val isAirMouse = mode == TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (x, y) = trackpadCentroid(event)
                lastPadX = x
                lastPadY = y
                touchStartX = x
                touchStartY = y
                totalTouchDistance = 0f
                maxPointerCountInGesture = event.pointerCount

                resetTrackpadAccumulator(x, y, event.pointerCount)
                if (!isAirMouse) {
                    controllerServer.sendTrackpadGesture(
                            TapLinkBluetoothControllerServer.TrackpadAction.DOWN,
                            event.pointerCount
                    )
                }

                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                flushPendingTrackpadDelta((event.pointerCount - 1).coerceAtLeast(1))
                val (x, y) = trackpadCentroid(event)
                resetTrackpadAccumulator(x, y, event.pointerCount)
                if (!isAirMouse) {
                    controllerServer.sendTrackpadGesture(
                            TapLinkBluetoothControllerServer.TrackpadAction.POINTER,
                            event.pointerCount
                    )
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val (x, y) = trackpadCentroid(event)
                processTrackpadSample(x, y, event.pointerCount, isAirMouse)

                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                flushPendingTrackpadDelta(event.pointerCount)
                val remainingPointerCount = (event.pointerCount - 1).coerceAtLeast(1)
                val (x, y) = trackpadCentroid(event, excludeActionPointer = true)
                resetTrackpadAccumulator(x, y, remainingPointerCount)
                if (!isAirMouse) {
                    controllerServer.sendTrackpadGesture(
                            TapLinkBluetoothControllerServer.TrackpadAction.POINTER,
                            remainingPointerCount
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val (x, y) = trackpadCentroid(event)
                processTrackpadSample(x, y, event.pointerCount, isAirMouse)

                if (!isAirMouse) {
                    flushPendingTrackpadDelta(event.pointerCount)
                    controllerServer.sendTrackpadGesture(
                            TapLinkBluetoothControllerServer.TrackpadAction.UP,
                            event.pointerCount
                    )
                } else {
                    pendingTrackpadDx = 0f
                    pendingTrackpadDy = 0f
                    hasPendingTrackpadDelta = false
                }

                if (maxPointerCountInGesture == 1 &&
                                totalTouchDistance < TAP_DISTANCE_PX &&
                                abs(x - touchStartX) < TAP_DISTANCE_PX &&
                                abs(y - touchStartY) < TAP_DISTANCE_PX
                ) {
                    controllerServer.sendTap()
                }

                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                flushPendingTrackpadDelta(event.pointerCount)
                if (!isAirMouse) {
                    controllerServer.sendTrackpadGesture(
                            TapLinkBluetoothControllerServer.TrackpadAction.CANCEL,
                            event.pointerCount
                    )
                }
                return true
            }
        }

        return false
    }

    private fun resetTrackpadAccumulator(x: Float, y: Float, pointerCount: Int) {
        lastPadX = x
        lastPadY = y
        pendingTrackpadDx = 0f
        pendingTrackpadDy = 0f
        hasPendingTrackpadDelta = false
        maxPointerCountInGesture = maxOf(maxPointerCountInGesture, pointerCount)
    }

    private fun processTrackpadSample(
            x: Float,
            y: Float,
            pointerCount: Int,
            isAirMouse: Boolean
    ) {
        val dx = x - lastPadX
        val dy = y - lastPadY

        totalTouchDistance += sqrt(dx * dx + dy * dy)
        lastPadX = x
        lastPadY = y
        maxPointerCountInGesture = maxOf(maxPointerCountInGesture, pointerCount)

        if (isAirMouse) return

        if (pointerCount >= 2) {
            flushPendingTrackpadDelta(pointerCount)
            if (abs(dy) >= TRACKPAD_MIN_DELTA) {
                controllerServer.sendScroll(-dy * trackpadSensitivity)
            }
            return
        }

        pendingTrackpadDx += dx * trackpadSensitivity
        pendingTrackpadDy += dy * trackpadSensitivity
        hasPendingTrackpadDelta = true
    }

    private fun trackpadCentroid(
            event: MotionEvent,
            historyIndex: Int? = null,
            excludeActionPointer: Boolean = false
    ): Pair<Float, Float> {
        val ignoredIndex = if (excludeActionPointer) event.actionIndex else -1
        var x = 0f
        var y = 0f
        var count = 0

        for (pointerIndex in 0 until event.pointerCount) {
            if (pointerIndex == ignoredIndex) continue
            x +=
                    if (historyIndex == null) {
                        event.getX(pointerIndex)
                    } else {
                        event.getHistoricalX(pointerIndex, historyIndex)
                    }
            y +=
                    if (historyIndex == null) {
                        event.getY(pointerIndex)
                    } else {
                        event.getHistoricalY(pointerIndex, historyIndex)
                    }
            count++
        }

        return if (count > 0) x / count to y / count else event.x to event.y
    }

    private fun flushPendingTrackpadDelta(pointerCount: Int = 1) {
        if (!hasPendingTrackpadDelta) return

        val dx = pendingTrackpadDx
        val dy = pendingTrackpadDy
        pendingTrackpadDx = 0f
        pendingTrackpadDy = 0f
        hasPendingTrackpadDelta = false

        if (abs(dx) >= TRACKPAD_MIN_DELTA || abs(dy) >= TRACKPAD_MIN_DELTA) {
            controllerServer.sendTrackpadDelta(
                    dx,
                    dy,
                    pointerCount,
                    TapLinkBluetoothControllerServer.TrackpadAction.MOVE
            )
        }
    }

    private fun updateModeChrome() {
        val isAirMouse = mode == TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE
        trackpad.alpha = if (isAirMouse) 0.45f else 1f
        recenterButton.visibility = if (isAirMouse) View.VISIBLE else View.GONE
    }

    private fun setPhoneKeyboardVisible(visible: Boolean) {
        keyboardPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            phoneKeyboardInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(phoneKeyboardInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            phoneKeyboardInput.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(phoneKeyboardInput.windowToken, 0)
        }
    }

    private fun showSettingsDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val dialogView =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1e293b"))
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                    val bgShape =
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(Color.parseColor("#1e293b"))
                                cornerRadius = dp(16).toFloat()
                                setStroke(dp(2), Color.parseColor("#334155"))
                            }
                    background = bgShape
                    layoutParams =
                            ViewGroup.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
                }

        val titleView =
                TextView(this).apply {
                    text = "Cursor Sensitivity"
                    setTextColor(Color.parseColor("#f8fafc"))
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
        dialogView.addView(
                titleView,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) }
        )

        // Trackpad Sensitivity Section
        val trackpadLabel =
                TextView(this).apply {
                    text = String.format("Trackpad Sensitivity: %.2fx", trackpadSensitivity)
                    setTextColor(Color.parseColor("#cbd5e1"))
                    textSize = 15f
                }
        dialogView.addView(
                trackpadLabel,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )

        val trackpadSeekBar =
                android.widget.SeekBar(this).apply {
                    max = 45 // 0.5 to 5.0 with step 0.1 (0 to 45 progress)
                    progress = ((trackpadSensitivity - 0.5f) * 10f).toInt().coerceIn(0, 45)
                    setOnSeekBarChangeListener(
                            object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                        seekBar: android.widget.SeekBar?,
                                        progress: Int,
                                        fromUser: Boolean
                                ) {
                                    val newVal = 0.5f + (progress / 10f)
                                    trackpadSensitivity = newVal
                                    trackpadLabel.text =
                                            String.format("Trackpad Sensitivity: %.2fx", newVal)
                                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                            .edit()
                                            .putFloat(KEY_TRACKPAD_SENSITIVITY, newVal)
                                            .apply()
                                }
                                override fun onStartTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                                override fun onStopTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                            }
                    )
                }
        dialogView.addView(
                trackpadSeekBar,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) }
        )

        // Air Mouse Sensitivity Section
        val airMouseLabel =
                TextView(this).apply {
                    text = String.format("Air Mouse Sensitivity: %.2fx", airMouseSensitivity)
                    setTextColor(Color.parseColor("#cbd5e1"))
                    textSize = 15f
                }
        dialogView.addView(
                airMouseLabel,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )

        val airMouseSeekBar =
                android.widget.SeekBar(this).apply {
                    max = 28 // 0.2 to 3.0 with step 0.1 (0 to 28 progress)
                    progress = ((airMouseSensitivity - 0.2f) * 10f).toInt().coerceIn(0, 28)
                    setOnSeekBarChangeListener(
                            object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                        seekBar: android.widget.SeekBar?,
                                        progress: Int,
                                        fromUser: Boolean
                                ) {
                                    val newVal = 0.2f + (progress / 10f)
                                    airMouseSensitivity = newVal
                                    airMouseLabel.text =
                                            String.format("Air Mouse Sensitivity: %.2fx", newVal)
                                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                            .edit()
                                            .putFloat(KEY_AIR_MOUSE_SENSITIVITY, newVal)
                                            .apply()
                                }
                                override fun onStartTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                                override fun onStopTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                            }
                    )
                }
        dialogView.addView(
                airMouseSeekBar,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) }
        )

        // Close button
        val closeBtn =
                createModernButton("Close", "#3b82f6").apply {
                    setOnClickListener { dialog.dismiss() }
                }
        dialogView.addView(closeBtn, LinearLayout.LayoutParams(-1, dp(44)))

        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
        dialog.show()
    }

    private fun ensureBluetoothPermission() {
        if (hasBluetoothPermission()) return
        val permission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        Manifest.permission.BLUETOOTH_CONNECT
                else Manifest.permission.BLUETOOTH
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_BLUETOOTH_PERMISSION)
    }

    private fun hasBluetoothPermission(): Boolean {
        val permission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        Manifest.permission.BLUETOOTH_CONNECT
                else Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(this, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun angleDelta(value: Float, baseline: Float): Float {
        var delta = value - baseline
        while (delta > PI) delta -= (2 * PI).toFloat()
        while (delta < -PI) delta += (2 * PI).toFloat()
        return delta
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSION = 2001
        private const val PREFS_NAME = "TapLinkControllerPrefs"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"
        private const val KEY_AIR_MOUSE_SENSITIVITY = "air_mouse_sensitivity"
        private const val DEFAULT_TRACKPAD_SENSITIVITY = 2.25f
        private const val PREVIOUS_DEFAULT_TRACKPAD_SENSITIVITY = 1.5f
        private const val LEGACY_TRACKPAD_SENSITIVITY = 2.5f
        private const val TAP_DISTANCE_PX = 24f
        private const val TRACKPAD_MIN_DELTA = 0.05f
        private const val AIR_MOUSE_YAW_RANGE = PI.toFloat() / 3f
        private const val AIR_MOUSE_PITCH_RANGE = PI.toFloat() / 4f
    }
}
