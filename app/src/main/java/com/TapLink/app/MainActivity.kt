package com.TapLink.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import com.ffalconxr.mercury.ipc.Launcher
import com.ffalconxr.mercury.ipc.helpers.RingIPCHelper
import com.ffalcon.mercury.android.sdk.util.DeviceUtil
import com.ffalcon.mercury.android.sdk.touch.FilterMode // ok if unused when not on X3
import androidx.core.content.edit


interface NavigationListener {
    fun onNavigationBackPressed()
    fun onQuitPressed()
    fun onRingPressed()
    fun onSettingsPressed()
    fun onRefreshPressed()
    fun onHomePressed()
    fun onHyperlinkPressed()
}

interface LinkEditingListener {
    fun onShowLinkEditing()
    fun onHideLinkEditing()
    fun onSendCharacterToLink(character: String)
    fun onSendBackspaceInLink()
    fun onSendEnterInLink()
    fun onSendClearInLink()
    fun isLinkEditing(): Boolean
}

private enum class MenuAxisLock { H, V }
private var menuAxisLock: MenuAxisLock? = null
private val MENU_AXIS_THRESHOLD = 6f  // tweak sensitivity

class MainActivity : AppCompatActivity(),
    DualWebViewGroup.DualWebViewGroupListener,
    NavigationListener,
    CustomKeyboardView.OnKeyboardActionListener,
    DualWebViewGroup.ScrollDirectionListener,
    BookmarkListener,
    BookmarkKeyboardListener,
    LinkEditingListener,
    DualWebViewGroup.MaskToggleListener,
    DualWebViewGroup.AnchorToggleListener
{

    private val H2V_GAIN = 1.0f   // how strongly horizontal motion affects vertical scroll
    private val X_INVERT = 1.0f   // 1 = left -> up (what you want). Use -1 to flip.
    private val Y_INVERT = 1.0f   // 1 = drag up -> up. Use -1 to flip if needed.
    lateinit var dualWebViewGroup: DualWebViewGroup
    private lateinit var webView: WebView
    private lateinit var mainContainer: FrameLayout
    private lateinit var gestureDetector: GestureDetector
    private var isSimulatingTouchEvent = false
    private var isCursorVisible = true

    private fun refreshCursor() {
        dualWebViewGroup.updateCursorPosition(lastCursorX, lastCursorY, isCursorVisible)
    }

    private fun refreshCursor(visible: Boolean) {
        isCursorVisible = visible
        refreshCursor()
    }

    private fun centerCursor(visible: Boolean = isCursorVisible) {
        lastCursorX = 320f
        lastCursorY = 240f
        isCursorVisible = visible
        refreshCursor()
    }
    private var isToggling = false
    private var lastCursorX = 320f
    private var lastCursorY = 240f

    private val PERMISSION_REQUEST_CODE = 123  // Any number can be used here
    private val AUDIO_PERMISSION_CODE = 123
    private val CAMERA_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_CODE = 100
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 999  // Any unique code
    private var cameraImageUri: Uri? = null
    private var isCapturing = false  // Add this flag to prevent multiple captures

    private var lastClickTime = 0L
    private val MIN_CLICK_INTERVAL = 500L  // Minimum time between clicks


    // In MainActivity, add these properties to track cursor state and position
    private var lastKnownCursorX = 320f  // Default center position
    private var lastKnownCursorY = 240f  // Default center position
    private var lastKnownWebViewX = 0f
    private var lastKnownWebViewY = 0f
    private var cursorJustAppeared = false // Track if cursor just appeared


    private var currentVelocityX = 0f
    private var currentVelocityY = 0f
    private val movementDecay = 0.9f       // Decay factor to slow down gradually
    private val updateInterval = 16L       // Update interval in ms for smooth motion
    private val handler = Handler(Looper.getMainLooper())


    private val longPressTimeout = 200L // Milliseconds threshold for tap vs long press

    private lateinit var cursorLeftView: ImageView
    private lateinit var cursorRightView: ImageView

    private var keyboardView: CustomKeyboardView? = null
    private var isKeyboardVisible = false

    private var originalWebViewHeight = 0


    private var isHorizontalScroll = false

    private var isProcessingTap = false

    private val prefsName = "BrowserPrefs"
    private val keyLastUrl = "last_url"
    private var lastUrl: String? = null
    private var isUrlEditing = false

    private var keyboardListener: DualWebViewGroup.KeyboardListener? = null

    private val PERMISSIONS_REQUEST_CODE = 123
    private var pendingPermissionRequest: PermissionRequest? = null
    private var audioManager: AudioManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var capturedImageData: ByteArray? = null

    private var fullScreenCustomView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var originalOrientation: Int = 0
    private var wasKeyboardDismissedByEnter = false

    private var preMaskCursorState = false
    private var preMaskCursorX = 0f
    private var preMaskCursorY = 0f

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingCursorUpdate = false

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            Log.d("MainActivity", "Back key pressed")
            when {
                fullScreenCustomView != null -> {
                    hideFullScreenCustomView()
                }
                isKeyboardVisible || dualWebViewGroup.isUrlEditing() -> {
                    // Hide keyboard and exit URL editing
                    hideCustomKeyboard()
                }
                isCursorVisible -> {
                    // Hide cursor
                    toggleCursorVisibility(forceHide = true)
                }
                else -> {
                    // Remove the callback and let the system handle back
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    // Re-enable for next time
                    isEnabled = true
                }
            }
        }
    }




    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var isAnchored = true


    private var firstSensorReading = true
    private val TRANSLATION_SCALE = 4000f // Large scale factor since we want content to move off-screen


    private var sensorEventListener = createSensorEventListener()

    private lateinit var tripleClickMenu: TripleClickMenu
    private var lastTapTime = 0L
    private var firstTapTime = 0L
    private var tapCount = 0
    private var pendingDoubleTapAction = false
    private val TRIPLE_TAP_TIMEOUT = 500L  // Total window for triple-tap sequence

    private var isTripleTapInProgress = false
    private var shouldResetInitialQuaternion = false

    private val SCROLL_MODE_TIMEOUT = 30000L // 30 seconds in milliseconds
    private var scrollModeHandler = Handler(Looper.getMainLooper())
    private var scrollModeRunnable = Runnable {
        if (isCursorVisible && !isKeyboardVisible) {
            // Switch to scroll mode
            toggleCursorVisibility(forceHide = true)
            dualWebViewGroup.setScrollMode(true)
        }
    }

    private lateinit var mLauncher: Launcher
    private var isRingConnected = false
    private var imuStatus = -1

    private var initialQuaternion: Quaternion? = null
    private var lastQuaternion: Quaternion? = null

    private data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double)

    private val doubleTapLock = Object()
    private var isProcessingDoubleTap = false
    private val DOUBLE_TAP_CONFIRMATION_DELAY = 200L

    private var isRingSwitchEnabled = true
    private var settingsMenu: View? = null

    private val ringResponseListener = Launcher.OnResponseListener { response ->
        if (response?.data == null) return@OnResponseListener

        if (isCursorVisible) {

            try {
                val jo = JSONObject(response.data)
                val datatype = jo.optString("datatype", "no datatype")

                when (datatype) {
                    "ring_info" -> {
                        isRingConnected = jo.getBoolean("ring_connected")
                        imuStatus = jo.getInt("ring_imu_status")

                        // Update UI elements for ring status
                        runOnUiThread {
                            // Update ring button through DualWebViewGroup
                            dualWebViewGroup.leftNavigationBar.findViewById<ImageButton>(R.id.btnRingSwitch)?.let { button ->
                                button.isEnabled = isRingConnected
                                button.alpha = if (isRingConnected) 1.0f else 0.5f
                                // Set initial state when ring connects
                                if (isRingConnected) {
                                    isRingSwitchEnabled = true
                                    button.setImageResource(R.drawable.ic_ring_enabled)
                                } else {
                                    isRingSwitchEnabled = false
                                    button.setImageResource(R.drawable.ic_ring_disabled)
                                }
                            }
                        }

                        // Add this line to update the SystemInfoView
                        dualWebViewGroup.updateRingStatus(isRingConnected)

                        if (isRingConnected && imuStatus != 1) {
                            RingIPCHelper.setRingIMU(this, true)
                        }
                    }

                    "ring_quaternion" -> {
                        // Skip logging quaternion data - it's too verbose
                        // Only process it
                        val w = jo.getDouble("w")
                        val x = jo.getDouble("x")
                        val y = jo.getDouble("y")
                        val z = jo.getDouble("z")
                        handleRingOrientation(w, x, y, z)
                    }

                    else -> {
                        // Log any non-quaternion events in detail
                        Log.d("RingData", """
                        New Ring Event:
                        Type: $datatype
                        Raw data: ${response.data}
                        Available keys: ${jo.keys().asSequence().toList()}
                    """.trimIndent())
                    }
                }
            } catch (e: Exception) {
                Log.e("RingData", "Error processing ring data: ${e.message}")
            }
        }
    }

    private val cursorToggleLock = Object()
    private val MINIMUM_FLING_VELOCITY = 50f
    private val MINIMUM_FLING_DISTANCE = 10f    // Minimum distance the finger must move
    private var potentialTapEvent: MotionEvent? = null

    private var pendingTouchHandler: Handler? = null
    private var pendingTouchRunnable: Runnable? = null


    init {
        Log.d("LinkEditing", "MainActivity initialized, isUrlEditing=$isUrlEditing")
    }



    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set window background to black immediately
        window.setBackgroundDrawableResource(android.R.color.black)

        // Force hardware acceleration but with black background
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Prevent any drawing until we're ready
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        // Set content view with black background
        setContentView(R.layout.activity_main)



        findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)

        mainContainer = findViewById(R.id.mainContainer)

        initializeRing()

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Add this to disable default keyboard
        window.setFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        // After basic window setup but before using any settings
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (!Settings.System.canWrite(this)) {
//                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
//                    data = Uri.parse("package:$packageName")
//                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                    startActivity(this)
//                }
//            }
//        }

        supportActionBar?.hide()

        keyboardListener = object : DualWebViewGroup.KeyboardListener {
            override fun onShowKeyboard() {
                showCustomKeyboard()
            }

            override fun onHideKeyboard() {
                hideCustomKeyboard()
            }
        }

        // Initialize DualWebViewGroup first
        dualWebViewGroup = findViewById(R.id.dualWebViewGroup)
        dualWebViewGroup.listener = this
        dualWebViewGroup.navigationListener = this
        dualWebViewGroup.maskToggleListener = this

        tripleClickMenu = TripleClickMenu(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            // Add explicit background to make it visible
            setBackgroundColor(Color.parseColor("#202020"))
            setPadding(32, 32, 32, 32)  // Add padding for better visibility
            elevation = 1000f

            listener = object : TripleClickMenu.TripleClickMenuListener {
                override fun onAnchorTogglePressed() {
                    toggleAnchor()
                    centerCursor()
                    tripleClickMenu.updateAnchorButtonState(!isAnchored)
                }
                override fun onQuitPressed() {
                    finish()
                }
                override fun onBackPressed() {
                    tripleClickMenu.hide()
                }

                override fun onMaskTogglePressed() {
                    dualWebViewGroup.maskToggleListener?.onMaskTogglePressed()
                }
            }
        }

        // Instead of adding directly to mainContainer, pass to DualWebViewGroup
        dualWebViewGroup.setTripleClickMenu(tripleClickMenu)




        // Initialize GestureDetector
        gestureDetector = GestureDetector(this, object : SimpleOnGestureListener() {
            private var isProcessingTap = false



            override fun onDown(e: MotionEvent): Boolean {
                Log.d("GestureInput", """
            Gesture Down:
            Source: ${e.source}
            Device: ${e.device?.name}
            ButtonState: ${e.buttonState}
            Pressure: ${e.pressure}
            Size: ${e.size}
            EventTime: ${e.eventTime}
            DownTime: ${e.downTime}
            Duration: ${e.eventTime - e.downTime}ms
        """.trimIndent())

                // Check if this is a ring event and if ring is disabled
                if (e.device?.name?.contains("Virtual", ignoreCase = true) == true && !isRingSwitchEnabled) {
                    return true  // Consume the event without processing it
                }


                // Store the down event for potential tap
                potentialTapEvent = MotionEvent.obtain(e)

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime > TRIPLE_TAP_TIMEOUT) {
                    Log.d("TripleClickMenuDebug", "Starting new tap sequence")
                    tapCount = 1
                    firstTapTime = currentTime
                    isTripleTapInProgress = false
                } else {
                    tapCount++
                    Log.d("TripleClickMenuDebug", "Tap count increased to: $tapCount")
                }
                lastTapTime = currentTime

                if (tapCount == 3 && (currentTime - firstTapTime) <= TRIPLE_TAP_TIMEOUT) {
                    Log.d("TripleClickMenuDebug", "Triple tap detected! Time from first tap: ${currentTime - firstTapTime}ms")
                    handler.removeCallbacksAndMessages(null)
                    pendingDoubleTapAction = false
                    isTripleTapInProgress = true

                    // Reset translations to center the view
                    shouldResetInitialQuaternion = true
                    dualWebViewGroup.updateLeftEyePosition(0f, 0f, 0f)  // Reset translations and rotation

                    if (!tripleClickMenu.isMenuVisible()) {
                        tripleClickMenu.show()  // Use the original show() method which handles button states
                        tripleClickMenu.updateAnchorButtonState(isAnchored)
                    } else {
                        tripleClickMenu.handleTap()
                    }
                    tapCount = 0
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                Log.d("RingInput", """
            Long Press:
            Source: ${e.source}
            Device: ${e.device?.name}
            ButtonState: ${e.buttonState}
            Pressure: ${e.pressure}
            Duration: ${e.eventTime - e.downTime}ms
        """.trimIndent())
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Keep menu behavior first
                if (tripleClickMenu?.isMenuVisible() == true) {
                    val scaledDistance = -distanceX * 0.5f
                    tripleClickMenu?.handleScroll(scaledDistance)
                    return true
                }

                // When ANCHORED: both X and Y move the page vertically
                if (isAnchored && !isKeyboardVisible && !dualWebViewGroup.isScreenMasked()) {
                    // Map horizontal to vertical: LEFT -> UP, RIGHT -> DOWN
                    // GestureDetector gives incremental deltas since last callback
                    val horizontalAsVertical = (-distanceX) * X_INVERT * H2V_GAIN
                    val verticalFromDrag = distanceY * Y_INVERT

                    val verticalDelta = horizontalAsVertical + verticalFromDrag

                    if (kotlin.math.abs(verticalDelta) >= 1f) {
                        webView.scrollBy(0, verticalDelta.toInt())
                    }
                    return true
                }

                // Not anchored: keep your existing cursor-follow logic
                val cursorGain = 0.45f
                val dx = -distanceX * cursorGain
                val dy = -distanceY * cursorGain
                if (!isAnchored && com.ffalcon.mercury.android.sdk.util.DeviceUtil.isX3Device()) {
                    val maxW = dualWebViewGroup.width.coerceAtLeast(1)
                    val maxH = dualWebViewGroup.height.coerceAtLeast(1)
                    lastCursorX = (lastCursorX + dx).coerceIn(0f, (maxW - 1).toFloat())
                    lastCursorY = (lastCursorY + dy).coerceIn(0f, (maxH - 1).toFloat())

                    val loc = IntArray(2)
                    webView.getLocationOnScreen(loc)
                    lastKnownWebViewX = lastCursorX - loc[0]
                    lastKnownWebViewY = lastCursorY - loc[1]
                    refreshCursor(true)
                    Log.d("GestureInput", "Trapped!")
                    return true
                }

                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {

                Log.d("RingInput", "Single Tap from device: ${e.device?.name}")
                // Check if this is a ring event and if ring is disabled
                if (e.device?.name?.contains("Virtual", ignoreCase = true) == true && !isRingSwitchEnabled) {
                    return true  // Consume the event without processing it
                }


                handleUserInteraction()

                // Don't handle single taps that are part of a triple tap sequence
                if (isTripleTapInProgress) {
                    isTripleTapInProgress = false
                    return true
                }

//                if(!tripleClickMenu.isMenuVisible()) {
//                    dualWebViewGroup.setScrollMode(false)
//                }


                Log.d("TouchDebug", "checkpoint2")

                if (tripleClickMenu.isMenuVisible()) {
                    tripleClickMenu.handleTap()
                    return true
                }

                Log.d("TouchDebug", "checkpoint3")

                Log.d("TripleClickMenuDebug", "Single tap confirmed, tapCount: $tapCount")

                if (tapCount < 2) {
                    Log.d("TouchDebug", "checkpoint4")

                    if (dualWebViewGroup.isScreenMasked()) {
                        // Restore pre-mask cursor state
                        if (preMaskCursorState) {
                            isCursorVisible = true
                            lastCursorX = preMaskCursorX
                            lastCursorY = preMaskCursorY
                            cursorLeftView.visibility = View.VISIBLE
                            cursorRightView.visibility = View.VISIBLE
                            refreshCursor(true)
                        }
                        dualWebViewGroup.unmaskScreen()
                        return true
                    }

                    Log.d("TouchDebug", "checkpoint5")

                    if (isProcessingTap) return true

                    isProcessingTap = true
                    Handler(Looper.getMainLooper()).postDelayed({ isProcessingTap = false }, 300)

                    // Add this condition to handle exiting scroll mode
                    if (dualWebViewGroup.isInScrollMode()) {
                        dualWebViewGroup.setScrollMode(false)
                        toggleCursorVisibility(forceShow = true)
                        return true
                    }
//                Log.d("TouchDebug", """
//        SingleTapConfirmed:
//        Event: $e
//        Coordinates: (${e.x}, ${e.y})
//        isProcessingTap: $isProcessingTap
//        isCursorVisible: $isCursorVisible
//    """.trimIndent())
                    Log.d("TouchDebug", "checkpoint6")
                    // Check if bookmarks are visible first
                    if (dualWebViewGroup.isBookmarksExpanded()) {
                        Log.d("BookmarksDebug", "Processing tap while bookmarks are visible")
                        val handled = dualWebViewGroup.handleBookmarkTap()
                        if (handled) return true
                    }

                    Log.d("TouchDebug", "checkpoint7")

                    when {
                        isToggling && cursorJustAppeared -> {
                            Log.d("TouchDebug", "Ignoring tap during cursor appearance")
                            return true
                        }



                        isCursorVisible -> {
                            // Check if this is a long press
                            if (e.eventTime - e.downTime > longPressTimeout) {
                                //Log.d("TouchDebug", "Long press detected, ignoring input interaction")
                                return true
                            }



                            // Handle regular clicks when cursor is visible
                            if (!cursorJustAppeared && !isSimulatingTouchEvent) {
                                Log.d("TouchDebug", "checkpoint8")
                                val UILocation = IntArray(2)
                                dualWebViewGroup.leftEyeUIContainer.getLocationOnScreen(UILocation)

                                val adjustedX: Float
                                val adjustedY: Float

                                if (isAnchored) {


                                    // Get rotation from the clip parent
                                    val rotationRad =
                                        Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
                                    val cos = Math.cos(rotationRad).toFloat()
                                    val sin = Math.sin(rotationRad).toFloat()

                                    // The cursor is fixed at (320, 240)
                                    val cursorX = lastCursorX
                                    val cursorY = lastCursorY

                                    // Step 1: Inverse translation (subtract the translation)
                                    val translatedX = cursorX - UILocation[0]
                                    val translatedY = cursorY - UILocation[1]

                                    // Step 2: Inverse rotation (rotate the translated point back to the view's local coordinates)
                                    adjustedX = translatedX * cos + translatedY * sin
                                    adjustedY = -translatedX * sin + translatedY * cos
                                } else {
                                    adjustedX = lastCursorX - UILocation[0]
                                    adjustedY = lastCursorY - UILocation[1]
                                }

                                // Handle navigation bar clicks first
                                if (adjustedY >= 480 - 48) {
                                    isSimulatingTouchEvent = false
                                    Log.d(
                                        "AnchoredTouchDebug",
                                        "Modified click location: ${adjustedX}, ${adjustedY}"
                                    )

                                    dualWebViewGroup.handleNavigationClick(adjustedX, adjustedY)
                                    return true
                                }

                                // Handle toggle bar clicks
                                if (adjustedX < 48 && adjustedY < 592) {
                                    //Log.d("AnchoredTouchDebug","Toggle Bar Location: ${toggleBarLocation[0]}, ${toggleBarLocation[1]}")

                                    isSimulatingTouchEvent = false
                                    dualWebViewGroup.handleNavigationClick(adjustedX, adjustedY)
                                    return true
                                }


                                dispatchTouchEventAtCursor()
                            }
                        }

                        else -> {
                            isSimulatingTouchEvent = true
                            toggleCursorVisibility()
                            Log.d("TouchDebug", "Toggling Cursor Visibility")
                        }
                    }
                    return true
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(
                    "DoubleTapDebug",
                    """Handling double tap as back navigation. isProcessingDoubleTap: $isProcessingDoubleTap"""
                )

                synchronized(doubleTapLock) {
                    if (isProcessingDoubleTap) return true
                    isProcessingDoubleTap = true
                    pendingDoubleTapAction = true

                    handler.postDelayed({
                        synchronized(doubleTapLock) {
                            try {
                                if (pendingDoubleTapAction && tapCount < 3) {
                                    performDoubleTapBackNavigation()
                                }
                            } finally {
                                tapCount = 0
                                lastTapTime = 0L
                                pendingDoubleTapAction = false
                                isProcessingDoubleTap = false
                            }
                        }
                    }, DOUBLE_TAP_CONFIRMATION_DELAY)
                }

                return true
            }

            private fun performDoubleTapBackNavigation() {
                val isScreenMasked = dualWebViewGroup.isScreenMasked()
                val hasHistory = webView.canGoBack()

                Log.d(
                    "DoubleTapDebug",
                    """Double tap confirmed. isScreenMasked=$isScreenMasked, isKeyboardVisible=$isKeyboardVisible, canGoBack=$hasHistory"""
                )

                if (!hasHistory) {
                    Log.d("DoubleTapDebug", "No history entry available for goBack()")
                    return
                }

                onNavigationBackPressed()
            }
            private var continuousScrollRunnable: Runnable? = null

            private fun stopContinuousScroll() {
                continuousScrollRunnable?.let { handler.removeCallbacks(it) }
                continuousScrollRunnable = null
            }

            private fun startContinuousScroll(initialVelocity: Float, isVertical: Boolean) {
                // Tune these to taste
                var velocity = initialVelocity
                val friction = 0.90f        // lower = longer glide
                val minVelocity = 18f       // cutoff
                val gain = 0.020f           // scales velocity to pixels per frame

                continuousScrollRunnable = object : Runnable {
                    override fun run() {
                        if (kotlin.math.abs(velocity) < minVelocity) {
                            continuousScrollRunnable = null
                            return
                        }

                        val delta = (velocity * gain)
                        if (isVertical) {
                            // positive delta → content moves down; we already sent -velocityY on start for UP
                            webView.scrollBy(0, delta.toInt())
                        } else {
                            webView.scrollBy(delta.toInt(), 0)
                        }

                        velocity *= friction
                        handler.postDelayed(this, 16) // ~60fps
                    }
                }
                handler.post(continuousScrollRunnable!!)
            }
            private fun nudgeCursor(dx: Float, dy: Float) {
                // Move the cursor one step with no inertia.
                // Reuse your existing runnable, but don't spin—just a single tick.
                currentVelocityX = dx
                currentVelocityY = dy
                handler.post(updateCursorRunnable)
                // Zero out so it stops after the single update
                currentVelocityX = 0f
                currentVelocityY = 0f
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {

                Log.d("GestureDebug", """
        --- onFling (X3: cursor > continuous) ---
        vX=$velocityX vY=$velocityY
        cursor=$isCursorVisible anchored=$isAnchored keyboard=$isKeyboardVisible
        ringEnabled=$isRingSwitchEnabled ringConnected=$isRingConnected
    """.trimIndent())
                val isX3 = com.ffalcon.mercury.android.sdk.util.DeviceUtil.isX3Device()
                if (!isX3) {
                    // Ring guard
                    if (e2.device?.name?.contains(
                            "Virtual",
                            ignoreCase = true
                        ) == true && !isRingSwitchEnabled
                    ) {
                        Log.d("GestureDebug", "Ignored fling (ring off)")
                        return true
                    }
                    if (e2.device?.name?.contains(
                            "Virtual",
                            ignoreCase = true
                        ) == true && isRingSwitchEnabled && !isAnchored
                    ) {
                        Log.d("GestureDebug", "Ring fling → handleScroll($velocityX)")
                        handleScroll(velocityX)
                        return true
                    }

                    // Thresholds
                    val dx = e2.x - (e1?.x ?: 0f)
                    val dy = e2.y - (e1?.y ?: 0f)
                    val total = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (kotlin.math.abs(velocityX) < MINIMUM_FLING_VELOCITY || total < MINIMUM_FLING_DISTANCE) {
                        Log.d("GestureDebug", "Below fling threshold → tap")
                        potentialTapEvent?.let { down ->
                            onSingleTapConfirmed(down); down.recycle()
                        }
                        potentialTapEvent = null
                        return true
                    }

                    handleUserInteraction()

                    if (tripleClickMenu.isMenuVisible() || dualWebViewGroup.isScreenMasked()) {
                        Log.d("GestureDebug", "Menu/mask active → consume")
                        return true
                    }

                    if (dualWebViewGroup.isBookmarksExpanded()) {
                        Log.d(
                            "GestureDebug",
                            "Bookmarks open → DualWebViewGroup.handleFling($velocityX)"
                        )
                        dualWebViewGroup.handleFling(velocityX)
                        return true
                    }

                    if (isKeyboardVisible) {
                        if (isAnchored) {
                            val slowed = velocityX * 0.15f
                            Log.d(
                                "GestureDebug",
                                "Keyboard+Anchored → JS smooth scroll (slowed=$slowed, horiz=$isHorizontalScroll)"
                            )
                            if (isHorizontalScroll) {
                                webView.evaluateJavascript(
                                    "window.scrollBy({left:${(-slowed).toInt()},behavior:'smooth'});",
                                    null
                                )
                            } else {
                                webView.evaluateJavascript(
                                    "window.scrollBy({top:${(-slowed).toInt()},behavior:'smooth'});",
                                    null
                                )
                            }
                        } else {
                            Log.d(
                                "GestureDebug",
                                "Keyboard visible → keyboardView.handleFlingEvent($velocityX)"
                            )
                            keyboardView?.handleFlingEvent(velocityX)
                        }
                        return true
                    }

                    // ======== Non-X3 legacy path ========
                    if (isAnchored) {
                        Log.d("GestureDebug", "Anchored (legacy) → handleScroll($velocityX)")
                        handleScroll(velocityX)
                        return true
                    }

                    if (isCursorVisible && !(isRingConnected && isRingSwitchEnabled)) {
                        val scale = 1f / 100f
                        currentVelocityX = velocityX * scale
                        currentVelocityY = 0f
                        Log.d(
                            "GestureDebug",
                            "Cursor inertia (legacy) vX=$velocityX → curVx=$currentVelocityX"
                        )
                        handler.post(updateCursorRunnable)
                        return true
                    }

                    Log.d("GestureDebug", "Default legacy → handleScroll($velocityX)")
                    handleScroll(velocityX)
                    return true
                } else {
                    return false
                }
            }

            // Update for smoother, responsive movement with looping
            private val updateCursorRunnable = object : Runnable {
                override fun run() {
                    if (abs(currentVelocityX) < 0.1f && abs(currentVelocityY) < 0.1f) {
                        handler.removeCallbacks(this)
                    } else {
                        // Update position with wrapping
                        lastCursorX = (lastCursorX + currentVelocityX) % 640
                        lastCursorY = (lastCursorY + currentVelocityY) % 480

                        // Handle wrapping for negative values
                        if (lastCursorX < 0) lastCursorX += 640
                        if (lastCursorY < 0) lastCursorY += 480

                        // Update cursor position
                        refreshCursor()

                        // Apply decay
                        currentVelocityX *= movementDecay
                        currentVelocityY *= movementDecay

                        handler.postDelayed(this, updateInterval)
                    }
                }
            }
        })




        // Create and set up bookmarks view
        val bookmarksView = BookmarksView(this).apply {
            setKeyboardListener(this@MainActivity)  // Set keyboard listener directly
            setBookmarkListener(this@MainActivity)  // Add this line to set the bookmark listener
        }

        // Set up bookmarks view in DualWebViewGroup
        dualWebViewGroup.setBookmarksView(bookmarksView)

        bookmarksView.setKeyboardListener(this)
        Log.d("BookmarksDebug","BookmarksView set in onCreate")

        // Set up the keyboard listener
        dualWebViewGroup.keyboardListener = object : DualWebViewGroup.KeyboardListener {
            override fun onShowKeyboard() {
                //Log.d("LinkEditing", "MainActivity keyboard listener - showCustomKeyboard called")
                showCustomKeyboard()
            }

            override fun onHideKeyboard() {
                //Log.d("LinkEditing", "MainActivity keyboard listener - hideCustomKeyboard called")
                hideCustomKeyboard()
            }
        }




        // Set up the cursor views directly in the main container
        cursorLeftView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(32, 32) // Adjust size as needed
            setImageResource(R.drawable.cursor_arrow_image)
            x = 320f
            y = 240f
            visibility = View.GONE
        }
        cursorRightView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(32, 32)
            setImageResource(R.drawable.cursor_arrow_image)
            x = 960f
            y = 240f
            visibility = View.GONE
        }

        // Add cursor views to the main container
        mainContainer.apply {
            addView(cursorLeftView)
            addView(cursorRightView)
        }




        webView = dualWebViewGroup.getWebView()
        Log.d("WebViewDebug", "Initial WebView state - URL: ${webView.url}")

        webView.setOnTouchListener { _, event ->
            Log.d("TouchDebug", """
        WebView onTouch:
        Action: ${event.action}
        Coordinates: (${event.x}, ${event.y})
        isAnchored: $isAnchored
        isSimulatingTouchEvent: $isSimulatingTouchEvent
    """.trimIndent())



            // Clear any pending touch events when a new touch starts
            // or when touch ends/cancels
            if (event.action == MotionEvent.ACTION_DOWN ||
                event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL) {
                pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
                pendingTouchRunnable = null
            }


            if (isAnchored && isKeyboardVisible) {
                return@setOnTouchListener false
            }

            if (isSimulatingTouchEvent) {
                return@setOnTouchListener false
            }

            if (isKeyboardVisible) {
                return@setOnTouchListener true
            }

            val handled = gestureDetector.onTouchEvent(event)

            // Add check for settings menu visibility
            if (dualWebViewGroup.isSettingsVisible()) {
                return@setOnTouchListener isCursorVisible  // Let the event propagate to the settings menu
            }

            // In scroll mode, let the gesture detector handle taps but allow scrolling
            if (dualWebViewGroup.isInScrollMode()) {
                return@setOnTouchListener handled // Return true if gesture detector handled it
            }

            if (isCursorVisible) {
                return@setOnTouchListener true
            }


            if (event.action == MotionEvent.ACTION_UP && !handled) {
                webView.performClick()
            }

            handled
        }


        // Disable the default keyboard
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // If cursor was visible, store its position
                if (isCursorVisible) {
                    lastKnownCursorX = lastCursorX
                    lastKnownCursorY = lastCursorY
                }


            }



            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Force enable input on all potential input fields
                webView.evaluateJavascript("""
                (function() {
                    function enableInput(element) {
                        element.style.webkitUserSelect = 'text';
                        element.style.userSelect = 'text';
                        element.setAttribute('inputmode', 'text');
                    }
                    
                    document.querySelectorAll('input,textarea,[contenteditable="true"]')
                        .forEach(enableInput);
                        
                    // Create observer for dynamically added elements
                    new MutationObserver((mutations) => {
                        mutations.forEach((mutation) => {
                            mutation.addedNodes.forEach((node) => {
                                if (node.nodeType === 1) {  // ELEMENT_NODE
                                    if (node.matches('input,textarea,[contenteditable="true"]')) {
                                        enableInput(node);
                                    }
                                    node.querySelectorAll('input,textarea,[contenteditable="true"]')
                                        .forEach(enableInput);
                                }
                            });
                        });
                    }).observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                })();
            """, null)

                wasKeyboardDismissedByEnter = false

                // Log focus state
                Log.d("WebViewDebug", "WebView focus state: ${view?.isFocused}")
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                Log.d("WebDebug", "History updated - url: $url, canGoBack: ${view?.canGoBack()}")
            }






        }

        cursorLeftView.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        cursorRightView.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        webView.setBackgroundColor(Color.BLACK)

        dualWebViewGroup.scrollDirectionListener = this
        isHorizontalScroll = false
        dualWebViewGroup.updateBrowsingMode(false)
        dualWebViewGroup.updateScrollToggleButtons(false)  // Start with vertical scroll



        // Set up the listener
        dualWebViewGroup.linkEditingListener = this
        Log.d("LinkEditing", "Set MainActivity as linkEditingListener")

        // Add after other listener assignments
        dualWebViewGroup.anchorToggleListener = this

        // Initialize sensor manager
        sensorManager  = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // After initializing webView and dualWebViewGroup but before loadInitialPage()
        // Set initial cursor position
        // Make cursor visible
        cursorLeftView.visibility = View.VISIBLE
        cursorRightView.visibility = View.VISIBLE
        centerCursor(true)

        resetScrollModeTimer()
        Log.d("ScrollModeDebug", "Initial scroll mode timer started")

        // Start in anchored mode
        rotationSensor?.let { sensor ->
            sensorEventListener = createSensorEventListener()
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        dualWebViewGroup.startAnchoring()


        // Then try to restore the previous state
        setupWebView()  // This will attempt to load the saved URL

// Only clear cache/history if restoration failed
        if (webView.url == null || webView.url == "about:blank") {
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl("https://www.google.com")
        }
        // Initialize camera after WebView setup
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            initializeCamera()
        } else {
            // Request camera permission if we don't have it
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
        initializeSpeechRecognition()  // Initialize speech recognition after WebView setup

        // Call permission check during setup
        checkAndRequestPermissions()


        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            Log.e("Sensor", "No rotation vector sensor found")
        } else {
            Log.d("Sensor", "Rotation vector sensor found")
        }

        // Get reference to ring button through DualWebViewGroup's navigation bar
        dualWebViewGroup.leftNavigationBar.findViewById<ImageButton>(R.id.btnRingSwitch)?.apply {
            isEnabled = false  // Initially disabled
            alpha = 0.5f      // Visual feedback for disabled state
            setImageResource(R.drawable.ic_ring_disabled)
        }

    }

    // Add method to handle hyperlink button press
    override fun onHyperlinkPressed() {
        Log.d("LinkEditing", "onHyperlinkPressed called")
        dualWebViewGroup.showLinkEditing()

    }

    override fun onPause() {
        super.onPause()
        if (isAnchored) {
            // Just unregister the sensor listener to save resources
            sensorManager.unregisterListener(sensorEventListener)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAnchored) {
            // Re-register the sensor listener
            rotationSensor?.let { sensor ->
                sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    // Add this to your onCreate after setContentView
    private fun initializeRing() {
        mLauncher = Launcher.getInstance(this)
        mLauncher.addOnResponseListener(ringResponseListener)
        RingIPCHelper.registerRingInfo(this)
        RingIPCHelper.setRingIMU(this, true) // Enable IMU for orientation data
    }


    override fun getCurrentUrl(): String {
        return dualWebViewGroup.getWebView().url ?: "https://www.google.com"
    }


    override fun onBookmarkSelected(url: String) {
        val formattedUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.contains(".") -> "https://$url"
            else -> "https://www.google.com/search?q=${Uri.encode(url)}"
        }
        webView.loadUrl(formattedUrl)
    }

    private fun handleMaskToggle() {
        //de-anchor when masking to avoid issues
        if (isAnchored){
            toggleAnchor()
        }

        // Store current cursor state before masking
        preMaskCursorState = isCursorVisible
        preMaskCursorX = lastCursorX
        preMaskCursorY = lastCursorY

        // Hide cursor
        isCursorVisible = false
        cursorLeftView.visibility = View.GONE
        cursorRightView.visibility = View.GONE
        refreshCursor(false)

        // Mask the screen
        dualWebViewGroup.maskScreen()
    }


    private fun resetScrollModeTimer() {
        // Always clear any existing timer first
        scrollModeHandler.removeCallbacks(scrollModeRunnable)

        // Start timer if cursor is visible and keyboard isn't
        if (isCursorVisible && !isKeyboardVisible) {
            Log.d("ScrollModeDebug", "Starting scroll mode timer")
            scrollModeHandler.postDelayed(scrollModeRunnable, SCROLL_MODE_TIMEOUT)
        } else {
            Log.d("ScrollModeDebug", """
            Timer not started because:
            Cursor visible: $isCursorVisible
            Keyboard visible: $isKeyboardVisible
        """.trimIndent())
        }
    }

    // Handle ring orientation data to move cursor
    private fun handleRingOrientation(w: Double, x: Double, y: Double, z: Double) {
        if (!isRingConnected) return

        if (!shouldUseRingControls() || !isRingSwitchEnabled) {
            return  // Don't process ring data if disabled or switch is off
        }

        val currentQuaternion = Quaternion(w, x, y, z)

        // Store initial orientation when we start
        if (initialQuaternion == null) {
            initialQuaternion = currentQuaternion
            lastQuaternion = currentQuaternion
            return
        }

        // Calculate relative movement
        var deltaX = (currentQuaternion.y - lastQuaternion!!.y)
        var deltaY = (currentQuaternion.x - lastQuaternion!!.x)
        val sensitivity = 1000.0

        synchronized(cursorToggleLock) {
            if (isCursorVisible) {
                lastCursorX = (lastCursorX + (deltaX * sensitivity).toFloat()).coerceIn(0f, 640f)
                lastCursorY = (lastCursorY + (deltaY * sensitivity).toFloat()).coerceIn(0f, 480f)
                scheduleCursorUpdate()
            }
        }

        // Update last known position outside the lock since it's independent
        lastQuaternion = currentQuaternion
    }

    private fun shouldUseRingControls(): Boolean {
        return isRingConnected && imuStatus == 1 && isRingSwitchEnabled
    }

    private fun resetRingReference() {
        initialQuaternion = null
        lastQuaternion = null
    }

    override fun onSendCharacterToLink(character: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition = dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            Log.d("LinkEditing", """
            Before character insertion:
            Text: '$currentText'
            Cursor position: $currentPosition
            Character to insert: '$character'
        """.trimIndent())

            val newText = StringBuilder(currentText)
                .insert(currentPosition, character)
                .toString()

            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(currentPosition + 1)

            // Log after modification
            Log.d("LinkEditing", """
            After character insertion:
            New text: '$newText'
            Expected cursor position: ${currentPosition + 1}
            Actual cursor position: ${dualWebViewGroup.getCurrentUrlEditField()?.selectionStart}
        """.trimIndent())
        }
    }

    override fun onSendBackspaceInLink() {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition = dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            if (currentPosition > 0) {
                // Delete character before cursor position
                val newText = StringBuilder(currentText)
                    .deleteCharAt(currentPosition - 1)
                    .toString()

                dualWebViewGroup.setLinkText(newText)

                // Move cursor back one position
                dualWebViewGroup.getCurrentUrlEditField()?.setSelection(currentPosition - 1)
            }
        }
    }

    override fun onMaskTogglePressed() {
        handleMaskToggle()
    }


    override fun onSendEnterInLink() {
        isUrlEditing = false
        dualWebViewGroup.toggleIsUrlEditing(false)
        isKeyboardVisible = false
        if (dualWebViewGroup.isUrlEditing()) {
            val url = dualWebViewGroup.getCurrentLinkText()
            val formattedUrl = formatUrl(url)
            webView.loadUrl(formattedUrl)
            dualWebViewGroup.hideLinkEditing()
            hideCustomKeyboard()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            webView.requestFocus()
        }
        Log.d("FocusDebug", "Window focus changed: $hasFocus")
    }

    // Helper function for quaternion multiplication
    fun quaternionMultiply(q1: FloatArray, q2: FloatArray): FloatArray {
        val w = q1[0]*q2[0] - q1[1]*q2[1] - q1[2]*q2[2] - q1[3]*q2[3]
        val x = q1[0]*q2[1] + q1[1]*q2[0] + q1[2]*q2[3] - q1[3]*q2[2]
        val y = q1[0]*q2[2] - q1[1]*q2[3] + q1[2]*q2[0] + q1[3]*q2[1]
        val z = q1[0]*q2[3] + q1[1]*q2[2] - q1[2]*q2[1] + q1[3]*q2[0]
        return floatArrayOf(w, x, y, z)
    }

    // Helper function for quaternion inversion
    fun quaternionInverse(q: FloatArray): FloatArray {
        val magnitudeSquared = q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]
        if (magnitudeSquared == 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val invMagnitude = 1f / magnitudeSquared
        return floatArrayOf(q[0]*invMagnitude, -q[1]*invMagnitude, -q[2]*invMagnitude, -q[3]*invMagnitude)
    }


    private fun initializeCamera() {
        try{
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Set up ImageReader for capturing photos
            imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    // When an image is captured
                    val image = reader.acquireLatestImage()
                    try {
                        // Convert image to base64 for web upload
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        val base64Image = Base64.encodeToString(bytes, Base64.DEFAULT)

                        // Send image back to Google's image search
                        runOnUiThread {
                            webView.evaluateJavascript("""
                            (function() {
                                // Create a File object from base64
                                fetch('data:image/jpeg;base64,$base64Image')
                                    .then(res => res.blob())
                                    .then(blob => {
                                        const file = new File([blob], "image.jpg", { type: 'image/jpeg' });
                                        
                                        // Find or create file input
                                        let input = document.querySelector('input[type="file"][name="encoded_image"]');
                                        if (!input) {
                                            input = document.createElement('input');
                                            input.type = 'file';
                                            input.name = 'encoded_image';
                                            document.body.appendChild(input);
                                        }
                                        
                                        // Create FileList with our image
                                        const dataTransfer = new DataTransfer();
                                        dataTransfer.items.add(file);
                                        input.files = dataTransfer.files;
                                        
                                        // Trigger form submission
                                        input.dispatchEvent(new Event('change', { bubbles: true }));
                                    });
                            })();
                        """.trimIndent(), null)
                        }
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e("Camera", "Failed to initialize camera system", e)
            runOnUiThread {
                webView.evaluateJavascript(
                    "alert('Failed to initialize camera system.');",
                    null
                )
            }
        }

    }


    override fun onSendClearInLink() {
        if (dualWebViewGroup.isUrlEditing()) {
            dualWebViewGroup.setLinkText("")
            // Set cursor at the beginning
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(0)
        }
    }


    override fun onShowKeyboardForEdit(text: String) {
        Log.d("MainActivity", "onShowKeyboardForEdit called with text: $text")

        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { showCustomKeyboard() }
            return
        }

        showCustomKeyboard()
    }

    override fun onShowKeyboardForNew() {
        showCustomKeyboard()
    }


    private fun adjustViewportForKeyboard() {
        val totalHeight = 480 // Total screen height
        val keyboardHeight = 220 // Keyboard height
        val inputFieldHeight = 48 // Standard height for input fields
        //val safeZone = 20 // Extra padding above keyboard
        val visibleHeight = totalHeight - keyboardHeight

        fun positionInputField(fieldY: Int) {
            val adjustment = when {
                // Field is below the safe visible area
                fieldY > (visibleHeight - inputFieldHeight ) -> {
                    -(fieldY - (visibleHeight - inputFieldHeight))
                }

                // Field is already in visible area
                else -> 0
            }

            // Delegate viewport adjustments to DualWebViewGroup
            dualWebViewGroup.adjustViewportAndFields(adjustment.toFloat())
        }

        // Get the current input field location
        val currentField = when {
            dualWebViewGroup.isUrlEditing() -> dualWebViewGroup.getCurrentUrlEditField()
            dualWebViewGroup.isBookmarksExpanded() -> dualWebViewGroup.getBookmarksView().getCurrentEditField()
            else -> {
                // WebView input fields code remains the same
                webView.evaluateJavascript("""
                (function() {
                    var element = document.activeElement;
                    if (!element) return null;
                    var rect = element.getBoundingClientRect();
                    return JSON.stringify({
                        top: rect.top,
                        bottom: rect.bottom
                    });
                })();
            """) { result ->
                    if (result != "null") {
                        try {
                            val metrics = JSONObject(result.trim('"'))
                            positionInputField(metrics.getInt("top"))
                        } catch (e: Exception) {
                            Log.e("ViewportAdjust", "Error parsing field position", e)
                        }
                    }
                }
                return
            }
        }

        // Get the Y position of the current field
        currentField?.let { field ->
            val location = IntArray(2)
            field.getLocationOnScreen(location)
            positionInputField(location[1])
        } ?: run {
            // If no field is found, just adjust to show the top of the viewport
            positionInputField(0)
        }

        // Delegate animation to DualWebViewGroup
        dualWebViewGroup.animateViewportAdjustment()
    }

    override fun onScrollDirectionToggled(isHorizontal: Boolean) {
        isHorizontalScroll = isHorizontal
        Log.d("MainActivity", "Scroll direction changed to: ${if (isHorizontal) "horizontal" else "vertical"}")

    }

    override fun onShowLinkEditing() {
        Log.d("LinkEditing", "onShowLinkEditing called")
        isUrlEditing = true  // Make sure this state is set
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        showCustomKeyboard()
    }



    override fun onHideLinkEditing() {
        Log.d("LinkEditing", "onHideLinkEditing called")
        isUrlEditing = false
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        Log.d("LinkEditing", "isUrlEditing set to false")
        hideCustomKeyboard()
    }

    private fun sendCharacterToLinkEditText(character: String) {
        Log.d("LinkEditing", "sendCharacterToLinkEditText called with: $character")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition = dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length
            Log.d("LinkEditing", """
            Before insertion:
            Text: '$currentText'
            Cursor position: $cursorPosition
        """.trimIndent())

            val newText = StringBuilder(currentText)
                .insert(cursorPosition, character)
                .toString()
            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition + 1)

            Log.d("LinkEditing", """
            After insertion:
            Text: '$newText'
            Cursor position: ${dualWebViewGroup.getCurrentUrlEditField()?.selectionStart}
        """.trimIndent())
        }
    }

    private fun sendBackspaceInLinkEditText() {
        Log.d("LinkEditing", "sendBackspaceInLinkEditText called")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition = dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length
            Log.d("LinkEditing", """
            Before backspace:
            Text: '$currentText'
            Cursor position: $cursorPosition
        """.trimIndent())

            if (cursorPosition > 0) {
                val newText = StringBuilder(currentText)
                    .deleteCharAt(cursorPosition - 1)
                    .toString()
                dualWebViewGroup.setLinkText(newText)
                dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition - 1)

                Log.d("LinkEditing", """
                After backspace:
                Text: '$newText'
                Cursor position: ${dualWebViewGroup.getCurrentUrlEditField()?.selectionStart}
            """.trimIndent())
            }
        }
    }

    private fun sendEnterInLinkEditText() {
        if (dualWebViewGroup.isUrlEditing()) {
            val url = dualWebViewGroup.getCurrentLinkText()
            val formattedUrl = formatUrl(url)
            webView.loadUrl(formattedUrl)
            dualWebViewGroup.hideLinkEditing()
            keyboardListener?.onHideKeyboard()
        }
    }




    override fun isLinkEditing(): Boolean = isUrlEditing

    private fun formatUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.contains(".") -> "https://$url"
            else -> "https://www.google.com/search?q=${Uri.encode(url)}"
        }
    }




    private fun handleScroll(velocityX: Float) {
        if (!isCursorVisible || isAnchored || (isRingConnected && isRingSwitchEnabled)) {
            val slowedVelocity = velocityX * 0.15
            Log.d("ScrollDebug", "in handleScroll")
            // Only check for scrollable elements if we're in horizontal scroll mode
            if (isHorizontalScroll) {
                webView.evaluateJavascript("""
            (function() {
                // Find element at last cursor position before cursor was hidden
                var element = document.elementFromPoint(${lastKnownWebViewX}, ${lastKnownWebViewY});
                var scrollableParent = null;
                
                // Look for horizontally scrollable elements at or above the cursor position
                while (element) {
                    const style = window.getComputedStyle(element);
                    const hasHorizontalScroll = (
                        element.scrollWidth > element.clientWidth &&
                        (style.overflowX === 'auto' || 
                         style.overflowX === 'scroll' ||
                         style.overflowY === 'auto' ||
                         style.overflowY === 'scroll' ||
                         element.classList.contains('scrollable') ||
                         element.getAttribute('role') === 'slider' ||
                         /carousel|slider|scroll/i.test(element.className))
                    );
                    
                    if (hasHorizontalScroll) {
                        scrollableParent = element;
                        break;
                    }
                    element = element.parentElement;
                }
                
                const scrollAmount = ${(-slowedVelocity).toInt()};
                
                if (scrollableParent) {
                    // Scroll the scrollable container that was found
                    scrollableParent.scrollBy({
                        left: scrollAmount,
                        behavior: 'smooth'
                    });
                    return true;
                } else {
                    // No scrollable element found - scroll the whole page horizontally
                    window.scrollBy({
                        left: scrollAmount,
                        behavior: 'smooth'
                    });
                    return false;
                }
            })();
            """) { result ->
                    // If JavaScript returned false (no scrollable element found), use native scroll as backup
                    if (result == "false") {
                        try {
                            webView.scrollBy((-slowedVelocity).toInt(), 0)
                        } catch (e: Exception) {
                            Log.e("ScrollDebug", "Native horizontal scroll failed", e)
                        }
                    }
                }
            } else {
                // Enhanced vertical scroll handling
                webView.evaluateJavascript(
                    """
            (function() {
                // Force layout recalculation
                document.body.offsetHeight;
                
                // Ensure proper viewport setup
                let viewport = document.querySelector('meta[name="viewport"]');
                if (!viewport) {
                    viewport = document.createElement('meta');
                    viewport.name = 'viewport';
                    document.head.appendChild(viewport);
                }
                viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0';

                // Remove any position:fixed elements that might interfere with scrolling
                const style = document.createElement('style');
                style.textContent = `
                    body { min-height: 100vh; }
                    .scrollable-content { overflow-y: auto; -webkit-overflow-scrolling: touch; }
                `;
                document.head.appendChild(style);

                // Try all possible scroll containers
                function getAllScrollableElements() {
                    const elements = [];
                    const allElements = document.getElementsByTagName('*');
                    
                    for (let element of allElements) {
                        const style = window.getComputedStyle(element);
                        if (
                            element.scrollHeight > element.clientHeight &&
                            (style.overflow === 'auto' || 
                             style.overflow === 'scroll' ||
                             style.overflowY === 'auto' ||
                             style.overflowY === 'scroll' ||
                             element.classList.contains('scrollable') ||
                             /scroll|content|main|article|wrapper/i.test(element.className))
                        ) {
                            elements.push(element);
                        }
                    }
                    return elements;
                }

                // Enhanced scroll attempt with multiple fallbacks
                function attemptScroll(amount) {
                    let scrolled = false;
                    const scrollableElements = getAllScrollableElements();
                    
                    // Try each scrollable container
                    for (let element of scrollableElements) {
                        const originalScroll = element.scrollTop;
                        element.scrollBy({
                            top: amount,
                            behavior: 'smooth'
                        });
                        if (element.scrollTop !== originalScroll) {
                            scrolled = true;
                            break;
                        }
                    }

                    // Fallback methods if no container scroll worked
                    if (!scrolled) {
                        // Try window scroll
                        const windowOriginalScroll = window.scrollY;
                        window.scrollBy({
                            top: amount,
                            behavior: 'smooth'
                        });
                        scrolled = window.scrollY !== windowOriginalScroll;

                        // Try document scroll
                        if (!scrolled) {
                            const docOriginalScroll = document.documentElement.scrollTop;
                            document.documentElement.scrollBy({
                                top: amount,
                                behavior: 'smooth'
                            });
                            scrolled = document.documentElement.scrollTop !== docOriginalScroll;
                        }
                    }

                    return scrolled;
                }

                const scrollAmount = ${(-slowedVelocity).toInt()};
                return attemptScroll(scrollAmount);
            })();
            """) { result ->
                    // If JavaScript scroll failed, try native scroll as fallback
                    if (result == "false") {
                        try {
                            // Try multiple native scroll methods
                            webView.scrollBy(0, (-slowedVelocity).toInt())

//                            // Additional fallback: post a delayed scroll
//                            Handler(Looper.getMainLooper()).postDelayed({
//                                webView.scrollBy(0, (-slowedVelocity).toInt())
//                            }, 16) // One frame delay

                        } catch (e: Exception) {
                            Log.e("ScrollDebug", "Native vertical scroll failed", e)
                        }
                    }
                }
            }
        }
    }

    private fun initializeSpeechRecognition() {
        // Check if speech recognition is available
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { matches ->
                            if (matches.isNotEmpty()) {
                                // Insert the recognized text into the search box
                                webView.evaluateJavascript("""
                                (function() {
                                    var searchInput = document.querySelector('input[name="q"]');
                                    if (searchInput) {
                                        searchInput.value = '${matches[0]}';
                                        searchInput.form.submit();
                                    }
                                })();
                            """.trimIndent(), null)
                            }
                        }
                    }

                    // Implement other RecognitionListener methods with empty bodies
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.e("SpeechRecognition", "Error: $error")
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }


    fun hideCustomKeyboard() {
        Log.d("KeyboardDebug", "Hiding keyboard")

           // First blur any focused element
           webView.evaluateJavascript("""
       (function() {
           const activeElement = document.activeElement;
           if (activeElement && activeElement !== document.body) {
               activeElement.blur();
               // For React/custom components that might need extra cleanup
               const event = new Event('blur', { bubbles: true });
               activeElement.dispatchEvent(event);
           }
       })();
       """, null)

        // First handle cleanup of keyboard state
        keyboardView?.visibility = View.GONE
        isKeyboardVisible = false
        keyboardView?.let { dualWebViewGroup.setKeyboard(it) }

        // Show info bars when keyboard hides
        dualWebViewGroup.showInfoBars()

        // Reset interaction states
        isSimulatingTouchEvent = false
        cursorJustAppeared = false
        isToggling = false
        isProcessingTap = false

        // Instruct DualWebViewGroup to hide the link field
        dualWebViewGroup.hideLinkEditing()

        // Clean up input state
        webView.evaluateJavascript("""
        (function() {
            var activeElement = document.activeElement;
            if (activeElement) {
                activeElement.blur();
            }
        })();
    """, null)

        // Notify DualWebViewGroup about keyboard being hidden
        dualWebViewGroup.onKeyboardHidden()

        // Restore original webView state
        val params = webView.layoutParams
        params.height = originalWebViewHeight
        webView.layoutParams = params
        webView.translationY = 0f

        // Clear any existing animations
        webView.clearAnimation()

        // Force layout update
        webView.requestLayout()
        webView.parent?.requestLayout()

        // Show cursor if not in URL editing mode
//        if (!dualWebViewGroup.isUrlEditing()) {
//            toggleCursorVisibility(forceShow = true)
//        }

        isUrlEditing = false



        dualWebViewGroup.cleanupResources()
    }



    override fun onClearPressed() {
        when {
            dualWebViewGroup.isBookmarksExpanded() && dualWebViewGroup.urlEditText.visibility != View.VISIBLE -> {
                // Handle bookmark menu navigation
                dualWebViewGroup.getBookmarksView().handleKeyboardInput("clear")
            }
            dualWebViewGroup.urlEditText.visibility == View.VISIBLE -> {
                // Clear edit field for both URL and bookmark editing
                dualWebViewGroup.setLinkText("")
            }
            else -> {
                // Preserve existing JavaScript functionality for web content
                runOnUiThread {
                    webView.evaluateJavascript("""
    (function() {
        var el = document.activeElement;
        if (!el) {
            console.log('No active element found');
            return null;
        }
        
        function simulateClearInput(element) {
            // Start composition
            const compStart = new Event('compositionstart', { bubbles: true });
            element.dispatchEvent(compStart);
            
            // Create beforeinput event
            const beforeInputEvent = new InputEvent('beforeinput', {
                bubbles: true,
                cancelable: true,
                inputType: 'deleteContent',
                data: null
            });
            element.dispatchEvent(beforeInputEvent);
            
            if (!beforeInputEvent.defaultPrevented) {
                // Use execCommand for deletion
                if (document.execCommand) {
                    // First select all
                    document.execCommand('selectAll', false);
                    // Then delete selection
                    document.execCommand('delete', false);
                }
                
                // Dispatch native input event
                const nativeInputEvent = new Event('input', { bubbles: true });
                element.dispatchEvent(nativeInputEvent);
            }
            
            // End composition
            const compEnd = new Event('compositionend', { bubbles: true });
            element.dispatchEvent(compEnd);
            
            // Handle React components
            if (element._valueTracker) {
                element._valueTracker.setValue('');
                element.dispatchEvent(new Event('input', { bubbles: true }));
            }
            
            return {
                success: true,
                type: element.type
            };
        }
        
        return JSON.stringify(simulateClearInput(el));
    })();
    """, null)
                }
            }
        }
    }

    override fun onMoveCursorLeft() {
        //Log.d("MainActivity", "onMoveCursorLeft() called")
        runOnUiThread {
            moveCursor(-1)
        }
    }

    override fun onMoveCursorRight() {
        //Log.d("MainActivity", "onMoveCursorRight() called")
        runOnUiThread {
            moveCursor(1)
        }
    }

    private fun moveCursor(offset: Int) {
        val focusedView = currentFocus
        if (focusedView != null) {
            val inputConnection = BaseInputConnection(focusedView, true)
            val now = SystemClock.uptimeMillis()
            val keyCode = if (offset < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val keyEventDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val keyEventUp = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            inputConnection.sendKeyEvent(keyEventDown)
            inputConnection.sendKeyEvent(keyEventUp)
            //Log.d("MainActivity", "Sent cursor move key event: $keyCode")
        } else {
            Log.d("MainActivity", "No focused view to move cursor")
        }
    }

    fun injectJavaScriptForInputFocus() {
        webView.evaluateJavascript("""
    (function() {
        // Store state about known popups to prevent re-triggering
        const knownPopups = new WeakSet();
        
        function canActuallyInputText(element) {
            try {
                // If we've previously identified this as part of a popup, skip input checks
                if (knownPopups.has(element)) {
                    console.log('Element is part of known popup, skipping input checks');
                    return false;
                }

                // First check if it's a popup/menu element
                if (element.getAttribute('aria-haspopup') === 'true' ||
                    element.getAttribute('aria-expanded') === 'false' ||
                    element.getAttribute('role') === 'button' ||
                    element.getAttribute('role') === 'menu' ||
                    element.getAttribute('role') === 'menuitem') {
                    console.log('Element identified as popup/menu');
                    
                    // Mark this and its children as known popup elements
                    knownPopups.add(element);
                    element.querySelectorAll('*').forEach(child => knownPopups.add(child));
                    
                    return false;
                }

                // Rest of the input detection code remains the same
                if (element instanceof HTMLInputElement) {
                    const textInputTypes = ['text', 'email', 'password', 'search', 'tel', 'url', 'number'];
                    return textInputTypes.includes(element.type);
                }
                
                if (element instanceof HTMLTextAreaElement) return true;
                if (element.isContentEditable) return true;

                return false;
            } catch (e) {
                console.log('Input validation error: ' + e.toString());
                return false;
            }
        }

        // Function to handle clicks
        function handleClick(event) {
            console.log('Click event detected');
            let target = event.target;
            let currentNode = target;
            
            // Log the click path
            console.log('Click path:', {
                targetTag: target.tagName,
                targetClass: target.className,
                targetRole: target.getAttribute('role')
            });
            
            while (currentNode && currentNode !== document.body) {
                if (canActuallyInputText(currentNode)) {
                    console.log('Found input-capable element');
                    window.Android?.onInputFocus();
                    break;
                }
                currentNode = currentNode.parentElement;
            }
        }

        // Remove any existing listeners to prevent duplicates
        document.removeEventListener('click', handleClick, true);
        
        // Add the click listener
        document.addEventListener('click', handleClick, true);

        // Set up a more robust mutation observer
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                // For any new nodes, check if they're part of a popup
                mutation.addedNodes.forEach((node) => {
                    if (node.nodeType === 1) { // ELEMENT_NODE
                        if (node.getAttribute('role') === 'menu' ||
                            node.getAttribute('role') === 'dialog' ||
                            node.getAttribute('aria-haspopup') === 'true') {
                            console.log('New popup/menu element detected');
                            knownPopups.add(node);
                            // Mark all children as part of the popup
                            node.querySelectorAll('*').forEach(child => knownPopups.add(child));
                        }
                    }
                });
            });
        });

        observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['role', 'aria-haspopup']
        });
    })();
    """, null)
    }

    private fun sendCharacterToWebView(character: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            sendCharacterToLinkEditText(character)
        } else {
            //Log.d("InputDebug", "Sending character: $character")
            webView.evaluateJavascript("""
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }
            
            // Create a composition event to better simulate natural typing
            function simulateNaturalInput(element, char) {
                // First, create a compositionstart event
                const compStart = new Event('compositionstart', { bubbles: true });
                element.dispatchEvent(compStart);
                
                // Then create main input event with the data
                const inputEvent = new InputEvent('input', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: char,
                    composed: true
                });
                
                // Store original value
                const originalValue = element.value || '';
                
                // Create a beforeinput event
                const beforeInputEvent = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: char
                });
                element.dispatchEvent(beforeInputEvent);
                
                if (!beforeInputEvent.defaultPrevented) {
                    // Let the browser handle the input naturally
                    const nativeInputEvent = new Event('input', { bubbles: true });
                    element.dispatchEvent(nativeInputEvent);
                    
                    // Use execCommand for more natural insertion
                    if (document.execCommand) {
                        document.execCommand('insertText', false, char);
                    } else {
                        // Fallback: try to preserve cursor position
                        const start = element.selectionStart;
                        const end = element.selectionEnd;
                        element.value = originalValue.slice(0, start) + 
                                      char + 
                                      originalValue.slice(end);
                    }
                }
                
                // Finally dispatch composition end
                const compEnd = new Event('compositionend', { bubbles: true });
                element.dispatchEvent(compEnd);
                
                // Ensure React and other frameworks pick up the change
                if (element._valueTracker) {
                    element._valueTracker.setValue(originalValue);
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                }
                
                return {
                    success: true,
                    type: element.type,
                    originalValue: originalValue,
                    newValue: element.value
                };
            }
            
            return JSON.stringify(simulateNaturalInput(el, '$character'));
        })();
        """) { result ->
                Log.d("InputDebug", "JavaScript result: $result")
            }
        }
    }


    private fun sendBackspaceToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendBackspaceInLinkEditText()
        } else {
            //Log.d("InputDebug", "Sending backspace")
            webView.evaluateJavascript("""
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }
            
            // Capture initial state for verification
            const initialState = {
                value: el.value,
                selectionStart: el.selectionStart,
                selectionEnd: el.selectionEnd
            };
            console.log('Initial state:', JSON.stringify(initialState));
            
            function simulateNaturalBackspace(element) {
                // Signal the upcoming deletion
                const beforeInputEvent = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'deleteContentBackward'
                });
                element.dispatchEvent(beforeInputEvent);
                
                if (!beforeInputEvent.defaultPrevented) {
                    let deletionSuccessful = false;
                    const originalValue = element.value;
                    
                    // Method 1: Try execCommand first
                    if (!deletionSuccessful && document.execCommand) {
                        try {
                            document.execCommand('delete', false);
                            // Verify if deletion worked
                            deletionSuccessful = element.value !== originalValue;
                            console.log('execCommand method:', deletionSuccessful ? 'succeeded' : 'failed');
                        } catch (e) {
                            console.log('execCommand failed:', e);
                        }
                    }
                    
                    // Method 2: Try keyboard events if execCommand didn't work
                    if (!deletionSuccessful) {
                        const backspaceKey = new KeyboardEvent('keydown', {
                            key: 'Backspace',
                            code: 'Backspace',
                            keyCode: 8,
                            which: 8,
                            bubbles: true,
                            cancelable: true
                        });
                        element.dispatchEvent(backspaceKey);
                        
                        // Verify if keyboard event worked
                        deletionSuccessful = element.value !== originalValue;
                        console.log('Keyboard event method:', deletionSuccessful ? 'succeeded' : 'failed');
                    }
                    
                    // Method 3: Manual manipulation as last resort
                    if (!deletionSuccessful) {
                        const start = element.selectionStart;
                        const end = element.selectionEnd;
                        
                        if (start === end && start > 0) {
                            // Delete single character
                            element.value = element.value.substring(0, start - 1) + 
                                          element.value.substring(end);
                            element.setSelectionRange(start - 1, start - 1);
                            deletionSuccessful = true;
                            console.log('Manual deletion succeeded');
                        } else if (start !== end) {
                            // Delete selection
                            element.value = element.value.substring(0, start) + 
                                          element.value.substring(end);
                            element.setSelectionRange(start, start);
                            deletionSuccessful = true;
                            console.log('Manual selection deletion succeeded');
                        }
                    }
                    
                    // Only dispatch input event if we actually made changes
                    if (deletionSuccessful) {
                        element.dispatchEvent(new Event('input', { bubbles: true }));
                        
                        // Handle React components
                        if (element._valueTracker) {
                            element._valueTracker.setValue('');
                            element.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    }
                }
                
                // Capture final state for verification
                const finalState = {
                    value: el.value,
                    selectionStart: el.selectionStart,
                    selectionEnd: el.selectionEnd
                };
                console.log('Final state:', JSON.stringify(finalState));
                
                return {
                    success: true,
                    initialState: initialState,
                    finalState: finalState
                };
            }
            
            return JSON.stringify(simulateNaturalBackspace(el));
        })();
        """) { result ->
                Log.d("InputDebug", "Backspace JavaScript result: $result")
            }
        }
    }


    private fun dispatchTouchEventAtCursor() {
        Log.d("TouchDebug", """
        dispatchTouchEventAtCursor called:
        isSimulatingTouchEvent: $isSimulatingTouchEvent
        cursorJustAppeared: $cursorJustAppeared
        isToggling: $isToggling
        lastCursorX: $lastCursorX
        lastCursorY: $lastCursorY
    """.trimIndent())

        if (isSimulatingTouchEvent || cursorJustAppeared || isToggling) {
            return
        }

        // Check if settings menu is visible first
        if (dualWebViewGroup.isSettingsVisible()) {
            // Get the settings menu bounds and check if cursor is within them
            val settingsMenuLocation = IntArray(2)
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()

            if (lastCursorX >= settingsMenuLocation[0] &&
                lastCursorX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                lastCursorY >= settingsMenuLocation[1] &&
                lastCursorY <= settingsMenuLocation[1] + settingsMenuSize.second) {

                // Dispatch touch event to settings menu
                dualWebViewGroup.dispatchSettingsTouchEvent(lastCursorX, lastCursorY)
                return
            }
        }


        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastClickTime < MIN_CLICK_INTERVAL) {
            return
        }
        lastClickTime = currentTime


// Existing WebView click path
        isSimulatingTouchEvent = true
        try {
            val UILocation = IntArray(2)
            dualWebViewGroup.leftEyeUIContainer.getLocationOnScreen(UILocation)
            val adjustedX: Float
            val adjustedY: Float

            if (isAnchored) {
                // Get rotation from the clip parent
                val rotationRad = Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()

                // The cursor is fixed at (320, 240)
                val cursorX = lastCursorX
                val cursorY = lastCursorY

                // Step 1: Inverse translation (subtract the translation)
                val translatedX = cursorX - UILocation[0]
                val translatedY = cursorY - UILocation[1]

                // Step 2: Inverse rotation (rotate the translated point back to the view's local coordinates)
                adjustedX = translatedX * cos + translatedY * sin - 48f
                adjustedY = -translatedX * sin + translatedY * cos
            } else {
                adjustedX = lastCursorX - UILocation[0]   - 48f //account for toggle bar
                adjustedY = lastCursorY - UILocation[1]
            }
            Log.d("ClickDebug", """
    Click coordinates:
    Raw cursor: ($lastCursorX, $lastCursorY)
    Adjusted for WebView: ($adjustedX, $adjustedY)
    parent window Location: (${UILocation[0]}, ${UILocation[1]})
    Final click position relative to content: (${adjustedX + UILocation[0]}, ${adjustedY + UILocation[1]})
""".trimIndent())
            val eventTime = SystemClock.uptimeMillis()

            // DOWN event
            val motionEventDown = MotionEvent.obtain(
                eventTime, eventTime,
                MotionEvent.ACTION_DOWN,
                adjustedX, adjustedY,
                1  // pointer count
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            webView.dispatchTouchEvent(motionEventDown)

            webView.evaluateJavascript("""
    (function() {
        var element = document.elementFromPoint($adjustedX, $adjustedY);
        var targetUrl = null;
        
        function findTargetUrl(el) {
            if (!el) return null;
            if (el.href) return el.href;
            if (el.dataset && (el.dataset.url || el.dataset.articleUrl)) {
                return el.dataset.url || el.dataset.articleUrl;
            }
            var linkParent = el.closest('a');
            if (linkParent && linkParent.href) return linkParent.href;
            return null;
        }
        
        targetUrl = findTargetUrl(element);
        if (targetUrl && targetUrl.includes('news.google.com')) {
            // Instead of returning the URL, create and trigger a real navigation
            var a = document.createElement('a');
            a.href = targetUrl;
            a.style.display = 'none';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            return "clicked";  // Signal that we handled it
        }
        return null;
    })();
""") { result ->
                // Complete the touch sequence regardless of whether we found a special link
                Handler(Looper.getMainLooper()).postDelayed({
                    val motionEventUp = MotionEvent.obtain(
                        eventTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        adjustedX, adjustedY,
                        1
                    ).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    webView.dispatchTouchEvent(motionEventUp)

                    // Clean up
                    motionEventDown.recycle()
                    motionEventUp.recycle()

                    // Reset states and check keyboard
                    Handler(Looper.getMainLooper()).postDelayed({
                        checkAndShowKeyboard(adjustedX.toInt(), adjustedY.toInt())
                        isSimulatingTouchEvent = false
                        cursorJustAppeared = false
                        isToggling = false
                    }, 150)
                }, 16)
            }

        } catch (e: Exception) {
            Log.e("ClickDebug", "Error in dispatchTouchEventAtCursor: ${e.message}")
            e.printStackTrace()
            isSimulatingTouchEvent = false
        }
    }

    private fun checkAndShowKeyboard(adjustedX: Int, adjustedY: Int) {
        webView.evaluateJavascript("""
        (function() {
            var element = document.elementFromPoint($adjustedX, $adjustedY);
            var node = element;

            function isPopupTrigger(el) {
                return (
                    el.getAttribute('aria-haspopup') === 'true' || 
                    el.getAttribute('aria-expanded') === 'false' ||
                    el.hasAttribute('aria-controls') ||
                    el.tagName.toLowerCase() === 'select' ||
                    el.tagName === 'BUTTON' ||
                    el.getAttribute('role') === 'button' ||
                    el.getAttribute('role') === 'menu' ||
                    el.getAttribute('role') === 'menuitem' ||
                    el.classList.contains('dropdown-toggle') ||
                    /(menu|dropdown|popup|button|signout|logout)/i.test(el.className) ||
                    el.getAttribute('aria-label')?.toLowerCase().includes('sign out')
                );
            }

            function canAcceptTextInput(el) {
                if (!el) return false;

                // First check if this is a button/menu - should take precedence
                const isMenuOrButton = (
                    el.getAttribute('role') === 'button' ||
                    el.getAttribute('role') === 'menuitem' ||
                    el.getAttribute('aria-haspopup') === 'true' ||
                    el.getAttribute('aria-expanded') !== null ||
                    el.tagName === 'BUTTON' ||
                    (el.tagName === 'A' && el.getAttribute('role') === 'button')
                );

                if (isMenuOrButton) {
                    //console.log('Element is a button or menu control');
                    return false;
                }

                // Enhanced check for text cursor and focus state
                const activeElement = document.activeElement;
                if (activeElement && activeElement !== document.body) {
                    console.log('Active element state:', {
                        tagName: activeElement.tagName,
                        className: activeElement.className,
                        hasSelection: window.getSelection().toString().length > 0,
                        selectionRangeCount: window.getSelection().rangeCount,
                        isInput: activeElement instanceof HTMLInputElement,
                        isTextarea: activeElement instanceof HTMLTextAreaElement,
                        selectionStart: 
                            activeElement instanceof HTMLInputElement || 
                            activeElement instanceof HTMLTextAreaElement ? 
                            activeElement.selectionStart : null,
                        isFocusInElement: activeElement === el || activeElement.contains(el) || el.contains(activeElement)
                    });

                    // Check for any visible text selection
                    const selection = window.getSelection();
                    // Check if there's a real text cursor (not just any selection)
                    const hasVisibleCursor = selection && (
                        // Has actual text selection
                        selection.toString().length > 0 ||
                        // Or has a collapsed cursor (blinking text cursor) in an editable element
                        (selection.rangeCount > 0 && 
                         selection.getRangeAt(0).collapsed &&
                         (activeElement.isContentEditable || 
                          activeElement instanceof HTMLInputElement || 
                          activeElement instanceof HTMLTextAreaElement ||
                          // Also check if it's inside a custom editor
                          (activeElement.closest('[contenteditable="true"]') ||
                           activeElement.closest('[role="textbox"]'))))
                    );

                    if (hasVisibleCursor && 
                        (activeElement === el || 
                         activeElement.contains(el) || 
                         el.contains(activeElement))) {
                        //console.log('Found element with visible text cursor');
                        return true;
                    }
                }

                const capabilities = {
                    tagName: el.tagName,
                    className: el.className,
                    isContentEditable: el.isContentEditable,
                    role: el.getAttribute('role'),
                    inputType: el instanceof HTMLInputElement ? el.type : null,
                    isTextarea: el instanceof HTMLTextAreaElement,
                    hasTextboxRole: el.getAttribute('role') === 'textbox',
                    contentEditable: el.getAttribute('contenteditable'),
                    ariaMultiline: el.getAttribute('aria-multiline'),
                    hasSearchRole: el.getAttribute('role') === 'search'
                };
                //console.log('Text input capabilities:', JSON.stringify(capabilities, null, 2));

                if (
                    el.isContentEditable ||
                    el instanceof HTMLTextAreaElement ||
                    el.getAttribute('role') === 'textbox' ||
                    el.getAttribute('role') === 'searchbox' ||
                    el.getAttribute('role') === 'search' ||
                    el.getAttribute('contenteditable') === 'true' ||
                    el.getAttribute('aria-multiline') === 'true' ||
                    (el instanceof HTMLInputElement && 
                        ['text', 'email', 'password', 'search', 'tel', 'url', 'number'].includes(el.type)) ||
                    (el.tagName.toLowerCase().includes('editor') ||
                     el.tagName.toLowerCase().includes('composer') ||
                     el.tagName.toLowerCase().includes('search'))
                ) {
                    //console.log('Element directly supports text input');
        // If the element directly supports text input, check if it can gain focus:
        return canElementGainFocus(el);
                }
                
                

                return false;
            }
            
            function canElementGainFocus(el) {
    try {
        const isAlreadyFocused = document.activeElement === el;
        el.focus();
        // Check if the element is focused after trying to focus
        const isFocused = document.activeElement === el;
        //console.log("Focus attempt: " + isFocused);
        // Remove focus only if it wasn't meant to be focused previously, and we have actually gained focus
        if (isFocused && !isAlreadyFocused) {
            el.blur();
        }
        return isFocused;
    } catch (e) {
        //console.log("Focus error", e);
        return false; // Return false if any exception occurs during focusing
    }
}


            // Check the element and its hierarchy for input capability
            node = element;
            while (node && node !== document.body) {
                if (canAcceptTextInput(node)) {
                    console.log('Input-capable element found:', node);
                    return 'input';
                }
                node = node.parentElement;
            }

            // Check active element with detailed logging
            const activeElement = document.activeElement;
            if (activeElement && activeElement !== document.body) {
                const activeElementInfo = {
                    tagName: activeElement.tagName,
                    className: activeElement.className,
                    isContentEditable: activeElement.isContentEditable,
                    role: activeElement.getAttribute('role'),
                    containsTarget: activeElement.contains(element),
                    isContainedByTarget: element.contains(activeElement)
                };
                //console.log('Active element details:', JSON.stringify(activeElementInfo, null, 2));

                if (activeElement === element || 
                    element.contains(activeElement) || 
                    activeElement.contains(element)) {
                    
                    if (canAcceptTextInput(activeElement)) {
                        //console.log('Active element can accept text input');
                        return 'input';
                    }
                }
            }

            return 'regular';
        })();
    """) { result ->
            Log.d("InputDebug", "Element detection result after touch: $result")
            if (result?.contains("input") == true) {
                Handler(Looper.getMainLooper()).post {
                    showCustomKeyboard()
                }
            }
        }
    }

    private fun showClickIndicator(x: Float, y: Float) {
        webView.evaluateJavascript("""
        (function() {
            const existingIndicator = document.getElementById('click-indicator');
            if (existingIndicator) existingIndicator.remove();
            
            const indicator = document.createElement('div');
            indicator.id = 'click-indicator';
            indicator.style.cssText = `
                position: absolute;
                width: 20px;
                height: 20px;
                background-color: red;
                border-radius: 50%;
                opacity: 0.5;
                pointer-events: none;
                z-index: 10000;
                left: ${x}px;
                top: ${y}px;
            `;
            document.body.appendChild(indicator);
            setTimeout(() => indicator.remove(), 1000);
        })();
    """, null)
    }


    private fun sendEnterToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendEnterInLinkEditText()
        } else {
            //Log.d("InputDebug", "Sending enter")
            webView.evaluateJavascript("""
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }
            
            // Log the active element for debugging
            console.log('Active element for enter:', {
                tagName: el.tagName,
                id: el.id,
                className: el.className,
                type: el.type,
                value: el.value
            });

            function dispatchKeyEvents(element) {
                // Create keydown event
                const keyDown = new KeyboardEvent('keydown', {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                });
                element.dispatchEvent(keyDown);

                // Create keypress event
                const keyPress = new KeyboardEvent('keypress', {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                });
                element.dispatchEvent(keyPress);

                // Create keyup event
                const keyUp = new KeyboardEvent('keyup', {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                });
                element.dispatchEvent(keyUp);

                // Dispatch input and change events
                element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
            }

            // Handle both direct elements and shadow DOM
            if (el.shadowRoot) {
                const shadowInput = el.shadowRoot.querySelector('input, textarea');
                if (shadowInput) {
                    dispatchKeyEvents(shadowInput);
                    return true;
                }
            }

            dispatchKeyEvents(el);
            return true;
        })();
        """) { result ->
                Log.d("InputDebug", "Enter JavaScript result: $result")
                Handler(Looper.getMainLooper()).post {
                    hideCustomKeyboard()
                }
            }
        }
    }



    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // First check if speech recognition is available
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val speechRecognitionAvailable = packageManager.resolveActivity(speechRecognizerIntent, 0) != null
        Log.d("WebView", "Speech recognition available: $speechRecognitionAvailable")



        // Section 1: Basic WebView Configuration
        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE

            // Ensure WebView can receive input methods
//          setOnTouchListener { v, event ->
//                if (!isCursorVisible) {
//                    // Only handle focus if cursor is not visible
//                    v.parent.requestDisallowInterceptTouchEvent(true)
//                }
//                false  // Always allow event to propagate
//            }

            // Section 2: WebView Settings Configuration
            settings.apply {


                // JavaScript and Content Settings
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false

                // Security and Access Settings
                saveFormData = true
                savePassword = true
                allowFileAccess = true
                allowContentAccess = true
                setGeolocationEnabled(true)

                // Display and Layout Settings
                @Suppress("DEPRECATION")
                defaultZoom = WebSettings.ZoomDensity.MEDIUM
                useWideViewPort = true
                loadWithOverviewMode = true
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

                // Disable Unnecessary Zoom Controls
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false

                // Multi-window Support
                setSupportMultipleWindows(false)

                // Handle Mixed Content
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                // Keep secure HTTPS navigation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }

                // Force Dark Mode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    forceDark = WebSettings.FORCE_DARK_ON
                }

                val wvVersion = getWebViewVersion() ?: "114.0.0.0"

                val newUserAgent = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/$wvVersion Mobile Safari/537.36"

                webView.settings.userAgentString = newUserAgent

                // Explicitly enable media
                setMediaPlaybackRequiresUserGesture(false)
            }

            // Enable third-party cookies specifically for auth
            CookieManager.getInstance().apply {
                setAcceptThirdPartyCookies(webView, true)
                setAcceptCookie(true)
                acceptCookie()
            }

            // Section 3: Single WebViewClient Implementation
            webViewClient = object : WebViewClient() {
                private var lastValidUrl: String? = null

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d("WebViewDebug", "Page started loading: $url")

                    if (url != null && !url.startsWith("about:blank")) {
                        lastValidUrl = url
                        view?.visibility = View.INVISIBLE
                    } else if (url?.startsWith("about:blank") == true && lastValidUrl != null) {
                        // Cancel about:blank load immediately
                        view?.stopLoading()
                        view?.loadUrl(lastValidUrl!!)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("WebViewDebug", "Page finished loading: $url")

                    if (url != null && !url.startsWith("about:blank")) {
                        view?.visibility = View.VISIBLE
                        injectJavaScriptForInputFocus()
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // Block about:blank navigations
                    if (url.startsWith("about:blank")) {
                        return true
                    }

                    // Handle app intents
                    if (url.startsWith("intent://") || url.startsWith("market://")) {
                        val fallbackUrl = url.substringAfter("fallback_url=", "")
                            .substringBefore("#", "")
                            .substringBefore("&", "")

                        if (fallbackUrl.isNotEmpty() &&
                            (fallbackUrl.startsWith("http") || fallbackUrl.startsWith("https"))) {
                            view?.loadUrl(fallbackUrl)
                            return true
                        }
                        return true
                    }

                    return false  // Let WebView handle normal URLs
                }
            }
            // Add more detailed logging to track input field interactions
            webView.evaluateJavascript("""
        (function() {
            document.addEventListener('focus', function(e) {
                console.log('Focus event:', {
                    target: e.target.tagName,
                    type: e.target.type,
                    isInput: e.target instanceof HTMLInputElement,
                    isTextArea: e.target instanceof HTMLTextAreaElement,
                    isContentEditable: e.target.isContentEditable
                });
            }, true);
        })();
    """, null)


            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                            // If we have CAMERA granted
                            if (ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                request.grant(request.resources)
                            } else {
                                request.deny()
                            }
                        } else {
                            request.deny()
                        }
                    }
                }

                override fun onReceivedTouchIconUrl(view: WebView?, url: String?, precomposed: Boolean) {
                    Log.d("WebViewDebug", "Received touch icon URL: $url")
                    super.onReceivedTouchIconUrl(view, url, precomposed)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    Log.d("WebViewInput", "${consoleMessage.messageLevel()} [${consoleMessage.lineNumber()}]: ${consoleMessage.message()}")
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // Cancel any ongoing request
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null

                    this@MainActivity.filePathCallback = filePathCallback

                    // Build an Intent array to include camera capture + file choose
                    val takePictureIntent = createCameraIntent()
                    val contentSelectionIntent = createContentSelectionIntent(fileChooserParams?.acceptTypes)

                    // Let user pick from either camera or existing files
                    val intentArray = if (takePictureIntent != null) arrayOf(takePictureIntent) else arrayOfNulls<Intent>(0)

                    // Create a chooser
                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                        putExtra(Intent.EXTRA_TITLE, "Image Chooser")
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray.filterNotNull().toTypedArray())
                    }

                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                    return true
                }
            }





        }


        // Section 5: Input Handling Configuration
        disableDefaultKeyboard()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Before loading the initial page, try to restore the previous session
        Log.d("WebViewDebug", "Attempting to restore previous session")

        try {
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val lastUrl = prefs.getString(keyLastUrl, null)
            Log.d("WebViewDebug", "Last saved URL: $lastUrl")

            val defaultDashboardUrl = "file:///android_asset/AR_Dashboard_Landscape_Sidebar.html"

            if (lastUrl != null && !lastUrl.startsWith("about:blank")) {
                Log.d("WebViewDebug", "Loading saved URL: $lastUrl")
                webView.loadUrl(lastUrl)
            } else {
                Log.d("WebViewDebug", "No valid saved URL, loading default AR dashboard")
                webView.loadUrl(defaultDashboardUrl)
            }
        } catch (e: Exception) {
            Log.e("WebViewDebug", "Error restoring session", e)
            webView.loadUrl("file:///android_asset/AR_Dashboard_Landscape_Sidebar.html")
        }

        setupMediaWebView()
        logPermissionState()  // Log initial permission state

        webView.addJavascriptInterface(AndroidInterface(this), "AndroidInterface")

    }

    @Suppress("DEPRECATION")
    private fun takePictureForSearch(webView: WebView?) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            isCapturing = false
            return
        }

        var camera: Camera? = null
        var surfaceTexture: SurfaceTexture? = null

        try {
            camera = Camera.open()
            Log.d("CameraTest", "Camera opened successfully")

            val parameters = camera.getParameters()
            parameters.setPictureSize(1280, 720)
            camera.setParameters(parameters)

            surfaceTexture = SurfaceTexture(0)
            camera.setPreviewTexture(surfaceTexture)
            camera.startPreview()

            camera.takePicture(
                { Log.d("CameraTest", "Shutter triggered") },
                null,
                { data, _ ->
                    Log.d("CameraTest", "Picture taken, data size: ${data.size}")

                    try {
                        // Store image data and reload the page to trigger onPageFinished
                        capturedImageData = data
                        runOnUiThread {
                            webView?.reload()
                        }
                    } finally {
                        camera?.stopPreview()
                        camera?.release()
                        surfaceTexture?.release()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("CameraTest", "Camera test failed", e)
            camera?.release()
            surfaceTexture?.release()
            isCapturing = false
        }
    }

    private class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun onInputFocus() {
            activity.runOnUiThread {
                // Double check that we're not already showing the keyboard
                if (!activity.isKeyboardVisible) {
                    Log.d("InputDebug", "Input focus confirmed via JavaScript")
                    activity.showCustomKeyboard()
                }
            }
        }
    }


    private fun createCameraIntent(): Intent? {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) == null) {
            // No camera activity on device
            return null
        }
        // Create a file/URI to store the image
        val imageFile = createTempImageFile() ?: return null
        cameraImageUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            imageFile
        )
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        return cameraIntent
    }

    private fun createContentSelectionIntent(acceptTypes: Array<String>?): Intent {
        val mimeTypes = acceptTypes?.filter { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("*/*")
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
    }

    // Example for creating a temp file
    private fun createTempImageFile(): File? {
        return try {
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile(
                "tmp_image_",
                ".jpg",
                storageDir
            )
        } catch (e: IOException) {
            Log.e("FileChooser", "Cannot create temp file", e)
            null
        }
    }

    fun getWebViewVersion(): String? {
        return try {
            // Try Google’s webview package first
            val pInfo = packageManager.getPackageInfo("com.google.android.webview", 0)
            pInfo.versionName // e.g. "114.0.5735.196"
        } catch (e: PackageManager.NameNotFoundException) {
            // Fallback: older AOSP webview, or it may be missing
            try {
                val pInfo = packageManager.getPackageInfo("com.android.webview", 0)
                pInfo.versionName // e.g. "97.0.4692.87"
            } catch (e2: PackageManager.NameNotFoundException) {
                null
            }
        }
    }


    private fun setupMediaWebView() {
        // Initialize AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.d("WebView", "Permission request: ${request.resources.joinToString()}")

                val permissions = mutableListOf<String>()
                val requiredAndroidPermissions = mutableListOf<String>()

                request.resources.forEach { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            permissions.add(resource)
                            requiredAndroidPermissions.add(android.Manifest.permission.RECORD_AUDIO)
                            // Configure AR glasses microphone for voice assistant mode
                            audioManager?.setParameters("audio_source_record=voiceassistant")
                        }
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            permissions.add(resource)
                            requiredAndroidPermissions.add(android.Manifest.permission.CAMERA)
                        }
                    }
                }

                runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val notGrantedPermissions = requiredAndroidPermissions.filter {
                            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                        }

                        if (notGrantedPermissions.isNotEmpty()) {
                            pendingPermissionRequest = request
                            requestPermissions(notGrantedPermissions.toTypedArray(), PERMISSIONS_REQUEST_CODE)
                        } else {
                            request.grant(permissions.toTypedArray())
                        }
                    } else {
                        request.grant(permissions.toTypedArray())
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                pendingPermissionRequest = null
                // Reset audio source when permissions are cancelled
                audioManager?.setParameters("audio_source_record=off")
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                showFullScreenCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideFullScreenCustomView()
            }
        }

        // Additional WebView settings for media support
        webView.settings.apply {
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            javaScriptEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
        }

        // Add JavaScript interface for custom media handling if needed
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMediaStart(type: String) {
                when (type) {
                    "audio" -> audioManager?.setParameters("audio_source_record=voiceassistant")
                    "video" -> { /* Handle camera initialization if needed */ }
                }
            }

            @JavascriptInterface
            fun onMediaStop() {
                audioManager?.setParameters("audio_source_record=off")
            }
        }, "AndroidMediaInterface")
    }

    private fun showFullScreenCustomView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (fullScreenCustomView != null) {
            callback?.onCustomViewHidden()
            return
        }

        fullScreenCustomView = view
        customViewCallback = callback
        originalSystemUiVisibility = window.decorView.systemUiVisibility
        originalOrientation = requestedOrientation

        // Listen for taps on the fullscreen content to exit fullscreen via back navigation
        var downX = 0f
        var downY = 0f
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val isTap = abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop
                    if (isTap) {
                        onBackPressedDispatcher.onBackPressed()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        dualWebViewGroup.showFullScreenOverlay(view)
        cursorLeftView.visibility = View.GONE
        cursorRightView.visibility = View.GONE
    }

    private fun hideFullScreenCustomView() {
        if (fullScreenCustomView == null) {
            return
        }
        dualWebViewGroup.hideFullScreenOverlay()
        fullScreenCustomView = null

        window.decorView.systemUiVisibility = originalSystemUiVisibility
        requestedOrientation = originalOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cursorLeftView.visibility = if (isCursorVisible) View.VISIBLE else View.GONE
        cursorRightView.visibility = if (isCursorVisible) View.VISIBLE else View.GONE

        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.extras?.get("data")?.let { imageBitmap ->
                // Convert bitmap to base64
                val base64Image = convertBitmapToBase64(imageBitmap as Bitmap)

                // Send the image back to Google Search
                webView.evaluateJavascript("""
                (function() {
                    // Find Google's image search input
                    var input = document.querySelector('input[type="file"][name="image_url"]');
                    if (!input) {
                        input = document.createElement('input');
                        input.type = 'file';
                        input.name = 'image_url';
                        document.body.appendChild(input);
                    }
                    
                    // Create a File object from base64
                    fetch('data:image/jpeg;base64,$base64Image')
                        .then(res => res.blob())
                        .then(blob => {
                            const file = new File([blob], "image.jpg", { type: 'image/jpeg' });
                            
                            // Create a FileList object
                            const dataTransfer = new DataTransfer();
                            dataTransfer.items.add(file);
                            
                            // Set the file and dispatch change event
                            input.files = dataTransfer.files;
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                        });
                })();
            """, null)
            }
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val imageBytes = outputStream.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.DEFAULT)
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsToRequest = mutableListOf<String>()

            // Check both permissions
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
            }

            if (permissionsToRequest.isNotEmpty()) {
                requestPermissions(permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
            }
        }
    }


    private fun logPermissionState() {
        val cameraPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        )
        val micPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        )

        Log.d("PermissionDebug", """
        Permission State:
        Camera: ${if (cameraPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
        Microphone: ${if (micPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
    """.trimIndent())
    }

    private fun disableDefaultKeyboard() {
        try {
            val method = WebView::class.java.getMethod("setShowSoftInputOnFocus", Boolean::class.java)
            method.invoke(webView, false)
        } catch (e: Exception) {
            // Fallback for older Android versions
            webView.evaluateJavascript("""
            document.addEventListener('focus', function(e) {
                if (e.target.tagName === 'INPUT' || 
                    e.target.tagName === 'TEXTAREA' || 
                    e.target.isContentEditable) {
                    window.Android.onInputFocus();
                    e.target.blur();
                    setTimeout(() => e.target.focus(), 50);
                }
            }, true);
        """, null)
        }
    }

    override fun onAnchorTogglePressed() {
        toggleAnchor()
    }


    // Add this method to handle permission results
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Initialize camera once we have permission
                    initializeCamera()
                } else {
                    Log.e("Camera", "Camera permission denied")
                    // Inform user that camera features won't work
                    webView.evaluateJavascript(
                        "alert('Camera permission is required for image search');",
                        null
                    )
                }
            }
        }
    }

    private fun loadInitialPage() {
        Log.d("WebViewDebug", "loadInitialPage called")
        // Load Google directly without the intermediate blank page
        webView.loadUrl("https://www.google.com")
    }

    fun showCustomKeyboard() {
        Log.d("KeyboardDebug", "1. Starting showCustomKeyboard")

        // Force WebView to lose focus
        webView.clearFocus()
        Log.d("KeyboardDebug", "2. WebView focus cleared")

        if (wasKeyboardDismissedByEnter) {
            wasKeyboardDismissedByEnter = false
            if(!dualWebViewGroup.isUrlEditing()) {
                return
            }
        }

        // Ensure keyboard view exists and is properly configured
        if (keyboardView == null) {
            Log.d("KeyboardDebug", "3. Creating new keyboard view")
            keyboardView = CustomKeyboardView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
                setOnKeyboardActionListener(this@MainActivity)
                Log.d("KeyboardDebug", "Keyboard created with visibility: $visibility")
            }
        }

        // Hide info bars when keyboard shows
        dualWebViewGroup.hideInfoBars()

        // Hide info bars when keyboard shows
        dualWebViewGroup.hideInfoBars()

        keyboardView?.let { keyboard ->
            // Log state before setting keyboard
            Log.d("KeyboardDebug", """
            Before setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent())

            // Force visibility BEFORE setting keyboard
            keyboard.visibility = View.VISIBLE
            dualWebViewGroup.keyboardContainer.visibility = View.VISIBLE

            dualWebViewGroup.setKeyboard(keyboard)

            // Log state after setting keyboard
            Log.d("KeyboardDebug", """
            After setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent())
        }

        isKeyboardVisible = true

        dualWebViewGroup.post {
            dualWebViewGroup.requestLayout()
            dualWebViewGroup.invalidate()
        }
    }

    private fun toggleAnchor() {




        isAnchored = !isAnchored
        hideCustomKeyboard()
        Log.d("AnchorDebug", """
        Anchor toggled:
        isAnchored: $isAnchored
        isKeyboardVisible: $isKeyboardVisible
        keyboardView null?: ${keyboardView == null}
    """.trimIndent())
        if (isAnchored) {
            // Move cursor to center of left screen
            centerCursor()

            // Initialize sensor handling
            sensorEventListener = createSensorEventListener()
            rotationSensor?.let { sensor ->
                sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
            dualWebViewGroup.startAnchoring()

        } else {
            // Stop sensor updates
            sensorManager.unregisterListener(sensorEventListener)

            // Restore cursor position
            refreshCursor()

            // Reset view positions
            dualWebViewGroup.stopAnchoring()
        }
    }

    private fun scheduleCursorUpdate() {
        if (!pendingCursorUpdate) {
            pendingCursorUpdate = true
            uiHandler.postDelayed({
                pendingCursorUpdate = false
                refreshCursor()
            }, 8)
        }
    }

    private fun createSensorEventListener(): SensorEventListener {
        return object : SensorEventListener {
            var initialQuaternion: FloatArray? = null
            override fun onSensorChanged(event: SensorEvent) {
                if (!isAnchored || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                val qx = event.values[0]
                val qy = event.values[1]
                val qz = event.values[2]
                val qw = event.values[3]
                val currentQuaternion = floatArrayOf(qw, qx, qy, qz)

                // Reset initial quaternion if requested
                if (shouldResetInitialQuaternion || initialQuaternion == null) {
                    initialQuaternion = currentQuaternion.clone()
                    shouldResetInitialQuaternion = false
                    return
                }

                val initialQuaternionInv = quaternionInverse(initialQuaternion!!)
                val relativeQuaternion = quaternionMultiply(initialQuaternionInv, currentQuaternion)

                val euler = quaternionToEuler(relativeQuaternion) // [roll, pitch, yaw]
                val rollRad = euler[2]  // or [2], etc., depends on your system
                val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()


                val deltaX = relativeQuaternion[1] * TRANSLATION_SCALE
                val deltaY = relativeQuaternion[2] * TRANSLATION_SCALE

                runOnUiThread {
                    dualWebViewGroup.updateLeftEyePosition(deltaX, deltaY, rollDeg)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    fun quaternionToEuler(q: FloatArray): FloatArray {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]

        // roll (x-axis rotation)
        val sinrCosp = 2f * (w*x + y*z)
        val cosrCosp = 1f - 2f * (x*x + y*y)
        val roll = atan2(sinrCosp, cosrCosp)

        // pitch (y-axis rotation)
        val sinp = 2f * (w*y - z*x)
        val pitch = if (abs(sinp) >= 1f) {
            // Use 90 degrees if out of range
            PI.toFloat()/2f * if (sinp > 0f) 1f else -1f
        } else {
            asin(sinp)
        }

        // yaw (z-axis rotation)
        val sinyCosp = 2f * (w*z + x*y)
        val cosyCosp = 1f - 2f * (y*y + z*z)
        val yaw = atan2(sinyCosp, cosyCosp)

        return floatArrayOf(roll, pitch, yaw)
    }


    /**
     * Toggles the cursor visibility.
     *
     * The optional [forceHide] and [forceShow] parameters make sure that callers can explicitly
     * request a desired state instead of relying on the current value. All state changes happen
     * within a synchronized block to avoid race conditions when rapid tap gestures or delayed
     * callbacks attempt to toggle the cursor simultaneously.
     */
    private fun toggleCursorVisibility(forceHide: Boolean = false, forceShow: Boolean = false) {
        Log.d("DoubleTapDebug", """
            Toggle Cursor Visibility:
            Force Hide: $forceHide
            Force Show: $forceShow
            Previous Visible: $isCursorVisible
            isSimulating: $isSimulatingTouchEvent
            cursorJustAppeared: $cursorJustAppeared
            isToggling: $isToggling
        """.trimIndent())

        synchronized(cursorToggleLock) {
            if (isToggling) return
            isToggling = true

            try {
                val previouslyVisible = isCursorVisible
                val targetVisibility = when {
                    forceHide -> false
                    forceShow -> true
                    else -> !previouslyVisible
                }

                if (targetVisibility == previouslyVisible) {
                    return
                }

                isCursorVisible = targetVisibility

                // Synchronise scroll mode with the cursor visibility state.
                dualWebViewGroup.setScrollMode(!isCursorVisible)

                if (isCursorVisible) {
                    resetRingReference()

                    if (!isAnchored) {
                        lastCursorX = lastKnownCursorX
                        lastCursorY = lastKnownCursorY
                    } else {
                        lastCursorX = 320f
                        lastCursorY = 240f
                    }

                    cursorJustAppeared = true
                    // Block interactions briefly to prevent stale taps from firing as the cursor reappears.
                    isSimulatingTouchEvent = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        cursorJustAppeared = false
                        isSimulatingTouchEvent = false
                    }, 300)

                    resetScrollModeTimer()
                } else {
                    lastKnownCursorX = lastCursorX
                    lastKnownCursorY = lastCursorY

                    val webViewLocation = IntArray(2)
                    webView.getLocationOnScreen(webViewLocation)
                    lastKnownWebViewX = lastCursorX - webViewLocation[0]
                    lastKnownWebViewY = lastCursorY - webViewLocation[1]

                    webView.evaluateJavascript("window.toggleTouchEvents(false);", null)
                    scrollModeHandler.removeCallbacks(scrollModeRunnable)
                }

                refreshCursor()
            } finally {
                isToggling = false
            }
        }
    }





    override fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean) {
        // Left screen cursor
        cursorLeftView.x = x % 640
        cursorLeftView.y = y
        cursorLeftView.visibility = if (isVisible) View.VISIBLE else View.GONE

        // Right screen cursor, offset by 640 pixels to appear on the right screen
        cursorRightView.x = (x % 640) + 640
        cursorRightView.y = y
        cursorRightView.visibility = if (isVisible) View.VISIBLE else View.GONE

        // Force layout and redraw for both cursors to ensure visibility
        cursorLeftView.requestLayout()
        cursorRightView.requestLayout()
        cursorLeftView.invalidate()
        cursorRightView.invalidate()
    }

    override fun onKeyPressed(key: String) {
        Log.d("LinkEditing", "onKeyPressed called with: $key")
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                // Handle bookmark menu navigation
                dualWebViewGroup.getBookmarksView().handleKeyboardInput(key)
            }
            editFieldVisible -> {
                // Handle any edit field input (URL or bookmark)
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart

                // Insert the key at cursor position
                val newText = StringBuilder(currentText)
                    .insert(cursorPosition, key)
                    .toString()

                // Set text and move cursor after inserted character
                dualWebViewGroup.setLinkText(newText, cursorPosition + 1)
            }
            else -> {
                sendCharacterToWebView(key)
            }
        }
    }

    private fun handleUserInteraction() {
        if (isCursorVisible && !isKeyboardVisible) {
            resetScrollModeTimer()
        }
    }

    override fun onBackspacePressed() {
        Log.d("LinkEditing", "onBackspacePressed called")
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                dualWebViewGroup.getBookmarksView().handleKeyboardInput("backspace")
            }
            editFieldVisible -> {
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart

                if (cursorPosition > 0) {
                    // Delete character before cursor
                    val newText = StringBuilder(currentText)
                        .deleteCharAt(cursorPosition - 1)
                        .toString()

                    // Set text and move cursor to position before deleted character
                    dualWebViewGroup.setLinkText(newText, cursorPosition - 1)
                }
            }
            else -> {
                sendBackspaceToWebView()
            }
        }
    }

    override fun onEnterPressed() {
        isKeyboardVisible = false //if enter is pressed keyboard is no longer visible
        if(isUrlEditing) {

            isUrlEditing = false
            dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)

        }

        wasKeyboardDismissedByEnter = true
        when {

            // If bookmarks are visible and being edited, handle bookmark updates
            dualWebViewGroup.isBookmarksExpanded() -> {
                dualWebViewGroup.getBookmarksView().onEnterPressed()
                hideCustomKeyboard()
            }
            // Otherwise handle regular keyboard input
            else -> {
                sendEnterToWebView()
            }
        }
    }

    override fun onHideKeyboard() {
        if (dualWebViewGroup.isBookmarkEditing()) {
            dualWebViewGroup.hideBookmarkEditing()
        }
        hideCustomKeyboard()
    }

    override fun onRefreshPressed() {
        //Log.d("Navigation", "Refresh pressed")
        val currentUrl = webView.url
        webView.evaluateJavascript("""
            (function() {
                const injectedStyles = document.querySelectorAll('style[data-injected="true"]');
                injectedStyles.forEach(style => style.remove());
                
                let viewport = document.querySelector('meta[name="viewport"]');
                if (viewport) {
                    viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0';
                }
            })();
        """, null)

        Handler(Looper.getMainLooper()).postDelayed({
            if (currentUrl != null) {
                webView.loadUrl(currentUrl)
            } else {
                webView.loadUrl("https://www.google.com")
            }
        }, 50)
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("RingInput", """
        Touch Event:
        Action: ${event.action}
        Source: ${event.source}
        Device: ${event.device?.name}
        ButtonState: ${event.buttonState}
        Pressure: ${event.pressure}
        Size: ${event.size}
        EventTime: ${event.eventTime}
        DownTime: ${event.downTime}
        Duration: ${event.eventTime - event.downTime}ms
    """.trimIndent())

        // Always let gestureDetector see the event
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            tripleClickMenu.stopFling()
        }

        // If gesture detector handled it, or if ring controls are active, consume the event
        return handled || shouldUseRingControls() || super.onTouchEvent(event)
    }

    // In the implementation of NavigationListener
    override fun onHomePressed() {
        //Log.d("Navigation", "Home pressed")
        val homeUrl = dualWebViewGroup.getBookmarksView().getHomeUrl()
        webView.loadUrl(homeUrl)
    }

    // In the implementation of NavigationListener
    override fun onRingPressed() {
        Log.d("Navigation", "Ring switch pressed")
        if (isRingConnected) {  // Only allow toggle if ring is connected
            isRingSwitchEnabled = !isRingSwitchEnabled
            // Update button icon through DualWebViewGroup
            dualWebViewGroup.leftNavigationBar.findViewById<ImageButton>(R.id.btnRingSwitch)?.setImageResource(
                if (isRingSwitchEnabled) R.drawable.ic_ring_enabled
                else R.drawable.ic_ring_disabled
            )
        }
    }

    // In the implementation of NavigationListener
    override fun onSettingsPressed() {
        Log.d("Navigation", "Settings pressed")
        dualWebViewGroup.showSettings()
    }




    // Add the navigation interface implementations
    override fun onNavigationBackPressed() {
        webView.copyBackForwardList().let { list ->
            Log.d("NavigationDebug", """
            Back pressed:
            Current URL: ${webView.url}
            Can go back: ${webView.canGoBack()}
            History size: ${list.size}
        """.trimIndent())

            if (list.size > 1) {
                val previousUrl = list.getItemAtIndex(list.size - 2).url
                Log.d("NavigationDebug", "Attempting to go back to: $previousUrl")

                // First, stop all JavaScript execution and ongoing loads
                webView.evaluateJavascript("window.stop();", null)
                webView.stopLoading()

                // Clear all JavaScript intervals and timeouts
                webView.evaluateJavascript("""
                (function() {
                    // Clear all intervals and timeouts
                    const highestId = window.setInterval(() => {}, 0);
                    for (let i = highestId; i >= 0; i--) {
                        window.clearInterval(i);
                        window.clearTimeout(i);
                    }
                    
                    // Clear onbeforeunload which some sites use to trap users
                    window.onbeforeunload = null;
                    
                    // Force clear any alert/confirm/prompt dialogs
                    window.alert = function(){};
                    window.confirm = function(){return true;};
                    window.prompt = function(){return '';};
                })();
            """, null)

                // Keep JavaScript enabled and go back
                webView.goBack()

                // After a short delay, verify we're at the right page
                Handler(Looper.getMainLooper()).postDelayed({
                    if (webView.url != previousUrl) {
                        webView.loadUrl(previousUrl)
                    }
                }, 100)

                webView.invalidate()
                dualWebViewGroup.invalidate()
            }
        }
    }

    override fun onQuitPressed() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        imageReader?.close()
        cameraThread.quitSafely()
        sensorManager.unregisterListener(sensorEventListener)
        mLauncher.removeOnResponseListener(ringResponseListener)
        RingIPCHelper.unRegisterRingInfo(this)
        mLauncher.disConnect()
    }


    override fun onStop() {
        super.onStop()

        // Get the current URL from the WebView
        val currentUrl = webView.url
        Log.d("WebViewDebug", "Saving current URL: $currentUrl")

        if (currentUrl != null && !currentUrl.startsWith("about:blank")) {
            // Save the URL to SharedPreferences
            getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putString(keyLastUrl, currentUrl)
                .apply()

            // Store it in our property for reference
            lastUrl = currentUrl

            // Save WebView state
            val webViewState = Bundle()
            webView.saveState(webViewState)

            try {
                val parcel = Parcel.obtain()
                webViewState.writeToParcel(parcel, 0)
                val serializedState = Base64.encodeToString(
                    parcel.marshall(),
                    Base64.DEFAULT
                )
                parcel.recycle()

                getSharedPreferences(prefsName, MODE_PRIVATE)
                    .edit {
                        putString("webview_state", serializedState)
                    }

                Log.d("WebViewDebug", "WebView state saved successfully")
            } catch (e: Exception) {
                Log.e("WebViewDebug", "Error saving WebView state", e)
            }
        }
    }

    // Add JavaScript interface to reset capturing state
    class AndroidInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun onCaptureComplete() {
            activity.runOnUiThread {
                activity.isCapturing = false
            }
        }
    }


}