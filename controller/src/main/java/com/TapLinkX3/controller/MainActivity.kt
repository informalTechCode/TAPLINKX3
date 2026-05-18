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
    private lateinit var triggerButton: Button
    private lateinit var recenterButton: Button
    private lateinit var trackpad: View
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var phoneKeyboardInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var aiInput: EditText
    private lateinit var aiResponseText: TextView
    private lateinit var aiAskButton: Button
    private var suppressKeyboardTextChange = false

    private var mode = TapLinkBluetoothControllerServer.ControllerMode.TRACKPAD
    private var hasBaseline = false
    private var baselineYaw = 0f
    private var baselinePitch = 0f
    private var lastPadX = 0f
    private var lastPadY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var totalTouchDistance = 0f
    private var triggerHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        setContentView(buildContentView())
        controllerServer.onConnectionChanged = { connected ->
            runOnUiThread {
                trackpad.alpha = if (connected) 1f else 0.5f
                startButton.text = if (connected) "Connected" else "Start Bluetooth"
                if (connected) {
                    sendSavedGroqApiKeyIfPresent()
                }
            }
        }
        controllerServer.onStatusChanged = { status ->
            runOnUiThread { statusText.text = status }
        }
        controllerServer.onKeyboardVisibilityRequested = { visible ->
            runOnUiThread { setPhoneKeyboardVisible(visible) }
        }
        ensureBluetoothPermission()
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerServer.stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR ||
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
        }

        val normalizedX = 0.5f + angleDelta(yaw, baselineYaw) / AIR_MOUSE_YAW_RANGE
        val normalizedY = 0.5f - (pitch - baselinePitch) / AIR_MOUSE_PITCH_RANGE
        controllerServer.sendAirMouseRay(normalizedX, normalizedY, triggerHeld)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(13, 17, 23))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            text = "Start Bluetooth, then open TapLink on the paired glasses."
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        startButton = Button(this).apply {
            text = "Start Bluetooth"
            setOnClickListener { startBluetoothController() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(-1, dp(52)))

        val apiKeyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        apiKeyInput = EditText(this).apply {
            hint = "Groq API key"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_GROQ_API_KEY, ""))
        }
        apiKeyRow.addView(apiKeyInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        apiKeyRow.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveAndSendGroqApiKey() }
        }, LinearLayout.LayoutParams(dp(100), dp(52)))
        root.addView(apiKeyRow, LinearLayout.LayoutParams(-1, -2))

        root.addView(buildAiPanel(), LinearLayout.LayoutParams(-1, -2))

        root.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply {
                text = "Trackpad"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
                isChecked = true
            })
            addView(RadioButton(this@MainActivity).apply {
                text = "Air mouse"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            })
            setOnCheckedChangeListener { group, checkedId ->
                val checked = group.findViewById<RadioButton>(checkedId)
                mode = if (checked.text.toString().contains("Air")) {
                    hasBaseline = false
                    TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE
                } else {
                    TapLinkBluetoothControllerServer.ControllerMode.TRACKPAD
                }
                controllerServer.setMode(mode)
                updateModeChrome()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        trackpad = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(22, 27, 34))
            isClickable = true
            setOnTouchListener(::handleTrackpadTouch)
            addView(TextView(this@MainActivity).apply {
                text = "Trackpad"
                setTextColor(Color.rgb(139, 148, 158))
                textSize = 22f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(trackpad, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(16)
            bottomMargin = dp(16)
        })

        keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        phoneKeyboardInput = EditText(this).apply {
            hint = "Type for glasses"
            setSingleLine(false)
            minLines = 1
            maxLines = 3
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                private var beforeText = ""

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    beforeText = s?.toString().orEmpty()
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (suppressKeyboardTextChange) return
                    val currentText = s?.toString().orEmpty()
                    when {
                        currentText.length > beforeText.length -> {
                            val added = currentText.substring(beforeText.length)
                            added.forEach { char ->
                                controllerServer.sendKey(if (char == '\n') "enter" else char.toString())
                            }
                        }
                        currentText.length < beforeText.length -> {
                            repeat(beforeText.length - currentText.length) {
                                controllerServer.sendKey("backspace")
                            }
                        }
                    }
                    suppressKeyboardTextChange = true
                    s?.clear()
                    suppressKeyboardTextChange = false
                }
            })
        }
        keyboardPanel.addView(phoneKeyboardInput, LinearLayout.LayoutParams(0, dp(64), 1f))
        keyboardPanel.addView(Button(this).apply {
            text = "Enter"
            setOnClickListener { controllerServer.sendKey("enter") }
        }, LinearLayout.LayoutParams(dp(92), dp(52)))
        keyboardPanel.addView(Button(this).apply {
            text = "Close"
            setOnClickListener {
                controllerServer.sendKey("hideKeyboard")
                setPhoneKeyboardVisible(false)
            }
        }, LinearLayout.LayoutParams(dp(92), dp(52)))
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, -2))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        recenterButton = Button(this).apply {
            text = "Recenter"
            setOnClickListener {
                hasBaseline = false
                Toast.makeText(this@MainActivity, "Air mouse recentered", Toast.LENGTH_SHORT).show()
            }
        }
        triggerButton = Button(this).apply {
            text = "Select"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        triggerHeld = true
                        controllerServer.sendTap()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        triggerHeld = false
                        true
                    }
                    else -> false
                }
            }
        }
        actionRow.addView(recenterButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        actionRow.addView(triggerButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actionRow, LinearLayout.LayoutParams(-1, -2))

        updateModeChrome()
        return root
    }

    private fun buildAiPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        panel.addView(TextView(this).apply {
            text = "TapLink AI"
            setTextColor(Color.WHITE)
            textSize = 16f
        }, LinearLayout.LayoutParams(-1, -2))

        aiResponseText = TextView(this).apply {
            text = "Ask from the phone using the saved Groq API key."
            setTextColor(Color.rgb(201, 209, 217))
            textSize = 14f
            maxLines = 7
        }
        panel.addView(aiResponseText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        aiInput = EditText(this).apply {
            hint = "Ask TapLink AI"
            setSingleLine(false)
            minLines = 1
            maxLines = 3
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        inputRow.addView(aiInput, LinearLayout.LayoutParams(0, dp(64), 1f))
        aiAskButton = Button(this).apply {
            text = "Ask"
            setOnClickListener { askPhoneAi() }
        }
        inputRow.addView(aiAskButton, LinearLayout.LayoutParams(dp(84), dp(52)))
        inputRow.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                phoneGroqClient.clearHistory()
                aiResponseText.text = "Chat cleared."
            }
        }, LinearLayout.LayoutParams(dp(84), dp(52)))
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
        ).show()
    }

    private fun askPhoneAi() {
        val key = apiKeyInput.text.toString().trim().ifBlank {
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
        aiInput.setText("")
        aiAskButton.isEnabled = false
        aiResponseText.text = "Thinking..."

        phoneGroqClient.ask(key, message) { result ->
            aiAskButton.isEnabled = true
            aiResponseText.text =
                    result.fold(
                            onSuccess = { it },
                            onFailure = { "AI error: ${it.message ?: "request failed"}" }
                    )
        }
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
        if (mode != TapLinkBluetoothControllerServer.ControllerMode.TRACKPAD) return false

        val normalizedX = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val normalizedY = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPadX = event.x
                lastPadY = event.y
                touchStartX = event.x
                touchStartY = event.y
                totalTouchDistance = 0f
                controllerServer.sendTouch(TapLinkBluetoothControllerServer.TouchAction.DOWN, normalizedX, normalizedY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastPadX
                val dy = event.y - lastPadY
                totalTouchDistance += sqrt(dx * dx + dy * dy)
                lastPadX = event.x
                lastPadY = event.y
                controllerServer.sendTrackpadDelta(dx * TRACKPAD_SENSITIVITY, dy * TRACKPAD_SENSITIVITY)
                controllerServer.sendTouch(TapLinkBluetoothControllerServer.TouchAction.MOVE, normalizedX, normalizedY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                controllerServer.sendTouch(TapLinkBluetoothControllerServer.TouchAction.UP, normalizedX, normalizedY)
                if (totalTouchDistance < TAP_DISTANCE_PX &&
                    abs(event.x - touchStartX) < TAP_DISTANCE_PX &&
                    abs(event.y - touchStartY) < TAP_DISTANCE_PX
                ) {
                    controllerServer.sendTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                controllerServer.sendTouch(TapLinkBluetoothControllerServer.TouchAction.CANCEL, normalizedX, normalizedY)
                return true
            }
        }
        return false
    }

    private fun updateModeChrome() {
        trackpad.alpha = if (mode == TapLinkBluetoothControllerServer.ControllerMode.TRACKPAD) 1f else 0.45f
        recenterButton.isEnabled = mode == TapLinkBluetoothControllerServer.ControllerMode.AIR_MOUSE
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

    private fun ensureBluetoothPermission() {
        if (hasBluetoothPermission()) return
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT
            else Manifest.permission.BLUETOOTH
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_BLUETOOTH_PERMISSION)
    }

    private fun hasBluetoothPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT
            else Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
        private const val TRACKPAD_SENSITIVITY = 1.25f
        private const val TAP_DISTANCE_PX = 24f
        private const val AIR_MOUSE_YAW_RANGE = PI.toFloat() / 3f
        private const val AIR_MOUSE_PITCH_RANGE = PI.toFloat() / 4f
    }
}
