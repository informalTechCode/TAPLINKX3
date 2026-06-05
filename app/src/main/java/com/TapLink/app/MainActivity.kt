package com.TapLinkX3.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
import android.view.Choreographer
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.TapLinkX3.app.controller.ControllerBluetoothClient
import com.TapLinkX3.app.controller.ControllerInputListener
import com.TapLinkX3.app.controller.ControllerMode
import com.TapLinkX3.app.controller.ControllerNetworkInputServer
import com.TapLinkX3.app.controller.ControllerTouchAction
import com.TapLinkX3.app.controller.ControllerTrackpadAction
import com.TapLinkX3.app.controller.GlassesCursorController
import com.ffalconxr.mercury.ipc.Launcher
import com.ffalconxr.mercury.ipc.helpers.GPSIPCHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraConfigurationUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import org.json.JSONObject

interface NavigationListener {
    fun onNavigationBackPressed()
    fun onNavigationForwardPressed()
    fun onQuitPressed()
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

private const val CONTROLLER_CURSOR_WIDTH = 640f
private const val CONTROLLER_CURSOR_HEIGHT = 480f

class MainActivity :
        AppCompatActivity(),
        DualWebViewGroup.DualWebViewGroupListener,
        NavigationListener,
        CustomKeyboardView.OnKeyboardActionListener,
        BookmarkListener,
        BookmarkKeyboardListener,
        LinkEditingListener,
        DualWebViewGroup.MaskToggleListener,
        DualWebViewGroup.AnchorToggleListener,
        DualWebViewGroup.WindowCallback,
        ControllerInputListener {

    private val messagesDesktopUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/148.0.7778.167 Safari/537.36"

    fun updateCursorSensitivity(progress: Int) {
        cursorSensitivity = progress
        // Map 0-100 to 0.0f - 0.9f gain. 50 -> 0.45f
        cursorGain = 0.9f * (progress / 100f)
    }

    private val H2V_GAIN = 1.0f // how strongly horizontal motion affects vertical scroll
    private val X_INVERT = -1.0f // 1 = left -> up (what you want). Use -1 to flip.
    private val Y_INVERT = -1.0f // 1 = drag up -> up. Use -1 to flip if needed.
    lateinit var dualWebViewGroup: DualWebViewGroup
    private val reusableLocation1 = IntArray(2)
    private lateinit var webView: WebView
    private lateinit var mainContainer: FrameLayout
    private lateinit var gestureDetector: GestureDetector
    private lateinit var templeDoubleTapDetector: GestureDetector
    private var isSimulatingTouchEvent = false
    private var isCursorVisible = true
    private var isMouseTapMode = false
    private var lastMouseRawX = Float.NaN
    private var lastMouseRawY = Float.NaN
    private var lastMouseMappedX = Float.NaN
    private var lastMouseMappedY = Float.NaN
    private var mouseGestureDownTime = 0L
    private var mouseGestureActive = false
    private var mouseSwipeTracking = false
    private var mouseSwipeStartedOnCustomUi = false
    private var mouseSwipeDownDispatched = false
    private var mouseSwipeStartX = 0f
    private var mouseSwipeStartY = 0f
    private var mouseSwipeLastX = 0f
    private var mouseSwipeLastY = 0f
    private var mouseSwipeDownTime = 0L
    private lateinit var controllerBluetoothClient: ControllerBluetoothClient
    private lateinit var controllerNetworkInputServer: ControllerNetworkInputServer
    private lateinit var cursorController: GlassesCursorController
    private var remoteControllerMode = ControllerMode.AIR_MOUSE
    private var controllerTouchDownTime = 0L
    private var isControllerConnected = false
    private var isPhoneKeyboardOpen = false
    private var metaModeEnabled = false
    private var isFirstModeCheck = true
    private var metaGestureStartX = 0f
    private var metaGestureStartY = 0f
    private var metaGestureLastX = 0f
    private var metaGestureLastY = 0f
    private var metaGestureTotalDistance = 0f
    private var savedCursorVisibleBeforeMetaMode: Boolean? = null
    private var glassAppsMetaToastShownForUrl: String? = null

    private fun refreshCursor() {
        if (isMetaNavigationActive()) {
            isCursorVisible = false
        }
        dualWebViewGroup.updateCursorPosition(lastCursorX, lastCursorY, isCursorVisible)
    }

    private fun refreshCursor(visible: Boolean) {
        isCursorVisible = visible
        refreshCursor()
    }

    private fun centerCursor(visible: Boolean = isCursorVisible) {
        lastCursorX = 320f
        lastCursorY = 240f
        refreshCursor(visible)
    }

    private fun isMousePointerEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        if (event.pointerCount <= 0) return false
        return event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun toggleMouseTapMode() {
        val enableMouseTapMode = !isMouseTapMode
        isMouseTapMode = enableMouseTapMode

        if (enableMouseTapMode) {
            cancelActiveTouchScrollGesture()
            if (::dualWebViewGroup.isInitialized && dualWebViewGroup.isInScrollMode()) {
                dualWebViewGroup.setScrollMode(false)
            }
            isCursorVisible = false
            cursorJustAppeared = false
            if (::cursorLeftView.isInitialized) {
                cursorLeftView.visibility = View.GONE
            }
            if (::cursorRightView.isInitialized) {
                cursorRightView.visibility = View.GONE
            }
            refreshCursor(false)
            dualWebViewGroup.showToast("Mouse tap mode enabled")
        } else {
            isCursorVisible = true
            refreshCursor(true)
            dualWebViewGroup.showToast("Cursor mode enabled")
        }
    }

    private fun ensureMouseTapModeEnabled() {
        if (isMouseTapMode) return
        toggleMouseTapMode()
    }

    private fun ensureMouseTapModeDisabled() {
        if (!isMouseTapMode) return
        toggleMouseTapMode()
    }

    private fun autoEnterMouseModeForMudraInput(event: MotionEvent) {
        val deviceName = event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name ?: return
        if (!deviceName.contains("Mudra", ignoreCase = true)) return
        ensureMouseTapModeEnabled()
    }
    private var isToggling = false
    private var lastCursorX = 320f
    private var lastCursorY = 240f
    private var isDispatchingTouchEvent = false
    private var isGestureHandled = false
    private var wasTouchOnBookmarks = false

    private val CAMERA_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_CODE = 100
    private val LOCATION_PERMISSION_REQUEST_CODE = 1002
    private var cameraPermissionGranted = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private val FILE_CHOOSER_REQUEST_CODE = 999 // Any unique code
    private var cameraImageUri: Uri? = null
    private var isCapturing = false // Add this flag to prevent multiple captures

    private var lastClickTime = 0L
    private val MIN_CLICK_INTERVAL = 500L // Minimum time between clicks

    // In MainActivity, add these properties to track cursor state and position
    private var lastKnownCursorX = 320f // Default center position
    private var lastKnownCursorY = 240f // Default center position
    private var lastKnownWebViewX = 0f
    private var lastKnownWebViewY = 0f
    private var cursorJustAppeared = false // Track if cursor just appeared

    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null

    private var currentVelocityX = 0f
    private var currentVelocityY = 0f
    private val movementDecay = 0.9f // Decay factor to slow down gradually
    private val updateInterval = 16L // Update interval in ms for smooth motion
    private val handler = Handler(Looper.getMainLooper())

    private val longPressTimeout = 200L // Milliseconds threshold for tap vs long press

    private lateinit var cursorLeftView: ImageView
    private lateinit var cursorRightView: ImageView
    private var lastRenderedCursorLeftX = Float.NaN
    private var lastRenderedCursorLeftY = Float.NaN
    private var lastRenderedCursorScale = Float.NaN
    private var lastRenderedCursorLeftVisibility = -1
    private var lastRenderedCursorRightVisibility = -1

    private var keyboardView: CustomKeyboardView? = null
    private var isKeyboardVisible = false
    private var wasKeyboardVisibleAtDown = false
    private var wasTouchOnKeyboard = false

    private val prefsName = Constants.BROWSER_PREFS_NAME
    private val keyLastUrl = Constants.KEY_LAST_URL
    private var lastUrl: String? = null
    private var isUrlEditing = false

    // User Agent management
    private var defaultUserAgent: String? = null
    private var customUserAgent: String? = null

    private var keyboardListener: DualWebViewGroup.KeyboardListener? = null

    private val PERMISSIONS_REQUEST_CODE = 123
    private var pendingPermissionRequest: PermissionRequest? = null
    private var qrScanCallbackWebView: WebView? = null
    private var isQrScanInProgress = false
    private var pendingNativeQrStart = false
    private var nativeQrScannerView: DecoratedBarcodeView? = null
    private val defaultQrZoomRatio = 3.0
    private var audioManager: AudioManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var fullScreenCustomView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var originalOrientation: Int = 0
    private var wasKeyboardDismissedByEnter = false
    private var suppressWebClickUntil = 0L

    private var preMaskCursorState = false
    private var preMaskCursorX = 0f
    private var preMaskCursorY = 0f
    private var closeChatOnNextPageStart = false
    private var closeChatOnNextPageStartDeadlineMs = 0L

    private var pendingCursorUpdate = false
    private var pendingActiveStateSave: Runnable? = null
    private var pendingActiveStateWebView: WebView? = null
    private var pendingActiveStateReason: String = "debounced"
    private val ACTIVE_STATE_SAVE_DEBOUNCE_MS = 750L
    private val pendingMessagesCompatibilityReloads =
            Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

    private val onBackPressedCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
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
    private var isAnchored = false // Will be loaded from preferences in onCreate
        set(value) {
            field = value
            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.isAnchored = value
            }
        }

    // Smoothing and performance parameters for anchored mode
    private val TRANSLATION_SCALE =
            2000f // Adjusted for better visual stability (approx 36 deg FOV)

    // Dynamic smoothing factors (controlled by user preference)
    // Range: 0 (fastest/least smooth) to 100 (slowest/most smooth)
    private var smoothnessLevel = 40 // Default: fairly smooth
    private var velocitySmoothing = 0.15f // Calculated from smoothnessLevel

    // Cursor sensitivity for non-anchored mode
    private var cursorSensitivity = 50 // Default 50 corresponds to 0.45f gain
    private var cursorGain = 0.45f

    // Velocity tracking for double exponential smoothing
    private var smoothedDeltaX = 0f
    private var smoothedDeltaY = 0f
    private var smoothedRollDeg = 0f
    private var lastAppliedAnchorX = Float.NaN
    private var lastAppliedAnchorY = Float.NaN
    private var lastAppliedAnchorRoll = Float.NaN
    private val ANCHOR_TRANSLATION_DEADBAND = 0.75f
    private val ANCHOR_ROTATION_DEADBAND_DEG = 0.08f

    // Frame timing for vsync
    private var lastFrameTime = 0L
    private val MIN_FRAME_INTERVAL_MS = 8L // ~120 FPS max (displays may be 90-120Hz)
    private var pendingAnchorFrame = false

    private var sensorEventListener = createSensorEventListener()
    private var shouldResetInitialQuaternion = false
    private var pendingDoubleTapAction = false
    private val ANCHOR_SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME

    private var ipcLauncher: Launcher? = null
    private var gpsUpdatesRegistered = false
    private val gpsHandler = Handler(Looper.getMainLooper())
    private var gpsStopRunnable: Runnable? = null
    private var lastGpsRequestAt = 0L
    private val GPS_IDLE_TIMEOUT_MS = 60000L

    private val doubleTapLock = Any()
    private var isProcessingDoubleTap = false
    private var lastDoubleTapStartTime = 0L
    private val DOUBLE_TAP_CONFIRMATION_DELAY = 200L

    // Triple tap detection for re-centering in anchored mode
    private var lastTapTime = 0L
    private var firstTapTime = 0L
    private var tapCount = 0
    private val TAP_INTERVAL = 400L // Max time between consecutive taps
    private val TRIPLE_TAP_DURATION = 800L // Max time for entire 3-tap sequence
    private var isTripleTapInProgress = false

    private var settingsMenu: View? = null

    private val gpsResponseListener =
            Launcher.OnResponseListener { response ->
                if (response?.data == null) return@OnResponseListener

                try {
                    val jo = JSONObject(response.data)
                    if (jo.has("mLatitude") && jo.has("mLongitude")) {
                        val mLatitude = jo.getDouble("mLatitude")
                        val mLongitude = jo.getDouble("mLongitude")

                        // Save for page reloads
                        lastGpsLat = mLatitude
                        lastGpsLon = mLongitude

                        // Inject location into WebView on UI thread
                        runOnUiThread { dualWebViewGroup.injectLocation(mLatitude, mLongitude) }
                    }
                } catch (e: Exception) {
                    DebugLog.e("GpsData", "Error processing GPS data: ${e.message}")
                }
            }

    private val cursorToggleLock = Any()
    private var potentialTapEvent: MotionEvent? = null

    private var pendingTouchHandler: Handler? = null
    private var pendingTouchRunnable: Runnable? = null

    // Touch scroll simulation state for mobile mode
    private var isTouchScrollActive = false
    private var touchScrollDownTime = 0L
    private var touchScrollCurrentY = 240f // Start at center
    private var accumulatedScrollY = 0f // Accumulate small scroll deltas

    private fun cancelActiveTouchScrollGesture() {
        pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
        pendingTouchRunnable = null
        accumulatedScrollY = 0f

        if (!isTouchScrollActive || !::webView.isInitialized) return

        val now = SystemClock.uptimeMillis()
        val cancelEvent =
                MotionEvent.obtain(
                        touchScrollDownTime,
                        now,
                        MotionEvent.ACTION_CANCEL,
                        lastCursorX,
                        touchScrollCurrentY,
                        0
                )
        cancelEvent.source = InputDevice.SOURCE_TOUCHSCREEN
        isSimulatingTouchEvent = true
        try {
            webView.dispatchTouchEvent(cancelEvent)
        } finally {
            isSimulatingTouchEvent = false
        }
        cancelEvent.recycle()
        isTouchScrollActive = false
    }

    private val notificationReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == NotificationService.ACTION_NOTIFICATION_POSTED) {
                        val packageName = intent.getStringExtra(NotificationService.EXTRA_PACKAGE)
                        val title = intent.getStringExtra(NotificationService.EXTRA_TITLE)
                        val text = intent.getStringExtra(NotificationService.EXTRA_TEXT)

                        DebugLog.d(
                                "MainActivity",
                                "Received notification from $packageName: $title - $text"
                        )

                        // Show a custom toast with the notification
                        dualWebViewGroup.showToast("Notification: $title - $text", 3000L)
                    }
                }
            }

    init {
        DebugLog.d("LinkEditing", "MainActivity initialized, isUrlEditing=$isUrlEditing")
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set window background to black immediately
        window.setBackgroundDrawableResource(android.R.color.black)

        // Set initial brightness to 10% to reduce power consumption
        window.attributes = window.attributes.apply { screenBrightness = 0.1f }

        // Force hardware acceleration but with black background
        window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Prevent any drawing until we're ready
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
        }

        // Set content view with black background
        setContentView(R.layout.activity_main)

        findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)

        mainContainer = findViewById(R.id.mainContainer)

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Add this to disable default keyboard
        window.setFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        // After basic window setup but before using any settings

        supportActionBar?.hide()

        keyboardListener =
                object : DualWebViewGroup.KeyboardListener {
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
        dualWebViewGroup.windowCallback = this
        dualWebViewGroup.restoreState()
        controllerNetworkInputServer = ControllerNetworkInputServer(this, this)
        controllerNetworkInputServer.start()
        controllerBluetoothClient = ControllerBluetoothClient(this, this)
        controllerBluetoothClient.start()

        // Load saved anchored mode state
        isAnchored =
                getSharedPreferences(prefsName, MODE_PRIVATE)
                        .getBoolean("isAnchored", false) // Default to false on first run

        dualWebViewGroup.isAnchored = isAnchored

        if (isAnchored) {
            dualWebViewGroup.startAnchoring()
        } else {
            dualWebViewGroup.stopAnchoring()
        }

        // Load cursor sensitivity
        cursorSensitivity =
                getSharedPreferences(prefsName, MODE_PRIVATE).getInt("cursorSensitivity", 50)
        updateCursorSensitivity(cursorSensitivity)
        metaModeEnabled = false
        applyMetaModeCursorVisibility()

        // Initialize GestureDetector
        gestureDetector =
                GestureDetector(
                        this,
                        object : SimpleOnGestureListener() {
                            private var isProcessingTap = false
                            private var totalScrollDistance = 0f
                            private var doubleTapRunnable: Runnable? = null

                            override fun onDown(e: MotionEvent): Boolean {
                                totalScrollDistance = 0f

                                // Store the down event for potential tap
                                potentialTapEvent = MotionEvent.obtain(e)

                                // Triple tap detection for screen re-centering in anchored mode
                                val currentTime = e.eventTime
                                if (currentTime - lastTapTime > TAP_INTERVAL) {
                                    DebugLog.d("TripleTapDebug", "Starting new tap sequence")
                                    tapCount = 1
                                    firstTapTime = currentTime
                                    isTripleTapInProgress = false
                                } else {
                                    tapCount++
                                    DebugLog.d(
                                            "TripleTapDebug",
                                            "Tap count increased to: $tapCount"
                                    )
                                }
                                lastTapTime = currentTime

                                // Check for triple tap
                                if (tapCount == 3 &&
                                                (currentTime - firstTapTime) <= TRIPLE_TAP_DURATION
                                ) {
                                    DebugLog.d(
                                            "TripleTapDebug",
                                            "Triple tap detected! Time from first tap: ${currentTime - firstTapTime}ms"
                                    )
                                    // Explicitly cancel the specific double tap runnable
                                    doubleTapRunnable?.let { handler.removeCallbacks(it) }
                                    doubleTapRunnable = null

                                    handler.removeCallbacksAndMessages(null)
                                    synchronized(doubleTapLock) { pendingDoubleTapAction = false }
                                    isTripleTapInProgress = true
                                    tapCount = 0

                                    if (isAnchored) {
                                        // Reset translations to center the view
                                        shouldResetInitialQuaternion = true
                                        dualWebViewGroup.updateLeftEyePosition(
                                                0f,
                                                0f,
                                                0f
                                        ) // Reset translations and rotation
                                        dualWebViewGroup.showToast("Screen Re-centered")
                                    } else {
                                        // Non-anchored triple tap: Toggle Scroll Mode
                                        if (dualWebViewGroup.isInScrollMode()) {
                                            toggleCursorVisibility(forceShow = true)
                                            dualWebViewGroup.showToast("Cursor mode activated")
                                        } else {
                                            toggleCursorVisibility(forceHide = true)
                                            dualWebViewGroup.showToast(
                                                    "Scroll mode activated, triple tap again to leave"
                                            )
                                        }
                                    }
                                    return true
                                }

                                return true
                            }

                            override fun onLongPress(e: MotionEvent) {
                                tapCount = 0
                            }

                            override fun onScroll(
                                    e1: MotionEvent?,
                                    e2: MotionEvent,
                                    distanceX: Float,
                                    distanceY: Float
                            ): Boolean {
                                if (metaModeEnabled) {
                                    tapCount = 0
                                    return true
                                }
                                tapCount = 0 // Reset tap count on scroll to prevent accidental
                                // triple-tap detection
                                totalScrollDistance +=
                                        kotlin.math.sqrt(
                                                distanceX * distanceX + distanceY * distanceY
                                        )

                                if (isAnchored && isCursorVisible) {
                                    // In anchored cursor mode, cursor movement should not become
                                    // a synthetic touch swipe on the page.
                                    cancelActiveTouchScrollGesture()
                                    return true
                                }

                                // When ANCHORED or SCROLL MODE: both X and Y move the page
                                // vertically
                                if ((!isCursorVisible &&
                                                (isAnchored ||
                                                        dualWebViewGroup.isInScrollMode())) &&
                                                !isKeyboardVisible &&
                                                !dualWebViewGroup.isScreenMasked()
                                ) {
                                    // Map horizontal to vertical: LEFT -> UP, RIGHT -> DOWN
                                    // GestureDetector gives incremental deltas since last callback
                                    val horizontalAsVertical = (-distanceX) * X_INVERT * H2V_GAIN
                                    val verticalFromDrag = distanceY * Y_INVERT

                                    val scale = dualWebViewGroup.uiScale
                                    val verticalDelta =
                                            (horizontalAsVertical + verticalFromDrag) / scale

                                    if (kotlin.math.abs(verticalDelta) >= 1f) {
                                        if (dualWebViewGroup.isDesktopMode()) {
                                            // Desktop mode: Use mouse scroll wheel simulation
                                            val pointerCoords = MotionEvent.PointerCoords()
                                            pointerCoords.x = 320f
                                            pointerCoords.y = 240f
                                            pointerCoords.setAxisValue(
                                                    MotionEvent.AXIS_VSCROLL,
                                                    verticalDelta / 30f
                                            )

                                            val pointerProperties = MotionEvent.PointerProperties()
                                            pointerProperties.id = 0
                                            pointerProperties.toolType = MotionEvent.TOOL_TYPE_MOUSE

                                            val event =
                                                    MotionEvent.obtain(
                                                            SystemClock.uptimeMillis(),
                                                            SystemClock.uptimeMillis(),
                                                            MotionEvent.ACTION_SCROLL,
                                                            1,
                                                            arrayOf(pointerProperties),
                                                            arrayOf(pointerCoords),
                                                            0,
                                                            0,
                                                            1.0f,
                                                            1.0f,
                                                            0,
                                                            0,
                                                            InputDevice.SOURCE_MOUSE,
                                                            0
                                                    )

                                            webView.dispatchGenericMotionEvent(event)
                                            event.recycle()
                                        } else {
                                            // Mobile mode: Use touch swipe simulation
                                            val now = SystemClock.uptimeMillis()

                                            // Accumulate scroll delta for smoother swiping
                                            accumulatedScrollY += verticalDelta

                                            if (!isTouchScrollActive) {
                                                // Prevent accidental clicks by requiring a minimum
                                                // movement threshold
                                                // before starting a swipe gesture. 15px is
                                                // typically safe for touch slop.
                                                if (kotlin.math.abs(accumulatedScrollY) < 15f) {
                                                    return true
                                                }

                                                // Start a new touch gesture at the last known
                                                // cursor position
                                                isTouchScrollActive = true
                                                touchScrollDownTime = now
                                                // Use last known cursor X/Y to ensure we scroll the
                                                // correct element
                                                touchScrollCurrentY = lastCursorY
                                                // accumulatedScrollY is valid and > threshold,
                                                // proceed using it

                                                val downEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_DOWN,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(downEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                downEvent.recycle()
                                            }

                                            // Apply scroll delta (Positive delta = Move DOWN = Drag
                                            // DOWN)
                                            // User wants: Swipe Forward (Up/Neg) -> Go Down (Scroll
                                            // Down)
                                            // Scroll Down -> Drag UP (Decrease Y)
                                            // So if Delta < 0, we want CurrentY to DECREASE.
                                            // So we ADD delta.
                                            var candidateY =
                                                    touchScrollCurrentY + accumulatedScrollY

                                            // Check bounds and loop gesture if needed to allow
                                            // continuous scrolling
                                            if (candidateY < 10f || candidateY > 470f) {
                                                // End current gesture with CANCEL to prevent acting
                                                // as a click at the edge
                                                val cancelEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_CANCEL,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                cancelEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(cancelEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                cancelEvent.recycle()

                                                // Reset to center to regain runway
                                                touchScrollCurrentY = 240f
                                                touchScrollDownTime = now

                                                // Start new gesture
                                                val downEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_DOWN,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(downEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                downEvent.recycle()

                                                // Re-calculate candidate
                                                candidateY =
                                                        touchScrollCurrentY + accumulatedScrollY
                                            }

                                            touchScrollCurrentY = candidateY.coerceIn(0f, 480f)
                                            accumulatedScrollY = 0f

                                            val moveEvent =
                                                    MotionEvent.obtain(
                                                            touchScrollDownTime,
                                                            now,
                                                            MotionEvent.ACTION_MOVE,
                                                            lastCursorX, // Keep X locked to gaze or
                                                            // original? Using gaze for
                                                            // now as per previous
                                                            touchScrollCurrentY,
                                                            0
                                                    )
                                            moveEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                            isSimulatingTouchEvent = true
                                            try {
                                                webView.dispatchTouchEvent(moveEvent)
                                            } finally {
                                                isSimulatingTouchEvent = false
                                            }
                                            moveEvent.recycle()

                                            // Schedule ACTION_CANCEL to complete the gesture if
                                            // scrolling stops. Using CANCEL prevents "clicks" on
                                            // lift.
                                            if (pendingTouchHandler == null) {
                                                pendingTouchHandler =
                                                        Handler(Looper.getMainLooper())
                                            }
                                            pendingTouchRunnable?.let {
                                                pendingTouchHandler?.removeCallbacks(it)
                                            }

                                            pendingTouchRunnable = Runnable {
                                                if (isTouchScrollActive) {
                                                    val upTime = SystemClock.uptimeMillis()
                                                    val cancelEvent =
                                                            MotionEvent.obtain(
                                                                    touchScrollDownTime,
                                                                    upTime,
                                                                    MotionEvent.ACTION_CANCEL,
                                                                    lastCursorX,
                                                                    touchScrollCurrentY,
                                                                    0
                                                            )
                                                    cancelEvent.source =
                                                            InputDevice.SOURCE_TOUCHSCREEN
                                                    isSimulatingTouchEvent = true
                                                    try {
                                                        webView.dispatchTouchEvent(cancelEvent)
                                                    } finally {
                                                        isSimulatingTouchEvent = false
                                                    }
                                                    cancelEvent.recycle()
                                                    isTouchScrollActive = false
                                                    DebugLog.d(
                                                            "ScrollMode",
                                                            "Touch scroll gesture cancelled via timeout"
                                                    )
                                                }
                                            }
                                            pendingTouchHandler?.postDelayed(
                                                    pendingTouchRunnable!!,
                                                    150
                                            )
                                        }
                                    }
                                    return true
                                }

                                // Not anchored: keep your existing cursor-follow logic
                                // val cursorGain = 0.45f // using class member cursorGain instead
                                val dx = -distanceX * cursorGain
                                val dy = -distanceY * cursorGain
                                if (!isAnchored && !dualWebViewGroup.isInScrollMode()) {
                                    // Clamp to single eye dimensions (640x480), not full dual
                                    // display width
                                    val maxW = 640f
                                    val maxH = 480f
                                    lastCursorX = (lastCursorX + dx).coerceIn(0f, maxW)
                                    lastCursorY = (lastCursorY + dy).coerceIn(0f, maxH)

                                    val loc = reusableLocation1
                                    webView.getLocationOnScreen(loc)
                                    lastKnownWebViewX = lastCursorX - loc[0]
                                    lastKnownWebViewY = lastCursorY - loc[1]
                                    refreshCursor(true)
                                    return true
                                }

                                return false
                            }
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                if (metaModeEnabled) {
                                    dispatchMetaKeyToWebView("Enter")
                                    return true
                                }
                                if (totalScrollDistance > 10f) {
                                    return false
                                }

                                handleUserInteraction()

                                // If the touch interaction started on the bookmarks view, consume
                                // the tap here
                                // to prevent it from propagating to the WebView (even if bookmarks
                                // closed in the meantime)
                                if (wasTouchOnBookmarks) {
                                    return true
                                }

                                // Don't handle single taps that are part of a triple tap sequence
                                if (isTripleTapInProgress) {
                                    isTripleTapInProgress = false
                                    return true
                                }

                                // When masked, don't consume the tap - let it reach the unmask
                                // button and media controls
                                // The mask overlay itself will block touches to web content
                                if (dualWebViewGroup.isScreenMasked()) {
                                    dispatchTouchEventAtCursor()
                                    return true
                                }

                                if (isProcessingTap) return true

                                isProcessingTap = true
                                Handler(Looper.getMainLooper())
                                        .postDelayed({ isProcessingTap = false }, 300)

                                // Bookmark taps are now handled exclusively by
                                // DualWebViewGroup.onTouchEvent
                                // to prevent double-dispatch issues with actions like "Set Home"

                                when {
                                    isToggling && cursorJustAppeared -> {
                                        return true
                                    }
                                    isCursorVisible -> {
                                        // Check if this is a long press
                                        if (e.eventTime - e.downTime > longPressTimeout) {
                                            return true
                                        }

                                        // Handle regular clicks when cursor is visible
                                        if (!cursorJustAppeared && !isSimulatingTouchEvent) {
                                            val UILocation = reusableLocation1
                                            dualWebViewGroup.leftEyeUIContainer.getLocationOnScreen(
                                                    UILocation
                                            )

                                            // Dispatch the touch event at the current cursor
                                            // position
                                            dispatchTouchEventAtCursor()
                                        }
                                    }
                                    else -> {
                                        // In scroll mode (cursor hidden), let taps pass through to
                                        // the WebView
                                        // User can exit scroll mode via the dedicated unhide button
                                        if (dualWebViewGroup.isInScrollMode()) {
                                            return false // Don't consume - let tap go to WebView
                                        }
                                        isSimulatingTouchEvent = true
                                        toggleCursorVisibility()
                                    }
                                }
                                return true
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                // Prevent double tap back navigation if keyboard is visible
                                if (isKeyboardVisible) {
                                    return true // Consume the event so it doesn't propagate
                                }

                                // If this is part of a triple tap sequence (which just toggled
                                // mode), ignore double tap
                                if (isTripleTapInProgress) {
                                    return true
                                }

                                val isInScrollMode = dualWebViewGroup.isInScrollMode()

                                if (isInScrollMode) {
                                    return true
                                }

                                synchronized(doubleTapLock) {
                                    // Safety check: Reset if flag has been stuck for too long
                                    // (>500ms)
                                    val currentTime = SystemClock.uptimeMillis()
                                    if (isProcessingDoubleTap &&
                                                    lastDoubleTapStartTime > 0 &&
                                                    currentTime - lastDoubleTapStartTime > 500
                                    ) {
                                        isProcessingDoubleTap = false
                                    }

                                    if (isProcessingDoubleTap) {
                                        return true
                                    }
                                    isProcessingDoubleTap = true
                                    lastDoubleTapStartTime = currentTime
                                    pendingDoubleTapAction = true

                                    // Calculate dynamic delay to ensure we wait until AFTER the
                                    // triple tap window closes
                                    val timeSinceFirstTap =
                                            SystemClock.uptimeMillis() - firstTapTime
                                    val remainingTripleTapWindow =
                                            TRIPLE_TAP_DURATION - timeSinceFirstTap

                                    // Make sure we wait at least a small buffer after the window
                                    // closes
                                    // But cap the delay to avoid excessive waiting if the window is
                                    // huge (though 800ms is reasonable)
                                    val delay =
                                            if (remainingTripleTapWindow > 0)
                                                    remainingTripleTapWindow + 30
                                            else DOUBLE_TAP_CONFIRMATION_DELAY

                                    doubleTapRunnable = Runnable {
                                        synchronized(doubleTapLock) {
                                            try {
                                                // Final check for triple tap
                                                if (isTripleTapInProgress) {
                                                    return@Runnable
                                                }

                                                if (pendingDoubleTapAction) {
                                                    performDoubleTapBackNavigation()
                                                }
                                            } finally {
                                                // Note: Do NOT reset tapCount/lastTapTime here
                                                pendingDoubleTapAction = false
                                                isProcessingDoubleTap = false
                                                lastDoubleTapStartTime = 0L
                                                doubleTapRunnable = null
                                            }
                                        }
                                    }

                                    handler.postDelayed(doubleTapRunnable!!, delay)
                                }

                                return true
                            }

                            private fun performDoubleTapBackNavigation() {
                                val hasHistory = webView.canGoBack()

                                if (!hasHistory) {
                                    return
                                }

                                onNavigationBackPressed()
                            }
                        }
                )

        templeDoubleTapDetector =
                GestureDetector(
                        this,
                        object : SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent): Boolean = true

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                toggleMouseTapMode()
                                return true
                            }
                        }
                )

        // Create and set up bookmarks view
        val bookmarksView =
                BookmarksView(this).apply {
                    setKeyboardListener(this@MainActivity) // Set keyboard listener directly
                    setBookmarkListener(
                            this@MainActivity
                    ) // Add this line to set the bookmark listener
                }

        // Set up bookmarks view in DualWebViewGroup
        dualWebViewGroup.setBookmarksView(bookmarksView)

        // Ensure settings and bookmarks are closed on app startup
        dualWebViewGroup.resetUiState()

        bookmarksView.setKeyboardListener(this)

        // Set up the keyboard listener
        dualWebViewGroup.keyboardListener =
                object : DualWebViewGroup.KeyboardListener {
                    override fun onShowKeyboard() {
                        showCustomKeyboard()
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard()
                    }
                }

        // Set up the mic listener for the chat view
        dualWebViewGroup.micListener =
                object : ChatView.MicListener {
                    override fun onMicrophonePressed() {
                        this@MainActivity.onMicrophonePressed()
                    }
                }

        // Cursor views setup
        // Set up the cursor views directly in the main container
        cursorLeftView =
                ImageView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(24, 24) // Adjust size as needed
                    setImageResource(R.drawable.cursor_arrow_image)
                    scaleType =
                            ImageView.ScaleType
                                    .FIT_START // Anchor to top-left for accurate click alignment
                    x = 320f
                    y = 240f
                    visibility = View.GONE
                }
        cursorRightView =
                ImageView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(24, 24)
                    setImageResource(R.drawable.cursor_arrow_image)
                    scaleType =
                            ImageView.ScaleType
                                    .FIT_START // Anchor to top-left for accurate click alignment
                    x = 960f
                    y = 240f
                    visibility = View.GONE
                }

        // Add cursor views to the main container
        mainContainer.apply {
            addView(cursorLeftView)
            addView(cursorRightView)
        }

        // 1. Get screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        cursorController =
                GlassesCursorController(screenWidth = screenWidth, screenHeight = screenHeight)
        cursorController.setOnUpdateListener { x, y, select ->
            lastCursorX = x
            lastCursorY = y
            lastKnownCursorX = x
            lastKnownCursorY = y
            refreshCursor(!isMetaNavigationActive())
            if (select && !isMetaNavigationActive()) {
                dispatchTouchEventAtCursor()
            }
            dualWebViewGroup.noteUserInteraction()
        }

        webView = dualWebViewGroup.getWebView()

        webView.setOnTouchListener { _, event ->
            val isMouseEvent = isMousePointerEvent(event)

            // Clear any pending touch events when a new touch starts
            // or when touch ends/cancels
            if (event.action == MotionEvent.ACTION_DOWN ||
                            event.action == MotionEvent.ACTION_UP ||
                            event.action == MotionEvent.ACTION_CANCEL
            ) {
                pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
                pendingTouchRunnable = null
            }

            if (isAnchored && isKeyboardVisible) {
                return@setOnTouchListener false
            }

            if (isSimulatingTouchEvent) {
                return@setOnTouchListener false
            }

            if (isMouseTapMode && isMouseEvent) {
                return@setOnTouchListener false
            }

            if (isKeyboardVisible) {
                return@setOnTouchListener true
            }

            // Use the cached result from dispatchTouchEvent instead of calling gestureDetector
            // again
            // This prevents double-processing which can corrupt gesture state
            val handled = isGestureHandled

            // Add check for settings menu visibility
            if (dualWebViewGroup.isSettingsVisible()) {
                return@setOnTouchListener isCursorVisible // Let the event propagate to the settings
                // menu
            }

            // In scroll mode, let taps pass through to WebView
            // The gesture detector still sees all events via dispatchTouchEvent,
            // so double-tap detection still works independently
            // In scroll mode, let taps pass through to WebView ONLY if anchored
            // If not anchored, we want the cursor to handle the event (move/wake)
            if (dualWebViewGroup.isInScrollMode() && isAnchored) {
                // Always return false to let taps reach the WebView (for clicking links, etc.)
                // The gesture detector has already processed the event in dispatchTouchEvent
                return@setOnTouchListener false
            }

            if (isCursorVisible && !isMouseEvent) {
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_UP && !handled) {}

            handled
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
        dualWebViewGroup.updateBrowsingMode(dualWebViewGroup.isDesktopMode())

        // Set up the listener
        dualWebViewGroup.linkEditingListener = this

        // Add after other listener assignments
        dualWebViewGroup.anchorToggleListener = this

        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Load preferences (use TapLinkPrefs for settings that are saved there)
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        smoothnessLevel = prefs.getInt(Constants.KEY_ANCHOR_SMOOTHNESS, 40)
        updateSmoothnessFactors(smoothnessLevel)

        // After initializing webView and dualWebViewGroup but before loadInitialPage()
        // Set initial cursor position
        // Make cursor visible
        cursorLeftView.visibility = View.VISIBLE
        cursorRightView.visibility = View.GONE
        centerCursor(true)

        // Start in saved anchored mode state
        if (isAnchored) {
            rotationSensor?.let { sensor ->
                sensorEventListener = createSensorEventListener()
                sensorManager.registerListener(sensorEventListener, sensor, ANCHOR_SENSOR_DELAY)
            }
            dualWebViewGroup.startAnchoring()
        } else {
            // Not anchored - make sure anchored mode is off
            dualWebViewGroup.stopAnchoring()
        }

        // Then try to restore the previous state
        setupWebView() // This will attempt to load the saved URL

        // Only clear cache/history if restoration failed
        if (webView.url == null || webView.url == "about:blank") {
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl(Constants.DEFAULT_URL)
        }
        // Initialize camera after WebView setup
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionGranted = true
        } else {
            // Request camera permission if we don't have it
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
        initializeSpeechRecognition() // Initialize speech recognition after WebView setup

        // Call permission check during setup
        checkAndRequestPermissions()

        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            DebugLog.e("Sensor", "No rotation vector sensor found")
        } else {
            DebugLog.d("Sensor", "Rotation vector sensor found")
        }
    }

    // Add method to handle hyperlink button press
    override fun onHyperlinkPressed() {
        dualWebViewGroup.showLinkEditing()
    }

    override fun onNavigationForwardPressed() {
        if (webView.canGoForward()) {
            webView.goForward()
        }
    }

    override fun onPause() {
        if (::cursorController.isInitialized) {
            cursorController.stop()
        }
        super.onPause()

        if (nativeQrScannerView != null || isQrScanInProgress) {
            isQrScanInProgress = false
            pendingNativeQrStart = false
            stopNativeQrScannerOverlay()
        }

        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        sensorManager.unregisterListener(sensorEventListener)
        stopGpsUpdates()

        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        // Save pending state before the app backgrounds.
        flushPendingActiveWebViewState("onPause")
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.flushPendingWindowsStateSave(force = true)
        }
    }

    override fun onResume() {
        if (::cursorController.isInitialized) {
            cursorController.start()
        }
        super.onResume()

        // Register notification receiver
        val filter = IntentFilter(NotificationService.ACTION_NOTIFICATION_POSTED)
        ContextCompat.registerReceiver(
                this,
                notificationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Restart mirroring to right eye
        dualWebViewGroup.startRefreshing()

        if (isAnchored) {
            // Re-register the sensor listener
            rotationSensor?.let { sensor ->
                sensorManager.registerListener(sensorEventListener, sensor, ANCHOR_SENSOR_DELAY)
            }
        }

        // Re-initialize camera if permission was previously granted
        if (cameraPermissionGranted) {
            initializeCamera()
        }
    }

    fun getLastLocation(): Pair<Double, Double>? {
        return if (lastGpsLat != null && lastGpsLon != null) {
            Pair(lastGpsLat!!, lastGpsLon!!)
        } else {
            null
        }
    }

    private fun ensureGpsUpdates() {
        if (gpsUpdatesRegistered) return

        if (ipcLauncher == null) {
            ipcLauncher = Launcher.getInstance(this)
        }
        ipcLauncher?.addOnResponseListener(gpsResponseListener)
        GPSIPCHelper.registerGPSInfo(this)
        gpsUpdatesRegistered = true
    }

    private fun noteGeolocationUse() {
        lastGpsRequestAt = SystemClock.elapsedRealtime()
        ensureGpsUpdates()

        gpsStopRunnable?.let { gpsHandler.removeCallbacks(it) }
        gpsStopRunnable =
                object : Runnable {
                    override fun run() {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastGpsRequestAt >= GPS_IDLE_TIMEOUT_MS) {
                            stopGpsUpdates()
                        } else {
                            gpsHandler.postDelayed(this, GPS_IDLE_TIMEOUT_MS)
                        }
                    }
                }
        gpsHandler.postDelayed(gpsStopRunnable!!, GPS_IDLE_TIMEOUT_MS)
    }

    private fun stopGpsUpdates() {
        if (!gpsUpdatesRegistered) return

        GPSIPCHelper.unRegisterGPSInfo(this)
        ipcLauncher?.removeOnResponseListener(gpsResponseListener)
        ipcLauncher?.disConnect()
        ipcLauncher = null
        gpsUpdatesRegistered = false
        gpsStopRunnable?.let { gpsHandler.removeCallbacks(it) }
        gpsStopRunnable = null
    }

    override fun getCurrentUrl(): String {
        return dualWebViewGroup.getWebView().url ?: Constants.DEFAULT_URL
    }

    fun openUrlInNewTab(url: String) {
        if (!::dualWebViewGroup.isInitialized) return
        val formattedUrl = formatUrl(url)
        val newWebView = dualWebViewGroup.createNewWindow()
        if (dualWebViewGroup.isChatVisible()) {
            closeChatOnNextPageStart = true
            closeChatOnNextPageStartDeadlineMs = SystemClock.uptimeMillis() + 5000L
        }
        newWebView.loadUrl(formattedUrl)
    }

    fun getActiveWebViewUrlOrNull(): String? {
        if (!::dualWebViewGroup.isInitialized) return null
        return dualWebViewGroup.getWebView().url
    }

    fun getActiveWebViewOrNull(): WebView? {
        if (!::dualWebViewGroup.isInitialized) return null
        return dualWebViewGroup.getWebView()
    }

    override fun onBookmarkSelected(url: String) {
        val formattedUrl =
                when {
                    // Check for file: protocol specifically
                    url.startsWith("file:", ignoreCase = true) -> url
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.contains(".") -> "https://$url"
                    else -> "https://www.google.com/search?q=${Uri.encode(url)}"
                }
        webView.loadUrl(formattedUrl)
    }

    private fun handleMaskToggle() {
        if (dualWebViewGroup.isScreenMasked()) {
            dualWebViewGroup.unmaskScreen()
            isCursorVisible = preMaskCursorState
            lastCursorX = preMaskCursorX
            lastCursorY = preMaskCursorY
            if (isCursorVisible) {
                cursorLeftView.visibility = View.VISIBLE
                cursorRightView.visibility = View.VISIBLE
                refreshCursor(true)
            }
            return
        }
        // Close settings menu if open to prevent state desync
        dualWebViewGroup.hideSettings()

        // de-anchor when masking to avoid issues
        if (isAnchored) {
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

    override fun onSendCharacterToLink(character: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            val newText = StringBuilder(currentText).insert(currentPosition, character).toString()

            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(currentPosition + 1)
        }
    }

    override fun onSendBackspaceInLink() {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            if (currentPosition > 0) {
                // Delete character before cursor position
                val newText =
                        StringBuilder(currentText).deleteCharAt(currentPosition - 1).toString()

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
    }

    // Helper function for quaternion multiplication
    fun quaternionMultiply(q1: FloatArray, q2: FloatArray, out: FloatArray): FloatArray {
        val w = q1[0] * q2[0] - q1[1] * q2[1] - q1[2] * q2[2] - q1[3] * q2[3]
        val x = q1[0] * q2[1] + q1[1] * q2[0] + q1[2] * q2[3] - q1[3] * q2[2]
        val y = q1[0] * q2[2] - q1[1] * q2[3] + q1[2] * q2[0] + q1[3] * q2[1]
        val z = q1[0] * q2[3] + q1[1] * q2[2] - q1[2] * q2[1] + q1[3] * q2[0]
        out[0] = w
        out[1] = x
        out[2] = y
        out[3] = z
        return out
    }

    // Helper function for quaternion inversion
    fun quaternionInverse(q: FloatArray, out: FloatArray): FloatArray {
        val magnitudeSquared = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]
        if (magnitudeSquared == 0f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            out[3] = 0f
            return out
        }
        val invMagnitude = 1f / magnitudeSquared
        out[0] = q[0] * invMagnitude
        out[1] = -q[1] * invMagnitude
        out[2] = -q[2] * invMagnitude
        out[3] = -q[3] * invMagnitude
        return out
    }

    private fun ensureCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun initializeCamera() {
        if (imageReader != null) return

        ensureCameraThread()
        val handler = cameraHandler ?: Handler(Looper.getMainLooper())

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Set up ImageReader for capturing photos
            imageReader =
                    ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 2)
                            .apply {
                                setOnImageAvailableListener(
                                        { reader ->
                                            // When an image is captured
                                            val image = reader.acquireLatestImage()
                                            try {
                                                // Convert image to base64 for web upload
                                                val buffer = image.planes[0].buffer
                                                val bytes = ByteArray(buffer.capacity())
                                                buffer.get(bytes)
                                                val base64Image =
                                                        Base64.encodeToString(bytes, Base64.DEFAULT)

                                                // Send image back to Google's image search
                                                runOnUiThread {
                                                    webView.evaluateJavascript(
                                                            """
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
                        """.trimIndent(),
                                                            null
                                                    )
                                                }
                                            } finally {
                                                image.close()
                                            }
                                        },
                                        handler
                                )
                            }
        } catch (e: Exception) {
            DebugLog.e("Camera", "Failed to initialize camera system", e)
            runOnUiThread {
                webView.evaluateJavascript("alert('Failed to initialize camera system.');", null)
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
        DebugLog.d("MainActivity", "onShowKeyboardForEdit called with text: $text")

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

    override fun onShowLinkEditing() {
        DebugLog.d("LinkEditing", "onShowLinkEditing called")
        isUrlEditing = true // Make sure this state is set
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        showCustomKeyboard()
    }

    override fun onHideLinkEditing() {
        DebugLog.d("LinkEditing", "onHideLinkEditing called")
        isUrlEditing = false
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        DebugLog.d("LinkEditing", "isUrlEditing set to false")
        hideCustomKeyboard()
    }

    private fun sendCharacterToLinkEditText(character: String) {
        DebugLog.d("LinkEditing", "sendCharacterToLinkEditText called with: $character")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            val newText = StringBuilder(currentText).insert(cursorPosition, character).toString()
            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition + 1)
        }
    }

    private fun sendBackspaceInLinkEditText() {
        DebugLog.d("LinkEditing", "sendBackspaceInLinkEditText called")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            if (cursorPosition > 0) {
                val newText = StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()
                dualWebViewGroup.setLinkText(newText)
                dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition - 1)
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
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") -> url
            url.contains(".") -> "https://$url"
            else -> "https://www.google.com/search?q=${Uri.encode(url)}"
        }
    }

    private fun isStreamingSite(url: String?): Boolean {
        if (url == null) return false
        val streamingDomains =
                listOf(
                        "netflix.com",
                        "disneyplus.com",
                        "hulu.com",
                        "primevideo.com",
                        "amazon.com/gp/video",
                        "max.com",
                        "peacocktv.com",
                        "apple.com/tv",
                        "tv.apple.com",
                        "tubitv.com",
                        "pluto.tv",
                        "paramountplus.com",
                        "discoveryplus.com"
                )
        return streamingDomains.any { url.contains(it, ignoreCase = true) }
    }

    private fun isDesktopBrowserRequiredSite(url: String?): Boolean {
        if (url == null) return false
        val host =
                try {
                    Uri.parse(url).host?.lowercase()
                } catch (_: Exception) {
                    null
                }
        return host == "messages.google.com"
    }

    private fun isGlassAppsSite(url: String?): Boolean {
        if (url == null) return false
        val host =
                try {
                    Uri.parse(url).host?.lowercase()
                } catch (_: Exception) {
                    null
                }
        return host == "glassapps.io" || host?.endsWith(".glassapps.io") == true
    }

    private fun applyUserAgentForUrl(view: WebView?, url: String?) {
        view ?: return
        val targetUserAgent =
                when {
                    isStreamingSite(url) -> defaultUserAgent
                    isDesktopBrowserRequiredSite(url) -> messagesDesktopUserAgent
                    isGlassAppsSite(url) -> customUserAgent
                    dualWebViewGroup.isDesktopMode() -> dualWebViewGroup.getDesktopUserAgent()
                    else -> customUserAgent
                }

        if (!targetUserAgent.isNullOrBlank() && view.settings.userAgentString != targetUserAgent) {
            view.settings.userAgentString = targetUserAgent
            DebugLog.d(
                    "UserAgent",
                    "Applied ${if (isDesktopBrowserRequiredSite(url)) "desktop-required" else "site"} User Agent for $url"
            )
        }
    }

    private fun markMessagesCompatibilityReloadNeeded(view: WebView) {
        pendingMessagesCompatibilityReloads.add(view)
    }

    private fun maybeRunMessagesCompatibilityReload(view: WebView?, url: String?) {
        if (view == null || !isDesktopBrowserRequiredSite(url)) return
        if (!pendingMessagesCompatibilityReloads.remove(view)) return

        view.postDelayed(
                {
                    if (!isDesktopBrowserRequiredSite(view.url)) return@postDelayed
                    dualWebViewGroup.toggleBrowsingMode()
                },
                250L
        )
    }

    private fun initializeSpeechRecognition() {
        // Check if speech recognition is available
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        DebugLog.d("SpeechRecognition", "Recognition available: $isAvailable")

        if (!isAvailable) {
            DebugLog.w("SpeechRecognition", "Speech recognition not available on this device")
            return
        }

        try {
            speechRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(
                                object : RecognitionListener {
                                    override fun onResults(results: Bundle?) {
                                        isListeningForSpeech = false
                                        results?.getStringArrayList(
                                                        SpeechRecognizer.RESULTS_RECOGNITION
                                                )
                                                ?.let { matches ->
                                                    if (matches.isNotEmpty()) {
                                                        val text = matches[0]
                                                        runOnUiThread {
                                                            onShowKeyboardForEdit(text)
                                                            // Handle inserting text based on what
                                                            // input is focused
                                                            val editFieldVisible =
                                                                    dualWebViewGroup
                                                                            .urlEditText
                                                                            .visibility ==
                                                                            View.VISIBLE

                                                            when {
                                                                dualWebViewGroup
                                                                        .isBookmarksExpanded() &&
                                                                        !editFieldVisible -> {
                                                                    // Handle bookmark menu
                                                                    // navigation - maybe search
                                                                    // bookmarks?
                                                                    // For now just toast or ignore
                                                                }
                                                                editFieldVisible -> {
                                                                    // Handle any edit field input
                                                                    // (URL or bookmark)
                                                                    val currentText =
                                                                            dualWebViewGroup
                                                                                    .getCurrentLinkText()
                                                                    val cursorPosition =
                                                                            dualWebViewGroup
                                                                                    .urlEditText
                                                                                    .selectionStart
                                                                    // Insert the text at cursor
                                                                    // position
                                                                    val newText =
                                                                            StringBuilder(
                                                                                            currentText
                                                                                    )
                                                                                    .insert(
                                                                                            cursorPosition,
                                                                                            text
                                                                                    )
                                                                                    .toString()

                                                                    // Set text and move cursor
                                                                    // after inserted text
                                                                    dualWebViewGroup.setLinkText(
                                                                            newText,
                                                                            cursorPosition +
                                                                                    text.length
                                                                    )
                                                                }
                                                                dualWebViewGroup.getDialogInput() !=
                                                                        null -> {
                                                                    val input =
                                                                            dualWebViewGroup
                                                                                    .getDialogInput()!!
                                                                    val currentText =
                                                                            input.text.toString()
                                                                    val cursorPosition =
                                                                            input.selectionStart
                                                                    val newText =
                                                                            StringBuilder(
                                                                                            currentText
                                                                                    )
                                                                                    .insert(
                                                                                            cursorPosition,
                                                                                            text
                                                                                    )
                                                                                    .toString()
                                                                    input.setText(newText)
                                                                    input.setSelection(
                                                                            cursorPosition +
                                                                                    text.length
                                                                    )
                                                                }
                                                                else -> {
                                                                    sendTextToWebView(text)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                    }

                                    // Implement other RecognitionListener methods with empty bodies
                                    override fun onReadyForSpeech(params: Bundle?) {
                                        DebugLog.d("SpeechRecognition", "Ready for speech")
                                        dualWebViewGroup.showToast("Listening...")
                                    }
                                    override fun onBeginningOfSpeech() {
                                        DebugLog.d("SpeechRecognition", "Speech started")
                                    }
                                    override fun onRmsChanged(rmsdB: Float) {}
                                    override fun onBufferReceived(buffer: ByteArray?) {}
                                    override fun onEndOfSpeech() {
                                        DebugLog.d("SpeechRecognition", "Speech ended")
                                        isListeningForSpeech = false
                                    }
                                    override fun onError(error: Int) {
                                        isListeningForSpeech = false
                                        val errorMsg =
                                                when (error) {
                                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                                            "Network timeout"
                                                    SpeechRecognizer.ERROR_NETWORK ->
                                                            "Network error"
                                                    SpeechRecognizer.ERROR_AUDIO ->
                                                            "Audio recording error"
                                                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                                                    SpeechRecognizer.ERROR_CLIENT ->
                                                            "Speech service unavailable"
                                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                                            "No speech detected"
                                                    SpeechRecognizer.ERROR_NO_MATCH ->
                                                            "No match found"
                                                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                                            "Recognizer busy"
                                                    SpeechRecognizer
                                                            .ERROR_INSUFFICIENT_PERMISSIONS ->
                                                            "Permission denied"
                                                    else -> "Error: $error"
                                                }
                                        DebugLog.e("SpeechRecognition", "Error: $error ($errorMsg)")
                                        dualWebViewGroup.showToast(errorMsg)
                                    }
                                    override fun onPartialResults(partialResults: Bundle?) {}
                                    override fun onEvent(eventType: Int, params: Bundle?) {}
                                }
                        )
                    }
            DebugLog.d("SpeechRecognition", "SpeechRecognizer created successfully")
        } catch (e: Exception) {
            DebugLog.e("SpeechRecognition", "Failed to create SpeechRecognizer", e)
            speechRecognizer = null
        }
    }

    fun hideCustomKeyboard() {
        DebugLog.d("KeyboardDebug", "Hiding keyboard")

        // First blur any focused element
        webView.evaluateJavascript(
                """
       (function() {
           const activeElement = document.activeElement;
           if (activeElement && activeElement !== document.body) {
               activeElement.blur();
               // For React/custom components that might need extra cleanup
               const event = new Event('blur', { bubbles: true });
               activeElement.dispatchEvent(event);
           }
       })();
       """,
                null
        )

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

        // Instruct DualWebViewGroup to hide the link field
        dualWebViewGroup.hideLinkEditing()

        // Clean up input state
        webView.evaluateJavascript(
                """
        (function() {
            var activeElement = document.activeElement;
            if (activeElement) {
                activeElement.blur();
            }
        })();
    """,
                null
        )

        // Notify DualWebViewGroup about keyboard being hidden
        dualWebViewGroup.onKeyboardHidden()
        if (isPhoneKeyboardOpen && ::controllerBluetoothClient.isInitialized) {
            isPhoneKeyboardOpen = false
            controllerBluetoothClient.sendKeyboardVisibility(false)
        }

        // Restore original webView state
        webView.translationY = 0f

        // Clear any existing animations
        webView.clearAnimation()

        // Force layout update
        webView.requestLayout()
        webView.parent?.requestLayout()

        // Show cursor if not in URL editing mode

        isUrlEditing = false

        dualWebViewGroup.post { dualWebViewGroup.updateScrollBarsVisibility() }

        dualWebViewGroup.cleanupResources()
    }

    override fun onClearPressed() {
        when {
            dualWebViewGroup.isBookmarksExpanded() &&
                    dualWebViewGroup.urlEditText.visibility != View.VISIBLE -> {
                // Handle bookmark menu navigation
                dualWebViewGroup.getBookmarksView().handleKeyboardInput("clear")
            }
            dualWebViewGroup.urlEditText.visibility == View.VISIBLE -> {
                // Clear edit field for both URL and bookmark editing
                dualWebViewGroup.setLinkText("")
            }
            dualWebViewGroup.getDialogInput() != null -> {
                dualWebViewGroup.getDialogInput()?.setText("")
            }
            else -> {
                // Preserve existing JavaScript functionality for web content
                runOnUiThread {
                    webView.evaluateJavascript(
                            """
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
    """,
                            null
                    )
                }
            }
        }
    }

    override fun onMoveCursorLeft() {
        runOnUiThread { moveCursor(-1) }
    }

    override fun onMoveCursorRight() {
        runOnUiThread { moveCursor(1) }
    }

    private var isListeningForSpeech = false
    private var groqAudioService: GroqAudioService? = null
    private var lastMicPressTime = 0L

    private fun setVoiceAssistantAudioRoute(enabled: Boolean) {
        val value = if (enabled) "voiceassistant" else "off"
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0
            )
            audioManager?.setParameters("audio_source_record=$value")
            DebugLog.d("AudioRoute", "audio_source_record=$value")
        } catch (e: Exception) {
            DebugLog.e("AudioRoute", "Failed to set audio route: $value", e)
        }
    }

    fun prepareAudioForTtsPlayback() {
        runOnUiThread { setVoiceAssistantAudioRoute(true) }
    }

    override fun onMicrophonePressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMicPressTime < 500) {
            DebugLog.d("SpeechRecognition", "Ignoring rapid microphone press")
            return
        }
        lastMicPressTime = currentTime

        DebugLog.d(
                "SpeechRecognition",
                "onMicrophonePressed called, isListening: $isListeningForSpeech"
        )
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                DebugLog.d("SpeechRecognition", "Requesting audio permission")
                requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSIONS_REQUEST_CODE
                )
                return@runOnUiThread
            }

            if (groqAudioService == null) {
                initializeGroqService()
            }

            if (!groqAudioService!!.hasApiKey()) {
                showGroqKeyDialog()
                return@runOnUiThread
            }

            if (groqAudioService!!.isRecording()) {
                // Stop listening
                DebugLog.d("SpeechRecognition", "Stopping Groq recording")
                groqAudioService?.stopRecording()
                setVoiceAssistantAudioRoute(false)
                dualWebViewGroup.showToast("Processing...")
            } else {
                // Start listening
                DebugLog.d("SpeechRecognition", "Starting Groq recording")
                setVoiceAssistantAudioRoute(true)
                groqAudioService?.startRecording()
                dualWebViewGroup.showToast("Listening...")
            }
        }
    }

    fun showGroqKeyDialog() {
        if (groqAudioService == null) {
            initializeGroqService()
        }
        val currentKey = groqAudioService?.getApiKey()
        dualWebViewGroup.showPromptDialog(
                "Enter Groq API Key",
                currentKey,
                { key ->
                    groqAudioService?.setApiKey(key)
                    dualWebViewGroup.showToast("API Key Saved")
                    hideCustomKeyboard()
                },
                { dualWebViewGroup.showToast("API Key Required for Voice") }
        )
    }

    private fun initializeGroqService() {
        groqAudioService =
                GroqAudioService(this).apply {
                    setListener(
                            object : GroqAudioService.TranscriptionListener {
                                override fun onTranscriptionResult(text: String) {
                                    DebugLog.d("SpeechRecognition", "Groq result: $text")
                                    runOnUiThread {
                                        // Restore playback route after recording so chat TTS is
                                        // audible.
                                        setVoiceAssistantAudioRoute(true)
                                        // If chat is visible, insert text there
                                        if (dualWebViewGroup.isChatVisible()) {
                                            dualWebViewGroup.insertVoiceToChatInput(text)
                                        } else {
                                            handleVoiceResult(text)
                                        }
                                        dualWebViewGroup.showToast("Success")
                                        keyboardView?.setMicActive(false)
                                        dualWebViewGroup.setChatMicActive(false)
                                    }
                                }

                                override fun onError(message: String) {
                                    DebugLog.e("SpeechRecognition", "Groq error: $message")
                                    runOnUiThread {
                                        setVoiceAssistantAudioRoute(false)
                                        dualWebViewGroup.showToast("Voice Error: $message")
                                        keyboardView?.setMicActive(false)
                                        dualWebViewGroup.setChatMicActive(false)
                                        if (message.contains("No API Key")) {
                                            showGroqKeyDialog()
                                        }
                                    }
                                }

                                override fun onRecordingStart() {
                                    DebugLog.d("SpeechRecognition", "Groq recording started")
                                    runOnUiThread {
                                        isListeningForSpeech = true
                                        keyboardView?.setMicActive(true)
                                        dualWebViewGroup.setChatMicActive(true)
                                    }
                                }

                                override fun onRecordingStop() {
                                    DebugLog.d("SpeechRecognition", "Groq recording stopped")
                                    runOnUiThread {
                                        isListeningForSpeech = false
                                        // Don't turn off mic indicator yet, wait for processing
                                        // result
                                    }
                                }
                            }
                    )
                }
    }

    private fun handleVoiceResult(text: String) {
        if (text.isBlank()) return

        onShowKeyboardForEdit(text)
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                // Handle bookmark menu navigation - maybe search bookmarks?
                // For now just ignore
            }
            editFieldVisible -> {
                // Handle URL/bookmark edit field input
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart.coerceAtLeast(0)
                val newText = StringBuilder(currentText).insert(cursorPosition, text).toString()
                dualWebViewGroup.setLinkText(newText, cursorPosition + text.length)
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart.coerceAtLeast(0)
                val newText = StringBuilder(currentText).insert(cursorPosition, text).toString()
                input.setText(newText)
                input.setSelection((cursorPosition + text.length).coerceAtMost(newText.length))
            }
            else -> {
                sendTextToWebView(text)
            }
        }

        isListeningForSpeech = false
    }

    private fun moveCursor(offset: Int) {
        val focusedView = currentFocus
        if (focusedView != null) {
            val inputConnection = BaseInputConnection(focusedView, true)
            val now = SystemClock.uptimeMillis()
            val keyCode =
                    if (offset < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val keyEventDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val keyEventUp = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            inputConnection.sendKeyEvent(keyEventDown)
            inputConnection.sendKeyEvent(keyEventUp)
        } else {
            DebugLog.d("MainActivity", "No focused view to move cursor")
        }
    }

    fun injectJavaScriptForInputFocus() {
        webView.evaluateJavascript(
                """
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
    """,
                null
        )
    }

    private fun sendCharacterToWebView(character: String) {
        sendTextToWebView(character)
    }

    private fun sendTextToWebView(text: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            // For now only supports single character for link editing in current impl,
            // but we can loop if needed or assume sendCharacterToLinkEditText works for chars.
            // But this method is generic.
            // If text is longer than 1 char, we should handle it.
            // The existing sendCharacterToLinkEditText handles single char.
            // Let's iterate if it's multiple chars or just insert if we make a
            // sendTextToLinkEditText
            text.forEach { char -> sendCharacterToLinkEditText(char.toString()) }
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendTextToChatInput(text)
            return
        } else {
            webView.evaluateJavascript(
                    """
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }
            
            // Create a composition event to better simulate natural typing
            function simulateNaturalInput(element, text) {
                // First, create a compositionstart event
                const compStart = new Event('compositionstart', { bubbles: true });
                element.dispatchEvent(compStart);
                
                // Then create main input event with the data
                const inputEvent = new InputEvent('input', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: text,
                    composed: true
                });
                
                // Store original value
                const originalValue = element.value || '';
                
                // Create a beforeinput event
                const beforeInputEvent = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: text
                });
                element.dispatchEvent(beforeInputEvent);
                
                if (!beforeInputEvent.defaultPrevented) {
                    // Let the browser handle the input naturally
                    const nativeInputEvent = new Event('input', { bubbles: true });
                    element.dispatchEvent(nativeInputEvent);
                    
                    // Use execCommand for more natural insertion
                    if (document.execCommand) {
                        document.execCommand('insertText', false, text);
                    } else {
                        // Fallback: try to preserve cursor position
                        const start = element.selectionStart;
                        const end = element.selectionEnd;
                        element.value = originalValue.slice(0, start) + 
                                      text +
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
            
            return JSON.stringify(simulateNaturalInput(el, ${JSONObject.quote(text)}));
        })();
        """
            ) { result -> DebugLog.d("InputDebug", "JavaScript result: $result") }
        }
    }

    private fun sendBackspaceToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendBackspaceInLinkEditText()
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendBackspaceToChatInput()
            return
        } else {
            webView.evaluateJavascript(
                    """
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
        """
            ) { result -> DebugLog.d("InputDebug", "Backspace JavaScript result: $result") }
        }
    }

    private fun dispatchTouchEventAtCursor() {

        if (isSimulatingTouchEvent || cursorJustAppeared || isToggling) {
            return
        }

        // Don't let the enter key tap pass through to the webview when keyboard closes
        if (wasKeyboardDismissedByEnter) {
            wasKeyboardDismissedByEnter = false
            return
        }

        // Suppress any webview click immediately after keyboard dismissal (hide button).
        val now = SystemClock.uptimeMillis()
        if (now < suppressWebClickUntil) {
            return
        }

        val scale = dualWebViewGroup.uiScale
        val interactionX: Float
        val interactionY: Float
        val groupLocation = reusableLocation1
        dualWebViewGroup.getLocationOnScreen(groupLocation)

        if (isAnchored) {
            // In anchored mode, interaction center is always screen center of the eye
            interactionX = 320f + groupLocation[0]
            interactionY = 240f + groupLocation[1]
        } else {
            // In non-anchored mode, interaction follows the visual cursor scaled around (320, 240)
            // and translated
            val transX = dualWebViewGroup.leftEyeUIContainer.translationX
            val transY = dualWebViewGroup.leftEyeUIContainer.translationY

            val visualX = 320f + (lastCursorX - 320f) * scale + transX
            val visualY = 240f + (lastCursorY - 240f) * scale + transY

            interactionX = visualX + groupLocation[0]
            interactionY = visualY + groupLocation[1]
        }

        // Intercept touches for mask overlay buttons when screen is masked
        if (dualWebViewGroup.isScreenMasked()) {
            dualWebViewGroup.dispatchMaskOverlayTouch(interactionX, interactionY)
            return
        }

        // Intercept touches for fullscreen overlay controls
        if (dualWebViewGroup.isFullScreenOverlayVisible()) {
            dualWebViewGroup.dispatchFullScreenOverlayTouch(interactionX, interactionY)
            return
        }

        // Intercept touches for dialogs
        if (dualWebViewGroup.isDialogAction(interactionX, interactionY)) {
            val dialogContainer = dualWebViewGroup.dialogContainer
            val location = reusableLocation1
            dialogContainer.getLocationOnScreen(location)

            // Calculate local coordinates relative to dialog container
            val localX = (interactionX - location[0]) / scale
            val localY = (interactionY - location[1]) / scale

            // Dispatch DOWN
            val downEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_DOWN,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            // Dispatch UP
            val upEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(upEvent)
            upEvent.recycle()
            return
        }

        // Check if settings menu is visible first
        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = reusableLocation1
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()

            if (interactionX >= settingsMenuLocation[0] &&
                            interactionX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            interactionY >= settingsMenuLocation[1] &&
                            interactionY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {

                // Dispatch touch event to settings menu using screen coordinates
                dualWebViewGroup.dispatchSettingsTouchEvent(interactionX, interactionY)
                return
            }
        }

        // Check for restore button click
        if (dualWebViewGroup.isPointInRestoreButton(interactionX, interactionY)) {
            dualWebViewGroup.performRestoreButtonClick()
            return
        }

        if (dualWebViewGroup.isChatVisible()) {
            if (dualWebViewGroup.isPointInChat(interactionX, interactionY)) {
                dualWebViewGroup.dispatchChatTouchEvent(interactionX, interactionY)
                return
            }
        }

        // Hit test for custom keyboard first so clicks never pass through it.
        if (isKeyboardVisible &&
                        wasKeyboardVisibleAtDown &&
                        dualWebViewGroup.isPointInKeyboard(interactionX, interactionY)
        ) {
            // Anchored mode needs explicit dispatch; non-anchored is handled by the view itself.
            if (isAnchored) {
                dualWebViewGroup.dispatchKeyboardTap(interactionX, interactionY)
            }
            return
        }

        // Check for bookmarks interaction (prevent click propagation to webview)
        if (dualWebViewGroup.isBookmarksExpanded()) {
            if (dualWebViewGroup.isPointInBookmarks(interactionX, interactionY)) {
                // Handled by DualWebViewGroup.onTouchEvent - just don't dispatch to webview
                DebugLog.d("ClickDebug", "Click consumed by bookmarks window")
                return
            }
        }

        // If the tap started on the keyboard, never let it fall through to the WebView.
        if (wasTouchOnKeyboard) {
            DebugLog.d("ClickDebug", "Click consumed by keyboard")
            return
        }

        // Check for windows overview interaction
        if (dualWebViewGroup.isWindowsOverviewVisible()) {
            if (dualWebViewGroup.isPointInWindowsOverview(interactionX, interactionY)) {
                dualWebViewGroup.performWindowsOverviewClick()
                return
            }
        }

        // Handle toggle/navigation bar clicks before scrollbars/web content
        val toggleHit =
                dualWebViewGroup.isToggleBarVisible() &&
                        dualWebViewGroup.isPointInToggleBar(interactionX, interactionY)
        val navHit =
                dualWebViewGroup.isNavBarVisible() &&
                        dualWebViewGroup.isPointInNavBar(interactionX, interactionY)
        if (toggleHit || navHit) {
            isSimulatingTouchEvent = false
            dualWebViewGroup.handleNavigationClick(interactionX, interactionY)
            return
        }

        // Check for scrollbar interaction
        if (dualWebViewGroup.isPointInScrollbar(interactionX, interactionY)) {
            dualWebViewGroup.dispatchScrollbarTouch(interactionX, interactionY)
            return
        }

        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastClickTime < MIN_CLICK_INTERVAL) {
            return
        }
        lastClickTime = currentTime

        // WebView click path
        isSimulatingTouchEvent = true
        try {
            val webViewLocation = reusableLocation1
            webView.getLocationOnScreen(webViewLocation)

            val translatedX = interactionX - webViewLocation[0]
            val translatedY = interactionY - webViewLocation[1]

            val adjustedX: Float
            val adjustedY: Float

            if (isAnchored) {
                val rotationRad =
                        Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()
                val unscaledX = translatedX * cos + translatedY * sin
                val unscaledY = -translatedX * sin + translatedY * cos
                adjustedX = unscaledX / scale
                adjustedY = unscaledY / scale
            } else {
                adjustedX = translatedX / scale
                adjustedY = translatedY / scale
            }

            val eventTime = SystemClock.uptimeMillis()

            // Set simulation flag to true to prevent this event from being counted as a new gesture
            // by the OnTouchListener (which would trigger onDown and inaccurate tap counts).
            // It gets reset to false in the cleanup handler below (line 2842).
            isSimulatingTouchEvent = true

            // DOWN event
            val motionEventDown =
                    MotionEvent.obtain(
                                    eventTime,
                                    eventTime,
                                    MotionEvent.ACTION_DOWN,
                                    adjustedX,
                                    adjustedY,
                                    1 // pointer count
                            )
                            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            webView.dispatchTouchEvent(motionEventDown)

            webView.evaluateJavascript(
                    """
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
"""
            ) { _ ->
                // Complete the touch sequence regardless of whether we found a special link
                Handler(Looper.getMainLooper())
                        .postDelayed(
                                {
                                    val motionEventUp =
                                            MotionEvent.obtain(
                                                            eventTime,
                                                            SystemClock.uptimeMillis(),
                                                            MotionEvent.ACTION_UP,
                                                            adjustedX,
                                                            adjustedY,
                                                            1
                                                    )
                                                    .apply {
                                                        source = InputDevice.SOURCE_TOUCHSCREEN
                                                    }
                                    webView.dispatchTouchEvent(motionEventUp)

                                    // Clean up
                                    motionEventDown.recycle()
                                    motionEventUp.recycle()

                                    // Reset states and check keyboard
                                    Handler(Looper.getMainLooper())
                                            .postDelayed(
                                                    {
                                                        checkAndShowKeyboard(
                                                                adjustedX.toInt(),
                                                                adjustedY.toInt()
                                                        )
                                                        isSimulatingTouchEvent = false
                                                        cursorJustAppeared = false
                                                        isToggling = false
                                                    },
                                                    150
                                            )
                                },
                                16
                        )
            }
        } catch (e: Exception) {
            DebugLog.e("ClickDebug", "Error in dispatchTouchEventAtCursor: ${e.message}")
            e.printStackTrace()
            isSimulatingTouchEvent = false
        }
    }

    private fun checkAndShowKeyboard(adjustedX: Int, adjustedY: Int) {
        webView.evaluateJavascript(
                """
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
    """
        ) { result ->
            DebugLog.d("InputDebug", "Element detection result after touch: $result")
            if (result?.contains("input") == true) {
                Handler(Looper.getMainLooper()).post { showCustomKeyboard() }
            }
        }
    }

    private fun maybeShowKeyboardForMouseClick(rawScreenX: Float, rawScreenY: Float) {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return

        val webViewLocation = reusableLocation1
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = rawScreenX - webViewLocation[0]
        val translatedY = rawScreenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return
        }

        val scale = dualWebViewGroup.uiScale
        val adjustedX: Float
        val adjustedY: Float

        if (isAnchored) {
            val rotationRad =
                    Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        checkAndShowKeyboard(adjustedX.toInt(), adjustedY.toInt())
    }

    private fun mapMousePointForVirtualTap(
            rawScreenX: Float,
            rawScreenY: Float
    ): Pair<Float, Float> {
        if (!isMouseTapMode) return rawScreenX to rawScreenY
        val fallbackEyeWidth = 640f
        if (!::dualWebViewGroup.isInitialized) {
            if (rawScreenX < fallbackEyeWidth) return rawScreenX to rawScreenY
            return (rawScreenX - fallbackEyeWidth) to rawScreenY
        }

        val groupLocation = reusableLocation1
        dualWebViewGroup.getLocationOnScreen(groupLocation)
        val groupLeft = groupLocation[0].toFloat()
        val groupWidth =
                dualWebViewGroup.width.toFloat().takeIf { it > 0f } ?: (fallbackEyeWidth * 2f)
        val eyeWidth = (groupWidth / 2f).coerceAtLeast(1f)

        val xWithinGroup = rawScreenX - groupLeft
        if (xWithinGroup < 0f || xWithinGroup >= groupWidth) {
            // Outside dual-eye surface: do not remap.
            return rawScreenX to rawScreenY
        }

        if (xWithinGroup < eyeWidth) {
            return rawScreenX to rawScreenY
        }

        return (rawScreenX - eyeWidth) to rawScreenY
    }

    private fun mapScreenPointToWebViewTouch(screenX: Float, screenY: Float): Pair<Float, Float>? {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return null
        val scale = dualWebViewGroup.uiScale
        val webViewLocation = reusableLocation1
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = screenX - webViewLocation[0]
        val translatedY = screenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return null
        }

        val adjustedX: Float
        val adjustedY: Float
        if (isAnchored) {
            val rotationRad =
                    Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        return adjustedX to adjustedY
    }

    private fun dispatchWebTouchFromScreen(
            action: Int,
            screenX: Float,
            screenY: Float,
            eventTime: Long = SystemClock.uptimeMillis(),
            downTime: Long = mouseSwipeDownTime
    ): Boolean {
        val mapped = mapScreenPointToWebViewTouch(screenX, screenY) ?: return false
        val adjustedX = mapped.first
        val adjustedY = mapped.second

        val event =
                MotionEvent.obtain(downTime, eventTime, action, adjustedX, adjustedY, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
        isSimulatingTouchEvent = true
        try {
            webView.dispatchTouchEvent(event)
        } finally {
            isSimulatingTouchEvent = false
            event.recycle()
        }
        return true
    }

    private fun isPointOnCustomUi(screenX: Float, screenY: Float): Boolean {
        if (!::dualWebViewGroup.isInitialized) return false
        if (dualWebViewGroup.isScreenMasked()) return true
        if (dualWebViewGroup.isFullScreenOverlayVisible()) return true
        if (dualWebViewGroup.isDialogAction(screenX, screenY)) return true

        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = reusableLocation1
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()
            if (screenX >= settingsMenuLocation[0] &&
                            screenX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            screenY >= settingsMenuLocation[1] &&
                            screenY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {
                return true
            }
        }

        if (dualWebViewGroup.isPointInRestoreButton(screenX, screenY)) return true
        if (dualWebViewGroup.isChatVisible() && dualWebViewGroup.isPointInChat(screenX, screenY))
                return true
        if (isKeyboardVisible && dualWebViewGroup.isPointInKeyboard(screenX, screenY)) return true
        if (dualWebViewGroup.isWindowsOverviewVisible() &&
                        dualWebViewGroup.isPointInWindowsOverview(screenX, screenY)
        ) {
            return true
        }
        if (dualWebViewGroup.isToggleBarVisible() &&
                        dualWebViewGroup.isPointInToggleBar(screenX, screenY)
        ) {
            return true
        }
        if (dualWebViewGroup.isNavBarVisible() && dualWebViewGroup.isPointInNavBar(screenX, screenY)
        ) {
            return true
        }
        if (dualWebViewGroup.isPointInScrollbar(screenX, screenY)) return true
        return false
    }

    private fun resolveMouseScreenPoint(ev: MotionEvent): Pair<Float, Float> {
        var screenX = ev.rawX
        var screenY = ev.rawY

        if (!screenX.isFinite() || !screenY.isFinite()) {
            val rootLoc = reusableLocation1
            window.decorView.getLocationOnScreen(rootLoc)
            screenX = ev.x + rootLoc[0]
            screenY = ev.y + rootLoc[1]
        }

        return screenX to screenY
    }

    private fun dispatchWebTapAtScreenCoordinates(screenX: Float, screenY: Float) {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return
        if (isSimulatingTouchEvent) return

        val scale = dualWebViewGroup.uiScale
        val webViewLocation = reusableLocation1
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = screenX - webViewLocation[0]
        val translatedY = screenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return
        }

        val adjustedX: Float
        val adjustedY: Float
        if (isAnchored) {
            val rotationRad =
                    Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        val eventTime = SystemClock.uptimeMillis()
        isSimulatingTouchEvent = true
        try {
            val downEvent =
                    MotionEvent.obtain(
                                    eventTime,
                                    eventTime,
                                    MotionEvent.ACTION_DOWN,
                                    adjustedX,
                                    adjustedY,
                                    1
                            )
                            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            webView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                val upEvent =
                                        MotionEvent.obtain(
                                                        eventTime,
                                                        SystemClock.uptimeMillis(),
                                                        MotionEvent.ACTION_UP,
                                                        adjustedX,
                                                        adjustedY,
                                                        1
                                                )
                                                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                                webView.dispatchTouchEvent(upEvent)
                                upEvent.recycle()
                                checkAndShowKeyboard(adjustedX.toInt(), adjustedY.toInt())
                                isSimulatingTouchEvent = false
                            },
                            16
                    )
        } catch (e: Exception) {
            isSimulatingTouchEvent = false
            DebugLog.e("MouseTap", "Failed to dispatch virtual web tap: ${e.message}")
        }
    }

    private fun handleMouseClickForCustomUi(rawScreenX: Float, rawScreenY: Float): Boolean {
        if (!::dualWebViewGroup.isInitialized) return false

        val scale = dualWebViewGroup.uiScale

        if (dualWebViewGroup.isScreenMasked()) {
            dualWebViewGroup.dispatchMaskOverlayTouch(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isFullScreenOverlayVisible()) {
            dualWebViewGroup.dispatchFullScreenOverlayTouch(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isDialogAction(rawScreenX, rawScreenY)) {
            val dialogContainer = dualWebViewGroup.dialogContainer
            val location = reusableLocation1
            dialogContainer.getLocationOnScreen(location)
            val localX = (rawScreenX - location[0]) / scale
            val localY = (rawScreenY - location[1]) / scale

            val downEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_DOWN,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            val upEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(upEvent)
            upEvent.recycle()
            return true
        }

        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = reusableLocation1
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()
            if (rawScreenX >= settingsMenuLocation[0] &&
                            rawScreenX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            rawScreenY >= settingsMenuLocation[1] &&
                            rawScreenY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {
                dualWebViewGroup.dispatchSettingsTouchEvent(rawScreenX, rawScreenY)
                return true
            }
        }

        if (dualWebViewGroup.isPointInRestoreButton(rawScreenX, rawScreenY)) {
            dualWebViewGroup.performRestoreButtonClick()
            return true
        }

        if (dualWebViewGroup.isChatVisible() &&
                        dualWebViewGroup.isPointInChat(rawScreenX, rawScreenY)
        ) {
            dualWebViewGroup.dispatchChatTouchEvent(rawScreenX, rawScreenY)
            return true
        }

        if (isKeyboardVisible && dualWebViewGroup.isPointInKeyboard(rawScreenX, rawScreenY)) {
            dualWebViewGroup.dispatchKeyboardTap(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isWindowsOverviewVisible() &&
                        dualWebViewGroup.isPointInWindowsOverview(rawScreenX, rawScreenY)
        ) {
            dualWebViewGroup.performWindowsOverviewClick()
            return true
        }

        val toggleHit =
                dualWebViewGroup.isToggleBarVisible() &&
                        dualWebViewGroup.isPointInToggleBar(rawScreenX, rawScreenY)
        val navHit =
                dualWebViewGroup.isNavBarVisible() &&
                        dualWebViewGroup.isPointInNavBar(rawScreenX, rawScreenY)
        if (toggleHit || navHit) {
            dualWebViewGroup.handleNavigationClick(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isPointInScrollbar(rawScreenX, rawScreenY)) {
            dualWebViewGroup.dispatchScrollbarTouch(rawScreenX, rawScreenY)
            return true
        }

        return false
    }

    private fun sendEnterToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendEnterInLinkEditText()
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendEnterToChatInput()
            hideCustomKeyboard()
            return
        } else {
            webView.evaluateJavascript(
                    """
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
        """
            ) { result ->
                DebugLog.d("InputDebug", "Enter JavaScript result: $result")
                Handler(Looper.getMainLooper()).post { hideCustomKeyboard() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebView(webView)
    }

    override fun onWindowCreated(webView: WebView) {
        configureWebView(webView)
        // Default URL loading is now handled in DualWebViewGroup.createNewWindow()
        // to avoid overriding restored state
    }

    override fun onWindowSwitched(webView: WebView) {
        // Update reference
        this.webView = webView

        // Ensure the correct touch listener is attached (though configureWebView likely did it)
        attachTouchListener(webView)
        applyForceDarkModeSetting(webView)

        // Persist the newly active window so reopen returns to the correct tab/page.
        schedulePersistActiveWebViewState("onWindowSwitched", webView)
    }

    private fun handleTapLinkChatUrl(view: WebView?, url: String?): Boolean {
        if (url == null || !url.startsWith("taplink://chat")) {
            return false
        }

        DebugLog.d("MainActivityClient") { "Intercepted taplink://chat" }

        val uri = Uri.parse(url)
        val msg = uri.getQueryParameter("msg")
        val history = uri.getQueryParameter("history")

        if (msg != null && view != null) {
            com.TapLinkX3.app.WebAppInterface(this, view).chatWithGroq(msg, history ?: "[]")
        }
        return true
    }

    private fun shouldOverrideWebViewNavigation(view: WebView?, uri: Uri?): Boolean {
        uri ?: return false
        val url = uri.toString()

        // Block about:blank navigations
        if (url.startsWith("about:blank")) {
            return true
        }

        val scheme = uri.scheme?.lowercase()

        if (handleTapLinkChatUrl(view, url)) {
            return true
        }

        // Handle app intents
        if (scheme == "intent" || scheme == "market") {
            val fallbackUrl =
                    url.substringAfter("fallback_url=", "")
                            .substringBefore("#", "")
                            .substringBefore("&", "")

            if (fallbackUrl.isNotEmpty() &&
                            (fallbackUrl.startsWith("http") || fallbackUrl.startsWith("https"))
            ) {
                view?.loadUrl(fallbackUrl)
                return true
            }
            return true
        }

        // Let WebView handle schemes it natively understands
        if (scheme == null ||
                        scheme == "http" ||
                        scheme == "https" ||
                        scheme == "file" ||
                        scheme == "about" ||
                        scheme == "data" ||
                        scheme == "blob" ||
                        scheme == "javascript"
        ) {
            applyUserAgentForUrl(view, url)
            return false
        }

        // For app/deep-link schemes (e.g., TikTok snssdk1233://), try external
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            DebugLog.w("WebView", "No handler for URL scheme: $scheme ($url)")
            true
        } catch (e: Exception) {
            DebugLog.w("WebView", "Failed to open external URL: $url")
            true
        }
    }

    private fun isForceDarkWebEnabled(): Boolean {
        return getSharedPreferences("TapLinkPrefs", MODE_PRIVATE)
                .getBoolean("forceDarkWebEnabled", true)
    }

    private fun applyForceDarkModeSetting(
            targetWebView: WebView,
            enabled: Boolean = isForceDarkWebEnabled()
    ) {
        targetWebView.settings.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = enabled
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark = if (enabled) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    fun setForceDarkWebEnabled(enabled: Boolean) {
        getSharedPreferences("TapLinkPrefs", MODE_PRIVATE)
                .edit()
                .putBoolean("forceDarkWebEnabled", enabled)
                .apply()

        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.getAllWebViews().forEach { applyForceDarkModeSetting(it, enabled) }
        } else if (::webView.isInitialized) {
            applyForceDarkModeSetting(webView, enabled)
        }
    }

    private fun attachTouchListener(targetWebView: WebView) {
        targetWebView.setOnTouchListener { _, event ->
            val isMouseEvent = isMousePointerEvent(event)
            // Allow simulated events to pass through immediately (used for scrolling)
            // CRITICAL: Check this FIRST to prevent infinite loops where simulated events
            // are fed back into gestureDetector, triggering more scrolls.
            if (isSimulatingTouchEvent) {
                return@setOnTouchListener false
            }

            if (isMouseTapMode && isMouseEvent) {
                return@setOnTouchListener false
            }

            // DO NOT call gestureDetector.onTouchEvent(event) here!
            // It is already called in MainActivity.dispatchTouchEvent().
            // Calling it again causes double-counting of taps (1 physical tap = 2 gesture taps).
            // We just use the result stored in isGestureHandled.

            // Logic to clear pending runnables on touch interaction
            if (event.action == MotionEvent.ACTION_DOWN ||
                            event.action == MotionEvent.ACTION_UP ||
                            event.action == MotionEvent.ACTION_CANCEL
            ) {
                pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
                pendingTouchRunnable = null
            }

            // --- BLOCKING LOGIC START ---

            if (isAnchored && isKeyboardVisible) {
                // Special case for anchored keyboard? (Original logic preserved)
                return@setOnTouchListener false
            }

            if (isKeyboardVisible) {
                // If keyboard is visible, block touches to WebView (prevent clicks behind keyboard)
                return@setOnTouchListener true
            }

            if (dualWebViewGroup.isSettingsVisible()) {
                // If settings are open, only block if cursor is visible?
                // logic matches original: return isCursorVisible
                return@setOnTouchListener isCursorVisible
            }

            if (dualWebViewGroup.isInScrollMode()) {
                // SCROLL MODE ENFORCEMENT:
                // Block ALL real touch events. The only way to interact is via
                // simulated scroll events (caught by isSimulatingTouchEvent check above)
                // or via gestures (caught by gestureDetector above).
                return@setOnTouchListener true
            }

            if (isCursorVisible && !isMouseEvent) {
                // If cursor is visible, we are in "mouse mode" - block direct touches.
                return@setOnTouchListener true
            }

            // --- BLOCKING LOGIC END ---

            // Otherwise, return handled state from GestureDetector
            // If GestureDetector consumed it (e.g., tap, long press), we consume.
            // If not, we fall through to false?
            // Actually original code returned 'handled' which was 'isGestureHandled'
            isGestureHandled
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        attachTouchListener(webView)

        // First check if speech recognition is available
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val speechRecognitionAvailable =
                packageManager.resolveActivity(speechRecognizerIntent, 0) != null
        DebugLog.d("WebView", "Speech recognition available: $speechRecognitionAvailable")

        // Section 1: Basic WebView Configuration
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.addJavascriptInterface(WebAppInterface(this, webView), "GroqBridge")

        webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE
            overScrollMode = View.OVER_SCROLL_NEVER

            // Section 2: WebView Settings Configuration
            settings.apply {
                // JavaScript and Content Settings
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION") databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false

                // Security and Access Settings
                allowFileAccess = true
                allowContentAccess = true
                setGeolocationEnabled(true)

                // Display and Layout Settings
                @Suppress("DEPRECATION") defaultZoom = WebSettings.ZoomDensity.MEDIUM
                useWideViewPort = true
                loadWithOverviewMode = true
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                textZoom = 80

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

                // Store default UA for sites that require it (like Netflix)
                if (defaultUserAgent == null) {
                    defaultUserAgent = WebSettings.getDefaultUserAgent(this@MainActivity)
                }

                // Use the actual runtime WebView UA to avoid auth providers flagging spoofed
                // clients.
                customUserAgent = defaultUserAgent
                if (!customUserAgent.isNullOrBlank()) {
                    settings.userAgentString = customUserAgent

                    // Pass runtime UA to DualWebViewGroup so mobile/desktop modes derive from it.
                    dualWebViewGroup.setMobileUserAgent(customUserAgent!!)
                }

                // Explicitly enable media
                setMediaPlaybackRequiresUserGesture(false)
            }
            applyForceDarkModeSetting(this)

            // Enable third-party cookies specifically for auth
            CookieManager.getInstance().apply {
                setAcceptThirdPartyCookies(webView, true)
                setAcceptCookie(true)
                acceptCookie()
            }

            // Section 3: Single WebViewClient Implementation
            webViewClient =
                    object : WebViewClient() {
                        private var lastValidUrl: String? = null

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            DebugLog.d("WebViewDebug", "Page started loading: $url")
                            // If cursor was visible, store its position
                            if (isCursorVisible) {
                                lastKnownCursorX = lastCursorX
                                lastKnownCursorY = lastCursorY
                            }

                            if (closeChatOnNextPageStart) {
                                val now = SystemClock.uptimeMillis()
                                if (now > closeChatOnNextPageStartDeadlineMs) {
                                    closeChatOnNextPageStart = false
                                } else if (!url.isNullOrBlank() && !url.startsWith("about:blank")) {
                                    closeChatOnNextPageStart = false
                                    dualWebViewGroup.hideChat()
                                }
                            }

                            dualWebViewGroup.clearExternalScrollMetrics()

                            applyUserAgentForUrl(view, url)

                            // Show loading bar immediately
                            dualWebViewGroup.updateLoadingProgress(0)

                            if (url != null && !url.startsWith("about:blank")) {
                                lastValidUrl = url

                                // Persist the URL as soon as navigation starts so app relaunch
                                // returns to the newest page even if load doesn't finish.
                                persistActiveUrl("onPageStarted", url, view)

                                // Start observers early so scrollbars can appear before full load.
                                view?.let { dualWebViewGroup.injectPageObservers(it) }

                                // Inject location early so it's available before page JS runs
                                if (lastGpsLat != null && lastGpsLon != null) {
                                    dualWebViewGroup.injectLocation(lastGpsLat!!, lastGpsLon!!)
                                }
                            } else if (url?.startsWith("about:blank") == true &&
                                            lastValidUrl != null
                            ) {
                                // Cancel about:blank load immediately
                                view?.stopLoading()
                                view?.loadUrl(lastValidUrl!!)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            DebugLog.d("WebViewDebug") { "Page finished loading: $url" }
                            maybeRunMessagesCompatibilityReload(view, url)

                            // Ensure loading bar is hidden when finished
                            dualWebViewGroup.updateLoadingProgress(100)

                            // Keep crash recovery current without serializing every WebView
                            // synchronously in the page-finish callback.
                            schedulePersistActiveWebViewState("onPageFinished", view)
                            dualWebViewGroup.scheduleSaveAllWindowsState()

                            if (url != null && !url.startsWith("about:blank")) {
                                view?.visibility = View.VISIBLE
                                injectJavaScriptForInputFocus()

                                // Force enable input on all potential input fields
                                view?.evaluateJavascript(
                                        """
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
                    """.trimIndent(),
                                        null
                                )

                                wasKeyboardDismissedByEnter = false

                                // Enforce desktop viewport if enabled or desktop-required, otherwise mobile viewport (persists across reloads/SPA navigation)
                                val isDesktop = dualWebViewGroup.isDesktopMode() || isDesktopBrowserRequiredSite(url)
                                val isGlassApps = isGlassAppsSite(url)
                                val viewportContent = when {
                                    isDesktop -> "width=1280, initial-scale=0.8"
                                    else -> "width=600, initial-scale=1.0, maximum-scale=1.0"
                                }

                                if (!isGlassApps) {
                                    view?.evaluateJavascript(
                                            """
                            (function() {
                                var viewport = document.querySelector('meta[name="viewport"]');
                                if (!viewport) {
                                    viewport = document.createElement('meta');
                                    viewport.name = 'viewport';
                                    document.head.appendChild(viewport);
                                }
                                viewport.content = '$viewportContent';
                            })();
                            """.trimIndent(),
                                            null
                                    )
                                } else {
                                    // Inject band-aid CSS to fix massive unstyled logos due to old WebView engine
                                    view?.evaluateJavascript(
                                            """
                            (function() {
                                var style = document.createElement('style');
                                style.innerHTML = `
                                    img[src*="father.png"] { max-width: 64px !important; max-height: 64px !important; }
                                    img.h-20 { max-width: 80px !important; max-height: 80px !important; }
                                    img.h-14 { max-width: 56px !important; max-height: 56px !important; }
                                    img { max-width: 100% !important; height: auto !important; }
                                `;
                                document.head.appendChild(style);
                            })();
                            """.trimIndent(),
                                            null
                                    )
                                }

                                view?.settings?.textZoom = 80

                                // Re-apply saved font settings to new page
                                if (!isGlassApps) {
                                    dualWebViewGroup.reapplyWebFontSettings()
                                }

                                // Inject last known location if available
                                if (lastGpsLat != null && lastGpsLon != null) {
                                    dualWebViewGroup.injectLocation(lastGpsLat!!, lastGpsLon!!)
                                }

                                maybeShowGlassAppsMetaModeToast(url)

                                // Restore media listeners and scrollbar logic from DualWebViewGroup
                                view?.let { dualWebViewGroup.injectPageObservers(it) }
                                dualWebViewGroup.updateScrollBarsVisibility()
                            }
                        }

                        override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                if (detail?.didCrash() == true) {
                                    DebugLog.e("WebView", "Render process crashed!")
                                    dualWebViewGroup.showConfirmDialog(
                                            "The web page crashed. Reload?",
                                            { view?.reload() },
                                            { /* Do nothing */}
                                    )
                                } else {
                                    DebugLog.e("WebView", "Render process killed by system (OOM).")
                                    // If system killed it, we can just return true and let the OS
                                    // handle it,
                                    // or offer a reload.
                                }
                            }
                            return true // Prevent app crash
                        }

                        override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                        ): Boolean {
                            return shouldOverrideWebViewNavigation(view, request?.url)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?
                        ): Boolean {
                            val uri = url?.let { Uri.parse(it) }
                            return shouldOverrideWebViewNavigation(view, uri)
                        }
                    }

            // Consolidate WebChromeClient to handle permissions, file choosing, and custom views
            webChromeClient =
                    object : WebChromeClient() {
                        // From first client
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            dualWebViewGroup.updateLoadingProgress(newProgress)
                        }
                        override fun onReceivedTouchIconUrl(
                                view: WebView?,
                                url: String?,
                                precomposed: Boolean
                        ) {
                            DebugLog.d("WebViewDebug") { "Received touch icon URL: $url" }
                            super.onReceivedTouchIconUrl(view, url, precomposed)
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            DebugLog.d("WebViewInput") {
                                "${consoleMessage.messageLevel()} [${consoleMessage.lineNumber()}]: ${consoleMessage.message()}"
                            }
                            return true
                        }

                        // Combined onPermissionRequest
                        override fun onPermissionRequest(request: PermissionRequest) {
                            DebugLog.d(
                                    "WebView",
                                    "Permission request: ${request.resources.joinToString()}"
                            )

                            val permissions = mutableListOf<String>()
                            val requiredAndroidPermissions = mutableListOf<String>()
                            var needsAudioRoute = false

                            request.resources.forEach { resource ->
                                when (resource) {
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                        permissions.add(resource)
                                        requiredAndroidPermissions.add(
                                                android.Manifest.permission.RECORD_AUDIO
                                        )
                                        needsAudioRoute = true
                                    }
                                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                        permissions.add(resource)
                                        requiredAndroidPermissions.add(
                                                android.Manifest.permission.CAMERA
                                        )
                                    }
                                }
                            }

                            runOnUiThread {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val notGrantedPermissions =
                                            requiredAndroidPermissions.filter {
                                                checkSelfPermission(it) !=
                                                        PackageManager.PERMISSION_GRANTED
                                            }

                                    if (notGrantedPermissions.isNotEmpty()) {
                                        pendingPermissionRequest = request
                                        requestPermissions(
                                                notGrantedPermissions.toTypedArray(),
                                                PERMISSIONS_REQUEST_CODE
                                        )
                                    } else {
                                        request.grant(permissions.toTypedArray())
                                        // Configure AR glasses mic AFTER granting so
                                        // WebView can initialise its audio capture first.
                                        if (needsAudioRoute) {
                                            setVoiceAssistantAudioRoute(true)
                                        }
                                    }
                                } else {
                                    request.grant(permissions.toTypedArray())
                                    if (needsAudioRoute) {
                                        setVoiceAssistantAudioRoute(true)
                                    }
                                }
                            }
                        }

                        override fun onPermissionRequestCanceled(request: PermissionRequest) {
                            pendingPermissionRequest = null
                            // Reset audio source when permissions are cancelled
                            setVoiceAssistantAudioRoute(false)
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                                origin: String,
                                callback: GeolocationPermissions.Callback
                        ) {
                            // Always grant permission for WebView content so our injected GPS logic
                            // takes over
                            noteGeolocationUse()
                            callback.invoke(origin, true, false)
                        }

                        // From second client
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

                        // From first client
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
                            val contentSelectionIntent =
                                    createContentSelectionIntent(fileChooserParams?.acceptTypes)

                            // Let user pick from either camera or existing files
                            val intentArray =
                                    if (takePictureIntent != null) arrayOf(takePictureIntent)
                                    else arrayOfNulls<Intent>(0)

                            // Create a chooser
                            val chooserIntent =
                                    Intent(Intent.ACTION_CHOOSER).apply {
                                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                                        putExtra(Intent.EXTRA_TITLE, "Image Chooser")
                                        putExtra(
                                                Intent.EXTRA_INITIAL_INTENTS,
                                                intentArray.filterNotNull().toTypedArray()
                                        )
                                    }

                            try {
                                @Suppress("DEPRECATION")
                                startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                            } catch (e: ActivityNotFoundException) {
                                this@MainActivity.filePathCallback = null
                                return false
                            }
                            return true
                        }

                        // Custom Dialog Handling
                        override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showAlertDialog(message ?: "") { result?.confirm() }
                            return true
                        }

                        override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showConfirmDialog(
                                    message ?: "",
                                    { result?.confirm() },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onJsPrompt(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                defaultValue: String?,
                                result: android.webkit.JsPromptResult?
                        ): Boolean {
                            dualWebViewGroup.showPromptDialog(
                                    message ?: "",
                                    defaultValue,
                                    { text -> result?.confirm(text) },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onJsBeforeUnload(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showConfirmDialog(
                                    message ?: "Are you sure you want to leave this page?",
                                    { result?.confirm() },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                        ): Boolean {
                            // Must provide a pristine WebView here; Chromium will navigate it.
                            val newWebView =
                                    dualWebViewGroup.createNewWindow(loadDefaultUrl = false)
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = newWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                            return false
                        }
                    }
        }

        // Section 5: Input Handling Configuration
        // Section 5: Input Handling Configuration
        disableDefaultKeyboard(webView)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        // Only try restoration if this is the initial window
        if (webView == dualWebViewGroup.getWebView()) {
            tryRestoreSession()
        }

        // Initialize AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Additional WebView settings for media support
        webView.settings.apply {
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            javaScriptEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
        }

        logPermissionState() // Log initial permission state

        webView.addJavascriptInterface(AndroidInterface(this, webView), "AndroidInterface")
        // Add JavaScript interface for custom media handling if needed
        webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onMediaStart(type: String) {
                        when (type) {
                            "audio" -> setVoiceAssistantAudioRoute(true)
                            "video" -> {
                                /* Handle camera initialization if needed */
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onMediaStop() {
                        setVoiceAssistantAudioRoute(false)
                    }
                },
                "AndroidMediaInterface"
        )
    }

    private fun tryRestoreSession() {
        // Before loading the initial page, try to restore the previous session
        DebugLog.d("WebViewDebug", "Attempting to restore previous session")

        try {
            dualWebViewGroup.updateBrowsingMode(dualWebViewGroup.isDesktopMode())
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val savedState = prefs.getString(Constants.KEY_WEBVIEW_STATE, null)
            val lastUrl = prefs.getString(keyLastUrl, null)
            DebugLog.d("WebViewDebug", "Last saved URL: $lastUrl")

            val defaultDashboardUrl = Constants.DEFAULT_URL
            var restored = false

            if (!savedState.isNullOrBlank()) {
                try {
                    val data = Base64.decode(savedState, Base64.DEFAULT)
                    val parcel = Parcel.obtain()
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    val bundle = Bundle.CREATOR.createFromParcel(parcel)
                    parcel.recycle()
                    restored = webView.restoreState(bundle) != null
                    DebugLog.d("WebViewDebug", "WebView state restored: $restored")
                } catch (e: Exception) {
                    DebugLog.e("WebViewDebug", "Error restoring WebView state", e)
                }
            }

            if (!restored) {
                if (lastUrl != null && !lastUrl.startsWith("about:blank")) {
                    DebugLog.d("WebViewDebug", "Loading saved URL: $lastUrl")
                    webView.loadUrl(lastUrl)
                } else {
                    DebugLog.d("WebViewDebug", "No valid saved URL, loading default AR dashboard")
                    webView.loadUrl(defaultDashboardUrl)
                }
            } else {
                // Restored pages may skip onPageFinished; inject observers and refresh scrollbars.
                webView.post {
                    dualWebViewGroup.injectPageObservers(webView)
                    dualWebViewGroup.updateScrollBarsVisibility()
                }
                webView.postDelayed(
                        {
                            dualWebViewGroup.injectPageObservers(webView)
                            dualWebViewGroup.updateScrollBarsVisibility()
                        },
                        750
                )
            }
        } catch (e: Exception) {
            DebugLog.e("WebViewDebug", "Error restoring session", e)
            webView.loadUrl(Constants.DEFAULT_URL)
        }
    }

    private class WebAppInterface(
            private val activity: MainActivity,
            private val webView: WebView
    ) {
        @JavascriptInterface
        fun onInputFocus() {
            activity.runOnUiThread {
                // Double check that we're not already showing the keyboard
                if (!activity.isKeyboardVisible) {
                    activity.showCustomKeyboard()
                }
            }
        }

        @JavascriptInterface
        fun onMediaPlaying(isPlaying: Boolean) {
            activity.runOnUiThread {
                if (activity.dualWebViewGroup.isActiveWebView(webView)) {
                    activity.dualWebViewGroup.updateMediaState(isPlaying)
                    if (isPlaying) {
                        activity.dualWebViewGroup.pauseBackgroundMedia(webView)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onMediaDetected(hasMedia: Boolean) {
            activity.runOnUiThread {
                if (!hasMedia && activity.dualWebViewGroup.isActiveWebView(webView)) {
                    activity.dualWebViewGroup.hideMediaControls()
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
        if (cameraPermissionGranted) {
            initializeCamera()
        }
        // Create a file/URI to store the image
        val imageFile = createTempImageFile() ?: return null
        cameraImageUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
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
            File.createTempFile("tmp_image_", ".jpg", storageDir)
        } catch (e: IOException) {
            DebugLog.e("FileChooser", "Cannot create temp file", e)
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

    @Suppress("DEPRECATION")
    private fun showFullScreenCustomView(
            view: View,
            callback: WebChromeClient.CustomViewCallback?
    ) {
        if (fullScreenCustomView != null) {
            callback?.onCustomViewHidden()
            return
        }

        fullScreenCustomView = view
        customViewCallback = callback
        originalSystemUiVisibility = window.decorView.systemUiVisibility
        originalOrientation = requestedOrientation

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        dualWebViewGroup.showFullScreenOverlay(view)
        cursorLeftView.visibility = View.GONE
        cursorRightView.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    private fun hideFullScreenCustomView() {
        if (fullScreenCustomView == null) {
            return
        }

        // If the fullscreen view is the native QR scanner, clear scanner state on exit.
        if (nativeQrScannerView != null || isQrScanInProgress) {
            nativeQrScannerView?.pause()
            nativeQrScannerView = null
            pendingNativeQrStart = false
            isQrScanInProgress = false
            qrScanCallbackWebView = null
            dualWebViewGroup.setSuppressFullscreenMediaControls(false)
        }

        dualWebViewGroup.hideFullScreenOverlay()
        fullScreenCustomView = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(
                    android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        requestedOrientation = originalOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cursorLeftView.visibility = if (isCursorVisible) View.VISIBLE else View.GONE
        cursorRightView.visibility = View.GONE

        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun startNativeQrScanner(sourceWebView: WebView) {
        if (isQrScanInProgress) {
            val quotedMessage = JSONObject.quote("A scan is already in progress.")
            sourceWebView.evaluateJavascript(
                    "window.__taplinkOnNativeQrError($quotedMessage);",
                    null
            )
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            qrScanCallbackWebView = sourceWebView
            pendingNativeQrStart = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }

        qrScanCallbackWebView = sourceWebView
        isQrScanInProgress = true
        pendingNativeQrStart = false

        val scannerContainer =
                FrameLayout(this).apply {
                    setBackgroundColor(Color.BLACK)
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                }

        val scannerView =
                DecoratedBarcodeView(this).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                    barcodeView.decoderFactory =
                            DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    setStatusText("Point at a QR code")
                }

        scannerView.barcodeView.addStateListener(
                object : CameraPreview.StateListener {
                    override fun previewSized() {}

                    override fun previewStarted() {
                        applyDefaultQrZoom(scannerView)
                    }

                    override fun previewStopped() {}

                    override fun cameraError(error: Exception) {}

                    override fun cameraClosed() {}
                }
        )

        scannerContainer.addView(scannerView)
        nativeQrScannerView = scannerView

        dualWebViewGroup.setSuppressFullscreenMediaControls(true)
        showFullScreenCustomView(scannerContainer, null)

        scannerView.decodeContinuous(
                object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        val value = result?.text?.trim().orEmpty()
                        if (value.isEmpty() || !isQrScanInProgress) {
                            return
                        }

                        runOnUiThread {
                            if (!isQrScanInProgress) {
                                return@runOnUiThread
                            }
                            isQrScanInProgress = false
                            stopNativeQrScannerOverlay()
                            dispatchNativeQrResult(value)
                        }
                    }

                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
                        // No-op.
                    }
                }
        )
        scannerView.resume()
    }

    private fun applyDefaultQrZoom(scannerView: DecoratedBarcodeView) {
        @Suppress("DEPRECATION")
        scannerView.changeCameraParameters { parameters: android.hardware.Camera.Parameters ->
            try {
                CameraConfigurationUtils.setZoom(parameters, defaultQrZoomRatio)
            } catch (e: Exception) {
                DebugLog.w("QRScanner", "Unable to apply default camera zoom: ${e.message}")
            }
            parameters
        }
    }

    private fun dispatchNativeQrResult(contents: String) {
        val targetWebView = qrScanCallbackWebView ?: webView
        val quotedContents = JSONObject.quote(contents)
        targetWebView.evaluateJavascript("window.__taplinkOnNativeQrResult($quotedContents);", null)
        qrScanCallbackWebView = null
    }

    private fun dispatchNativeQrError(message: String, target: WebView? = qrScanCallbackWebView) {
        val targetWebView = target ?: webView
        val quotedMessage = JSONObject.quote(message)
        targetWebView.evaluateJavascript("window.__taplinkOnNativeQrError($quotedMessage);", null)
        pendingNativeQrStart = false
        isQrScanInProgress = false
        qrScanCallbackWebView = null
    }

    private fun stopNativeQrScannerOverlay() {
        nativeQrScannerView?.pause()
        nativeQrScannerView = null
        dualWebViewGroup.setSuppressFullscreenMediaControls(false)
        if (fullScreenCustomView != null) {
            hideFullScreenCustomView()
        }
    }

    private fun stopNativeQrScannerSession() {
        if (nativeQrScannerView == null && !isQrScanInProgress && !pendingNativeQrStart) {
            return
        }
        pendingNativeQrStart = false
        isQrScanInProgress = false
        qrScanCallbackWebView = null
        stopNativeQrScannerOverlay()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                if (filePathCallback != null) {
                    var results: Array<Uri>? = null

                    // Check if response is from Camera (data is null/empty but cameraImageUri is
                    // set)
                    // or from File Picker (data has URI)
                    if (data == null || data.data == null) {
                        // If cameraImageUri is populated, use it
                        if (cameraImageUri != null) {
                            results = arrayOf(cameraImageUri!!)
                        }
                    } else {
                        // File picker result
                        data.dataString?.let { results = arrayOf(Uri.parse(it)) }
                    }

                    filePathCallback?.onReceiveValue(results)
                    filePathCallback = null
                }
            } else {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }

        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            data?.extras?.get("data")?.let { imageBitmap ->
                // Convert bitmap to base64
                val base64Image = convertBitmapToBase64(imageBitmap as Bitmap)

                // Send the image back to Google Search
                webView.evaluateJavascript(
                        """
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
            """,
                        null
                )
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
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                                    PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (permissionsToRequest.isNotEmpty()) {
                requestPermissions(permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            }
        }
    }

    private fun logPermissionState() {
        val cameraPermission =
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        val micPermission =
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)

        DebugLog.d(
                "PermissionDebug",
                """
        Permission State:
        Camera: ${if (cameraPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
        Microphone: ${if (micPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
    """.trimIndent()
        )
    }

    private fun disableDefaultKeyboard(targetWebView: WebView) {
        try {
            val method =
                    WebView::class.java.getMethod("setShowSoftInputOnFocus", Boolean::class.java)
            method.invoke(targetWebView, false)
        } catch (e: Exception) {
            // Fallback for older Android versions
            targetWebView.evaluateJavascript(
                    """
            document.addEventListener('focus', function(e) {
                if (e.target.tagName === 'INPUT' || 
                    e.target.tagName === 'TEXTAREA' || 
                    e.target.isContentEditable) {
                    window.Android.onInputFocus();
                    e.target.blur();
                    setTimeout(() => e.target.focus(), 50);
                }
            }, true);
        """,
                    null
            )
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
            PERMISSIONS_REQUEST_CODE -> {
                pendingPermissionRequest?.let { request ->
                    val grantedResources = mutableListOf<String>()
                    var audioGranted = false
                    permissions.forEachIndexed { index, permission ->
                        if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                            if (permission == Manifest.permission.RECORD_AUDIO) {
                                grantedResources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                audioGranted = true
                            } else if (permission == Manifest.permission.CAMERA) {
                                grantedResources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                            } else if (permission == Manifest.permission.BLUETOOTH_CONNECT &&
                                            ::controllerBluetoothClient.isInitialized
                            ) {
                                controllerBluetoothClient.start()
                            }
                        }
                    }
                    if (grantedResources.isNotEmpty()) {
                        request.grant(grantedResources.toTypedArray())
                        // Configure AR glasses mic AFTER granting
                        if (audioGranted) {
                            setVoiceAssistantAudioRoute(true)
                        }
                    } else {
                        request.deny()
                    }
                    pendingPermissionRequest = null
                }
            }
            CAMERA_PERMISSION_CODE -> {
                cameraPermissionGranted =
                        grantResults.isNotEmpty() &&
                                grantResults[0] == PackageManager.PERMISSION_GRANTED

                if (!cameraPermissionGranted) {
                    DebugLog.e("Camera", "Camera permission denied")
                    // Inform user that camera features won't work
                    webView.evaluateJavascript(
                            "alert('Camera permission is required for image search');",
                            null
                    )
                    if (pendingNativeQrStart) {
                        dispatchNativeQrError("Camera permission denied.")
                    }
                } else if (pendingNativeQrStart) {
                    val targetWebView = qrScanCallbackWebView ?: webView
                    startNativeQrScanner(targetWebView)
                }
            }
        }
    }

    private fun loadInitialPage() {
        DebugLog.d("WebViewDebug", "loadInitialPage called")
        // Load Google directly without the intermediate blank page
        webView.loadUrl(Constants.DEFAULT_URL)
    }

    fun showCustomKeyboard() {
        DebugLog.d("KeyboardDebug", "1. Starting showCustomKeyboard")

        if (isControllerConnected) {
            if (!isPhoneKeyboardOpen) {
                isPhoneKeyboardOpen = true
                controllerBluetoothClient.sendKeyboardVisibility(true)
                dualWebViewGroup.showToast("Keyboard opened on phone", 1500L)
            }
            return
        }

        if (isKeyboardVisible &&
                        keyboardView?.visibility == View.VISIBLE &&
                        dualWebViewGroup.keyboardContainer.visibility == View.VISIBLE
        ) {
            DebugLog.d("KeyboardDebug", "Keyboard already visible; ignoring show request")
            return
        }

        // Force WebView to lose focus
        webView.clearFocus()
        DebugLog.d("KeyboardDebug", "2. WebView focus cleared")

        if (wasKeyboardDismissedByEnter) {
            wasKeyboardDismissedByEnter = false
            if (!dualWebViewGroup.isUrlEditing()) {
                return
            }
        }

        // Ensure keyboard view exists and is properly configured
        if (keyboardView == null) {
            DebugLog.d("KeyboardDebug", "3. Creating new keyboard view")
            keyboardView =
                    CustomKeyboardView(this).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        Gravity.BOTTOM
                                )
                        setOnKeyboardActionListener(this@MainActivity)
                        DebugLog.d("KeyboardDebug", "Keyboard created with visibility: $visibility")
                    }
        }

        // Hide info bars when keyboard shows
        dualWebViewGroup.hideInfoBars()

        keyboardView?.let { keyboard ->
            // Log state before setting keyboard
            DebugLog.d(
                    "KeyboardDebug",
                    """
            Before setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent()
            )

            // Force visibility BEFORE setting keyboard
            keyboard.visibility = View.VISIBLE
            dualWebViewGroup.keyboardContainer.visibility = View.VISIBLE

            dualWebViewGroup.setKeyboard(keyboard)

            // Log state after setting keyboard
            DebugLog.d(
                    "KeyboardDebug",
                    """
            After setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent()
            )
            isKeyboardVisible = true

            // Ensure keyboard is on top of dialogs
            dualWebViewGroup.keyboardContainer.elevation = 3000f
            dualWebViewGroup.keyboardContainer.bringToFront()
        }

        isKeyboardVisible = true

        dualWebViewGroup.post {
            dualWebViewGroup.updateScrollBarsVisibility()
            dualWebViewGroup.requestLayout()
            dualWebViewGroup.invalidate()
            refreshCursor()
        }
    }

    private fun toggleAnchor() {

        isAnchored = !isAnchored

        // Save anchored mode state
        getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_IS_ANCHORED, isAnchored)
                .apply()

        hideCustomKeyboard()
        DebugLog.d(
                "AnchorDebug",
                """
        Anchor toggled:
        isAnchored: $isAnchored
        isKeyboardVisible: $isKeyboardVisible
        keyboardView null?: ${keyboardView == null}
    """.trimIndent()
        )
        if (isAnchored) {
            // Move cursor to center of left screen
            centerCursor()

            // Initialize sensor handling with reset velocity tracking
            smoothedDeltaX = 0f
            smoothedDeltaY = 0f
            smoothedRollDeg = 0f
            lastAppliedAnchorX = Float.NaN
            lastAppliedAnchorY = Float.NaN
            lastAppliedAnchorRoll = Float.NaN
            lastFrameTime = 0L

            sensorEventListener = createSensorEventListener()
            rotationSensor?.let { sensor ->
                // Use a stable sensor cadence; UI work is still coalesced to vsync.
                sensorManager.registerListener(sensorEventListener, sensor, ANCHOR_SENSOR_DELAY)
            }
            dualWebViewGroup.startAnchoring()
        } else {
            // CRITICAL: Set isAnchored to false FIRST to stop any pending sensor callbacks
            // (The sensor listener checks this flag before applying updates)

            // Stop sensor updates immediately
            sensorManager.unregisterListener(sensorEventListener)

            // Wait a tiny bit for any in-flight Choreographer callbacks to complete
            // before resetting positions
            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                // Now reset view positions after sensor updates have stopped
                                dualWebViewGroup.stopAnchoring()

                                // Restore cursor position
                                refreshCursor()
                            },
                            50
                    ) // Small delay to ensure pending frames are processed
        }
    }

    private fun scheduleCursorUpdate() {
        if (!pendingCursorUpdate) {
            pendingCursorUpdate = true
            Choreographer.getInstance().postFrameCallback {
                pendingCursorUpdate = false
                refreshCursor()
            }
        }
    }

    /**
     * Updates the smoothing factors based on user preference slider (0-100) 0 = Fastest/least
     * smooth (high factor values = more responsive) 100 = Slowest/most smooth (low factor values =
     * very smooth) 80 = Default balanced setting
     */
    private fun updateSmoothnessFactors(level: Int) {
        smoothnessLevel = level.coerceIn(0, 100)

        // Map 0-100 to smoothing factors with INVERTED non-linear scaling
        // Higher slider values = LOWER factors = MORE smoothing
        // Lower slider values = HIGHER factors = LESS smoothing (more responsive)

        val invertedLevel = 100 - smoothnessLevel

        // Output damping: 0.55 (fast/left) to 0.05 (very smooth/right).
        // The raw sensor quaternion stays unsmoothed so the anchor target stops when head motion
        // stops instead of continuing to drift toward a lagging orientation estimate.
        velocitySmoothing = 0.05f + (invertedLevel / 100f) * 0.50f

        DebugLog.d(
                "SmoothnessDebug",
                """
            Smoothness updated:
            Level: $smoothnessLevel (0=fast, 100=smooth)
            Inverted: $invertedLevel
            Output Damping: $velocitySmoothing
        """.trimIndent()
        )
    }

    /** Public function called from DualWebViewGroup when user adjusts smoothness slider */
    fun updateAnchorSmoothness(level: Int) {
        updateSmoothnessFactors(level)
        // Preference is already saved by DualWebViewGroup, just update the factors
    }

    private fun createSensorEventListener(): SensorEventListener {
        return object : SensorEventListener {
            var initialQuaternion: FloatArray? = null

            val currentQuaternion = FloatArray(4)
            val inverseInitialQuaternion = FloatArray(4)
            val relativeQuaternion = FloatArray(4)
            val eulerAngles = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                if (!isAnchored || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                // Frame rate limiting to prevent excessive updates
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastFrameTime < MIN_FRAME_INTERVAL_MS) return
                lastFrameTime = currentTime

                SensorManager.getQuaternionFromVector(currentQuaternion, event.values)

                // Reset initial quaternion if requested
                if (shouldResetInitialQuaternion || initialQuaternion == null) {
                    initialQuaternion = currentQuaternion.clone()
                    shouldResetInitialQuaternion = false
                    // Reset velocity smoothing
                    smoothedDeltaX = 0f
                    smoothedDeltaY = 0f
                    smoothedRollDeg = 0f
                    lastAppliedAnchorX = Float.NaN
                    lastAppliedAnchorY = Float.NaN
                    lastAppliedAnchorRoll = Float.NaN
                    return
                }

                quaternionInverse(initialQuaternion!!, inverseInitialQuaternion)
                quaternionMultiply(inverseInitialQuaternion, currentQuaternion, relativeQuaternion)

                quaternionToEuler(relativeQuaternion, eulerAngles) // [x-axis, y-axis, z-axis]
                val xAxisRad = eulerAngles[0]
                val yAxisRad = eulerAngles[1]
                val zAxisRad = eulerAngles[2]
                val rollDeg = Math.toDegrees(zAxisRad.toDouble()).toFloat()

                // Convert relative head rotation to screen-space compensation using angular
                // offsets, not raw quaternion vector components. Raw components are only a
                // small-angle approximation and leak between axes as the user's head turns,
                // which makes the anchored image visibly wander.
                val angularTranslationScale = TRANSLATION_SCALE * 0.5f
                val deltaX = xAxisRad * angularTranslationScale
                val deltaY = yAxisRad * angularTranslationScale

                // Apply velocity smoothing (double exponential smoothing) using dynamic factor
                smoothedDeltaX =
                        smoothedDeltaX * (1f - velocitySmoothing) + deltaX * velocitySmoothing
                smoothedDeltaY =
                        smoothedDeltaY * (1f - velocitySmoothing) + deltaY * velocitySmoothing
                smoothedRollDeg =
                        smoothedRollDeg * (1f - velocitySmoothing) + rollDeg * velocitySmoothing

                // Coalesce sensor events to one pending vsync update. The newest smoothed values
                // are
                // stored above, so dropping intermediate callbacks reduces main-thread frame
                // pressure.
                if (!pendingAnchorFrame) {
                    pendingAnchorFrame = true
                    Choreographer.getInstance().postFrameCallback {
                        pendingAnchorFrame = false
                        // Double-check isAnchored before applying update (prevents race conditions)
                        if (isAnchored) {
                            val translationChanged =
                                    lastAppliedAnchorX.isNaN() ||
                                            abs(smoothedDeltaX - lastAppliedAnchorX) >=
                                                    ANCHOR_TRANSLATION_DEADBAND ||
                                            abs(smoothedDeltaY - lastAppliedAnchorY) >=
                                                    ANCHOR_TRANSLATION_DEADBAND
                            val rotationChanged =
                                    lastAppliedAnchorRoll.isNaN() ||
                                            abs(smoothedRollDeg - lastAppliedAnchorRoll) >=
                                                    ANCHOR_ROTATION_DEADBAND_DEG
                            if (!translationChanged && !rotationChanged) {
                                return@postFrameCallback
                            }

                            lastAppliedAnchorX = smoothedDeltaX
                            lastAppliedAnchorY = smoothedDeltaY
                            lastAppliedAnchorRoll = smoothedRollDeg
                            dualWebViewGroup.updateLeftEyePosition(
                                    smoothedDeltaX,
                                    smoothedDeltaY,
                                    smoothedRollDeg
                            )
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    fun quaternionToEuler(q: FloatArray, out: FloatArray): FloatArray {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]

        // roll (x-axis rotation)
        val sinrCosp = 2f * (w * x + y * z)
        val cosrCosp = 1f - 2f * (x * x + y * y)
        val roll = atan2(sinrCosp, cosrCosp)

        // pitch (y-axis rotation)
        val sinp = 2f * (w * y - z * x)
        val pitch =
                if (abs(sinp) >= 1f) {
                    // Use 90 degrees if out of range
                    PI.toFloat() / 2f * if (sinp > 0f) 1f else -1f
                } else {
                    asin(sinp)
                }

        // yaw (z-axis rotation)
        val sinyCosp = 2f * (w * z + x * y)
        val cosyCosp = 1f - 2f * (y * y + z * z)
        val yaw = atan2(sinyCosp, cosyCosp)

        out[0] = roll
        out[1] = pitch
        out[2] = yaw
        return out
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
        DebugLog.d(
                "DoubleTapDebug",
                """
            Toggle Cursor Visibility:
            Force Hide: $forceHide
            Force Show: $forceShow
            Previous Visible: $isCursorVisible
            isSimulating: $isSimulatingTouchEvent
            cursorJustAppeared: $cursorJustAppeared
            isToggling: $isToggling
        """.trimIndent()
        )

        synchronized(cursorToggleLock) {
            if (isToggling) return
            isToggling = true

            try {
                val previouslyVisible = isCursorVisible
                val targetVisibility =
                        when {
                            forceHide -> false
                            forceShow -> true
                            else -> !previouslyVisible
                        }

                // Early exit if already in the desired state (but still reset isToggling in
                // finally)
                if (targetVisibility == previouslyVisible) {
                    return
                }

                isCursorVisible = targetVisibility

                // Synchronise scroll mode with the cursor visibility state.
                dualWebViewGroup.setScrollMode(!isCursorVisible)

                if (isCursorVisible) {
                    cancelActiveTouchScrollGesture()

                    if (!isAnchored) {
                        lastCursorX = lastKnownCursorX
                        lastCursorY = lastKnownCursorY
                    } else {
                        lastCursorX = 320f
                        lastCursorY = 240f
                    }

                    cursorJustAppeared = true
                    // Block interactions briefly to prevent stale taps from firing as the cursor
                    // reappears.
                    // Note: removed isSimulatingTouchEvent = true as it causes OnTouchListener to
                    // ALLOW events through
                    Handler(Looper.getMainLooper()).postDelayed({ cursorJustAppeared = false }, 300)
                } else {
                    lastKnownCursorX = lastCursorX
                    lastKnownCursorY = lastCursorY

                    val webViewLocation = reusableLocation1
                    webView.getLocationOnScreen(webViewLocation)
                    lastKnownWebViewX = lastCursorX - webViewLocation[0]
                    lastKnownWebViewY = lastCursorY - webViewLocation[1]

                    webView.evaluateJavascript("window.toggleTouchEvents(false);", null)
                }

                refreshCursor()
            } finally {
                isToggling = false
            }
        }
    }

    override fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean) {
        val scale = dualWebViewGroup.uiScale
        val shouldRenderCursor = isVisible && !isMouseTapMode

        // Calculate visual position scaled around center (320, 240) and translated (only in
        // non-anchored mode)
        val transX = if (isAnchored) 0f else dualWebViewGroup.leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else dualWebViewGroup.leftEyeUIContainer.translationY

        val visualX = 320f + (x - 320f) * scale + transX
        val visualY = 240f + (y - 240f) * scale + transY

        // Logic to prevent "wrapping":
        // 1. Left cursor should ONLY be visible if it is within the left screen bounds (< 640)
        // 2. Right cursor (which is at visualX + 640) should ONLY be visible if visualX >= 0 (so
        // final x >= 640)
        // Note: We use a small buffer (e.g. -20 to 660) if we want to allow partial cursor
        // visibility at edges,
        // but strictly preventing wrapping means keeping it to the 640 boundary.

        val showLeft = shouldRenderCursor && visualX < 640f
        // The right eye is a PixelCopy mirror of the left-eye window region, so it already
        // receives the left cursor. Drawing a second native right-eye cursor causes duplicate
        // cursors when display size or placement makes the two projections diverge.
        val leftVisibility = if (showLeft) View.VISIBLE else View.GONE
        val rightVisibility = View.GONE

        val positionChanged =
                abs(visualX - lastRenderedCursorLeftX) >= 0.5f ||
                        abs(visualY - lastRenderedCursorLeftY) >= 0.5f
        val scaleChanged = abs(scale - lastRenderedCursorScale) >= 0.001f
        val visibilityChanged =
                leftVisibility != lastRenderedCursorLeftVisibility ||
                        rightVisibility != lastRenderedCursorRightVisibility

        if (!positionChanged && !scaleChanged && !visibilityChanged) {
            return
        }

        if (cursorLeftView.pivotX != 0f) cursorLeftView.pivotX = 0f
        if (cursorLeftView.pivotY != 0f) cursorLeftView.pivotY = 0f
        if (cursorRightView.pivotX != 0f) cursorRightView.pivotX = 0f
        if (cursorRightView.pivotY != 0f) cursorRightView.pivotY = 0f

        if (positionChanged) {
            cursorLeftView.x = visualX
            cursorLeftView.y = visualY
            cursorRightView.x = visualX + 640
            cursorRightView.y = visualY
        }

        if (scaleChanged) {
            cursorLeftView.scaleX = scale
            cursorLeftView.scaleY = scale
            cursorRightView.scaleX = scale
            cursorRightView.scaleY = scale
        }

        if (cursorLeftView.visibility != leftVisibility) {
            cursorLeftView.visibility = leftVisibility
        }
        if (cursorRightView.visibility != rightVisibility) {
            cursorRightView.visibility = rightVisibility
        }

        lastRenderedCursorLeftX = visualX
        lastRenderedCursorLeftY = visualY
        lastRenderedCursorScale = scale
        lastRenderedCursorLeftVisibility = leftVisibility
        lastRenderedCursorRightVisibility = rightVisibility
    }

    override fun onKeyPressed(key: String) {
        DebugLog.d("LinkEditing", "onKeyPressed called with: $key")
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
                val newText = StringBuilder(currentText).insert(cursorPosition, key).toString()

                // Set text and move cursor after inserted character
                dualWebViewGroup.setLinkText(newText, cursorPosition + 1)
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart
                val newText = StringBuilder(currentText).insert(cursorPosition, key).toString()
                input.setText(newText)
                input.setSelection(cursorPosition + 1)
            }
            else -> {
                sendCharacterToWebView(key)
            }
        }
    }

    private fun handleUserInteraction() {
        if (isCursorVisible && !isKeyboardVisible) {}
    }

    override fun onBackspacePressed() {
        DebugLog.d("LinkEditing", "onBackspacePressed called")
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
                    val newText =
                            StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()

                    // Set text and move cursor to position before deleted character
                    dualWebViewGroup.setLinkText(newText, cursorPosition - 1)
                }
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart
                if (cursorPosition > 0) {
                    val newText =
                            StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()
                    input.setText(newText)
                    input.setSelection(cursorPosition - 1)
                }
            }
            else -> {
                sendBackspaceToWebView()
            }
        }
    }

    override fun onEnterPressed() {
        isKeyboardVisible = false // if enter is pressed keyboard is no longer visible
        if (isUrlEditing) {

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
            dualWebViewGroup.getDialogInput() != null -> {
                // If in dialog input, enter might mean confirm, or just hide keyboard?
                // Usually OK button handles the confirm.
                // Let's just hide keyboard for now or do nothing.
                hideCustomKeyboard()
            }
            // Otherwise handle regular keyboard input
            else -> {
                sendEnterToWebView()
            }
        }
    }

    override fun onHideKeyboard() {
        suppressWebClickUntil = SystemClock.uptimeMillis() + 250
        if (dualWebViewGroup.isBookmarkEditing()) {
            dualWebViewGroup.hideBookmarkEditing()
        }
        hideCustomKeyboard()
    }

    override fun onRefreshPressed() {
        val currentUrl = webView.url
        webView.evaluateJavascript(
                """
            (function() {
                const injectedStyles = document.querySelectorAll('style[data-injected="true"]');
                injectedStyles.forEach(style => style.remove());
                
                let viewport = document.querySelector('meta[name="viewport"]');
                if (viewport) {
                    viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0';
                }
            })();
        """,
                null
        )

        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            if (currentUrl != null) {
                                webView.loadUrl(currentUrl)
                            } else {
                                webView.loadUrl(Constants.DEFAULT_URL)
                            }
                        },
                        50
                )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    dualWebViewGroup.toggleMediaPlayback()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    dualWebViewGroup.playMedia()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    dualWebViewGroup.pauseMedia()
                    return true
                }
            }
        }
        if (metaModeEnabled && isMetaNavigationKey(event.keyCode)) {
            val keyName = metaKeyNameForKeyCode(event.keyCode)
            if (keyName != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    dispatchMetaKeyToWebView(keyName)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val deviceName = ev.device?.name ?: InputDevice.getDevice(ev.deviceId)?.name
        if (deviceName?.contains("cyttsp5_mt", ignoreCase = true) == true) {
            ensureMouseTapModeDisabled()
        }

        // Temple arm input is normally reserved for mode-toggle double taps; when the companion
        // app has Meta mode active, any temple touch exits Meta mode and returns to Trackpad.
        if (ev.device?.name == "cyttsp6_mt") {
            if (isMetaNavigationActive() && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // Exit meta mode: switch companion and main app to trackpad
                remoteControllerMode = ControllerMode.TRACKPAD
                ensureMouseTapModeDisabled()
                if (::cursorController.isInitialized) {
                    cursorController.onControllerModeChanged(ControllerMode.TRACKPAD)
                }
                applyMetaModeCursorVisibility()
                if (::controllerBluetoothClient.isInitialized) {
                    controllerBluetoothClient.sendMode(ControllerMode.TRACKPAD)
                }
                if (::dualWebViewGroup.isInitialized) {
                    dualWebViewGroup.setNavBarsHidden(false)
                    dualWebViewGroup.showToast("Exited meta mode", 1200L)
                }
                return true
            }
            // Consume remaining move/up events of the exit gesture
            if (isMetaNavigationActive()) {
                return true
            }
            templeDoubleTapDetector.onTouchEvent(ev)
            return true
        }

        autoEnterMouseModeForMudraInput(ev)

        val isMouseEvent = isMousePointerEvent(ev)

        if (!isMouseEvent && handleMetaTouchEvent(ev)) {
            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.noteUserInteraction()
            }
            return true
        }

        // Track state at start of touch to prevent double-dispatch issues
        if (ev.action == MotionEvent.ACTION_DOWN) {
            wasTouchOnBookmarks = false
            wasTouchOnKeyboard = false
            wasKeyboardVisibleAtDown = isKeyboardVisible

            if (::dualWebViewGroup.isInitialized) {
                // In anchored mode, use the eye center (look-to-click) for coordinate checks
                // In non-anchored mode, use the raw touch coordinates
                val checkX: Float
                val checkY: Float

                if (isAnchored) {
                    val groupLocation = reusableLocation1
                    dualWebViewGroup.getLocationOnScreen(groupLocation)
                    checkX = 320f + groupLocation[0]
                    checkY = 240f + groupLocation[1]
                } else {
                    // In non-anchored mode, check if the CURSOR (not the touch) is over the
                    // bookmarks
                    // This matches the logic in dispatchTouchEventAtCursor
                    val scale = dualWebViewGroup.uiScale
                    val transX = dualWebViewGroup.leftEyeUIContainer.translationX
                    val transY = dualWebViewGroup.leftEyeUIContainer.translationY

                    val visualX = 320f + (lastCursorX - 320f) * scale + transX
                    val visualY = 240f + (lastCursorY - 240f) * scale + transY

                    val groupLocation = reusableLocation1
                    dualWebViewGroup.getLocationOnScreen(groupLocation)

                    checkX = visualX + groupLocation[0]
                    checkY = visualY + groupLocation[1]
                }

                if (dualWebViewGroup.isPointInBookmarks(checkX, checkY)) {
                    wasTouchOnBookmarks = true
                }
                if (dualWebViewGroup.isPointInKeyboard(checkX, checkY)) {
                    wasTouchOnKeyboard = true
                }
            }
        }

        // Let gestureDetector see the event for global gestures (like double-tap back)
        // regardless of whether a child view consumes it.
        if (!isMouseEvent) {
            isDispatchingTouchEvent = true
            try {
                isGestureHandled = gestureDetector.onTouchEvent(ev)
            } finally {
                isDispatchingTouchEvent = false
            }
        } else {
            val mousePoint = resolveMouseScreenPoint(ev)
            val rawX = mousePoint.first
            val rawY = mousePoint.second
            val mappedPoint = mapMousePointForVirtualTap(rawX, rawY)
            val mappedX = mappedPoint.first
            val mappedY = mappedPoint.second
            val usedRightEyeMapping = isMouseTapMode && mappedX != rawX

            lastMouseRawX = rawX
            lastMouseRawY = rawY
            lastMouseMappedX = mappedX
            lastMouseMappedY = mappedY

            val useMouseForGestures = !isMouseTapMode
            if (useMouseForGestures) {
                val gestureAction =
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_BUTTON_PRESS -> MotionEvent.ACTION_DOWN
                            MotionEvent.ACTION_BUTTON_RELEASE -> MotionEvent.ACTION_UP
                            else -> ev.actionMasked
                        }

                val shouldSendToGestureDetector =
                        gestureAction == MotionEvent.ACTION_DOWN ||
                                gestureAction == MotionEvent.ACTION_MOVE ||
                                gestureAction == MotionEvent.ACTION_UP ||
                                gestureAction == MotionEvent.ACTION_CANCEL

                isDispatchingTouchEvent = true
                try {
                    if (shouldSendToGestureDetector) {
                        val injectDownBeforeUp =
                                gestureAction == MotionEvent.ACTION_UP && !mouseGestureActive

                        if (injectDownBeforeUp) {
                            val syntheticDownTime =
                                    if (ev.downTime > 0L && ev.downTime <= ev.eventTime) ev.downTime
                                    else ev.eventTime
                            val syntheticDown =
                                    MotionEvent.obtain(
                                            syntheticDownTime,
                                            syntheticDownTime,
                                            MotionEvent.ACTION_DOWN,
                                            mappedX,
                                            mappedY,
                                            ev.metaState
                                    )
                            syntheticDown.source = InputDevice.SOURCE_TOUCHSCREEN
                            try {
                                gestureDetector.onTouchEvent(syntheticDown)
                            } finally {
                                syntheticDown.recycle()
                            }
                            mouseGestureDownTime = syntheticDownTime
                            mouseGestureActive = true
                        }

                        if (gestureAction == MotionEvent.ACTION_DOWN) {
                            mouseGestureDownTime = ev.eventTime
                            mouseGestureActive = true
                        }

                        val gestureDownTime =
                                if (gestureAction == MotionEvent.ACTION_DOWN) ev.eventTime
                                else if (mouseGestureActive) mouseGestureDownTime else ev.downTime

                        val gestureEvent =
                                MotionEvent.obtain(
                                        gestureDownTime,
                                        ev.eventTime,
                                        gestureAction,
                                        mappedX,
                                        mappedY,
                                        ev.metaState
                                )
                        gestureEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                        try {
                            isGestureHandled = gestureDetector.onTouchEvent(gestureEvent)
                        } finally {
                            gestureEvent.recycle()
                        }

                        if (gestureAction == MotionEvent.ACTION_UP ||
                                        gestureAction == MotionEvent.ACTION_CANCEL
                        ) {
                            mouseGestureActive = false
                        }
                    } else {
                        isGestureHandled = false
                    }
                } finally {
                    isDispatchingTouchEvent = false
                }
            } else {
                isGestureHandled = false
            }

            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    if (::dualWebViewGroup.isInitialized) {
                        dualWebViewGroup.updatePointerHover(mappedX, mappedY)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_CANCEL -> {
                    if (::dualWebViewGroup.isInitialized) {
                        dualWebViewGroup.clearPointerHover()
                    }
                }
            }

            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                            ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                if (!useMouseForGestures) {
                    val dragSlop = 10f
                    val movedSinceDown =
                            kotlin.math.abs(mappedX - mouseSwipeStartX) >= dragSlop ||
                                    kotlin.math.abs(mappedY - mouseSwipeStartY) >= dragSlop
                    val longPressLike =
                            mouseSwipeTracking &&
                                    (ev.eventTime - mouseSwipeDownTime) >= 120L &&
                                    !mouseSwipeStartedOnCustomUi
                    val dragLikeRelease = movedSinceDown || longPressLike

                    if (mouseSwipeDownDispatched) {
                        dispatchWebTouchFromScreen(
                                MotionEvent.ACTION_CANCEL,
                                mappedX,
                                mappedY,
                                ev.eventTime
                        )
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }

                    if (dragLikeRelease && !mouseSwipeStartedOnCustomUi) {
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }

                    if (handleMouseClickForCustomUi(mappedX, mappedY)) {
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }
                    if (usedRightEyeMapping) {
                        dispatchWebTapAtScreenCoordinates(mappedX, mappedY)
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }
                    maybeShowKeyboardForMouseClick(mappedX, mappedY)
                    mouseSwipeTracking = false
                    mouseSwipeStartedOnCustomUi = false
                    mouseSwipeDownDispatched = false
                }
            }

            if (!useMouseForGestures) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                        mouseSwipeTracking = true
                        mouseSwipeStartedOnCustomUi = isPointOnCustomUi(mappedX, mappedY)
                        mouseSwipeDownDispatched = false
                        mouseSwipeStartX = mappedX
                        mouseSwipeStartY = mappedY
                        mouseSwipeLastX = mappedX
                        mouseSwipeLastY = mappedY
                        mouseSwipeDownTime = ev.eventTime
                    }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                        if (mouseSwipeTracking && !mouseSwipeStartedOnCustomUi) {
                            val dragSlop = 10f
                            val movedEnough =
                                    kotlin.math.abs(mappedX - mouseSwipeStartX) >= dragSlop ||
                                            kotlin.math.abs(mappedY - mouseSwipeStartY) >= dragSlop

                            if (!mouseSwipeDownDispatched && movedEnough) {
                                mouseSwipeDownDispatched =
                                        dispatchWebTouchFromScreen(
                                                MotionEvent.ACTION_DOWN,
                                                mouseSwipeStartX,
                                                mouseSwipeStartY,
                                                mouseSwipeDownTime,
                                                mouseSwipeDownTime
                                        )
                            }

                            if (mouseSwipeDownDispatched) {
                                dispatchWebTouchFromScreen(
                                        MotionEvent.ACTION_MOVE,
                                        mappedX,
                                        mappedY,
                                        ev.eventTime,
                                        mouseSwipeDownTime
                                )
                                mouseSwipeLastX = mappedX
                                mouseSwipeLastY = mappedY
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (mouseSwipeDownDispatched) {
                            dispatchWebTouchFromScreen(
                                    MotionEvent.ACTION_CANCEL,
                                    mouseSwipeLastX,
                                    mouseSwipeLastY,
                                    ev.eventTime,
                                    mouseSwipeDownTime
                            )
                        }
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                    }
                }
            }
        }

        // Reset idle timer on any touch to restore full refresh rate
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.noteUserInteraction()
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        autoEnterMouseModeForMudraInput(ev)

        if (isMousePointerEvent(ev) && ::dualWebViewGroup.isInitialized) {
            val mousePoint = resolveMouseScreenPoint(ev)
            val rawX = mousePoint.first
            val rawY = mousePoint.second
            val mappedPoint = mapMousePointForVirtualTap(rawX, rawY)
            val mappedX = mappedPoint.first
            val mappedY = mappedPoint.second

            lastMouseRawX = rawX
            lastMouseRawY = rawY
            lastMouseMappedX = mappedX
            lastMouseMappedY = mappedY

            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    dualWebViewGroup.updatePointerHover(mappedX, mappedY)
                }
                MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_CANCEL -> {
                    dualWebViewGroup.clearPointerHover()
                }
            }
        }

        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        autoEnterMouseModeForMudraInput(event)

        // Use the result captured in dispatchTouchEvent to avoid calling it twice
        val handled = isGestureHandled

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            // triple click menu fling logic was here
        }

        // If gesture detector handled it, consume the event
        return handled || super.onTouchEvent(event)
    }

    // In the implementation of NavigationListener
    override fun onHomePressed() {
        val homeUrl = dualWebViewGroup.getBookmarksView().getHomeUrl()
        webView.loadUrl(homeUrl)
    }

    // In the implementation of NavigationListener
    override fun onSettingsPressed() {
        DebugLog.d("Navigation", "Settings pressed")
        dualWebViewGroup.showSettings()
    }

    // Add the navigation interface implementations
    override fun onNavigationBackPressed() {
        val historyList = webView.copyBackForwardList()
        val canGoBack = webView.canGoBack()

        DebugLog.d(
                "NavigationDebug",
                """
            Back pressed:
            Current URL: ${webView.url}
            Can go back: $canGoBack
            History size: ${historyList.size}
        """.trimIndent()
        )

        if (!canGoBack) {
            DebugLog.d("NavigationDebug", "No history entry available for goBack()")
            return
        }

        dualWebViewGroup.updateLoadingProgress(0)

        if (historyList.size > 1) {
            historyList.getItemAtIndex(historyList.size - 2).url.also {
                DebugLog.d("NavigationDebug", "Attempting to go back to: $it")
            }
        } else {
            DebugLog.d("NavigationDebug", "History stack did not expose a previous URL")
        }

        // First, stop all JavaScript execution and ongoing loads
        webView.evaluateJavascript("window.stop();", null)
        webView.stopLoading()

        // Clear all JavaScript intervals and timeouts
        webView.evaluateJavascript(
                """
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
            """.trimIndent(),
                null
        )

        // Keep JavaScript enabled and go back
        webView.goBack()
        webView.invalidate()
        dualWebViewGroup.invalidate()
    }

    override fun onQuitPressed() {
        finish()
    }

    override fun onControllerConnected(name: String, address: String) {
        runOnUiThread {
            isControllerConnected = true
            DebugLog.d("ControllerInput", "Controller connected: $name from $address")
            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.showToast("Controller connected: $name", 1500L)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Sync Groq API Key to controller if we have one saved
            val savedGroqKey =
                    getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(Constants.KEY_GROQ_API_KEY, "")
            if (!savedGroqKey.isNullOrBlank()) {
                controllerBluetoothClient.sendGroqApiKey(savedGroqKey)
            }

            if (::controllerNetworkInputServer.isInitialized) {
                controllerBluetoothClient.sendNetworkEndpoint(
                        ControllerNetworkInputServer.GLASSES_INPUT_PORT,
                        controllerNetworkInputServer.localIpv4Addresses()
                )
            }
        }
    }

    override fun onControllerDisconnected() {
        runOnUiThread {
            isControllerConnected = false
            isPhoneKeyboardOpen = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.setNavBarsHidden(false)
            }
        }
    }

    override fun onControllerModeChanged(mode: ControllerMode) {
        runOnUiThread {
            if (isFirstModeCheck) {
                isFirstModeCheck = false
                if (mode == ControllerMode.META) {
                    if (::controllerBluetoothClient.isInitialized) {
                        controllerBluetoothClient.sendMode(ControllerMode.TRACKPAD)
                    }
                    remoteControllerMode = ControllerMode.TRACKPAD
                    ensureMouseTapModeDisabled()
                    if (::cursorController.isInitialized) {
                        cursorController.onControllerModeChanged(ControllerMode.TRACKPAD)
                    }
                    applyMetaModeCursorVisibility()
                    if (::dualWebViewGroup.isInitialized) {
                        dualWebViewGroup.setNavBarsHidden(false)
                        dualWebViewGroup.showToast("Reverting to trackpad mode on launch", 1500L)
                    }
                    return@runOnUiThread
                }
            }

            remoteControllerMode = mode
            ensureMouseTapModeDisabled()
            if (::cursorController.isInitialized) {
                cursorController.onControllerModeChanged(mode)
            }
            applyMetaModeCursorVisibility()
            if (::dualWebViewGroup.isInitialized) {
                if (mode == ControllerMode.META) {
                    dualWebViewGroup.setNavBarsHidden(true)
                } else {
                    dualWebViewGroup.setNavBarsHidden(false)
                }
            }
            dualWebViewGroup.showToast(
                    when (mode) {
                        ControllerMode.AIR_MOUSE -> "Controller air mouse"
                        ControllerMode.TRACKPAD -> "Controller trackpad"
                        ControllerMode.META -> "Controller meta mode"
                    },
                    1200L
            )
        }
    }

    override fun onControllerKey(key: String) {
        runOnUiThread {
            when (key) {
                "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter" ->
                        dispatchMetaKeyToWebView(key)
                "backspace" -> onBackspacePressed()
                "enter" -> onEnterPressed()
                "hideKeyboard" -> {
                    isPhoneKeyboardOpen = false
                    controllerBluetoothClient.sendKeyboardVisibility(false)
                }
                "toggleMask" -> handleMaskToggle()
                "zoomIn" -> dualWebViewGroup.handleZoomButtonClick("in")
                "zoomOut" -> dualWebViewGroup.handleZoomButtonClick("out")
                else -> if (key.isNotEmpty()) onKeyPressed(key)
            }
        }
    }

    override fun onControllerGroqApiKey(key: String) {
        runOnUiThread {
            val trimmed = key.trim()
            if (trimmed.isBlank()) {
                dualWebViewGroup.showToast("Groq API key was empty", 1500L)
                return@runOnUiThread
            }

            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(Constants.KEY_GROQ_API_KEY, trimmed)
                    .apply()
            if (groqAudioService == null) {
                initializeGroqService()
            }
            groqAudioService?.setApiKey(trimmed)
            dualWebViewGroup.showToast("Groq API key saved from phone", 1500L)
        }
    }

    override fun onControllerAiPrompt(prompt: String) {
        runOnUiThread {
            if (!::dualWebViewGroup.isInitialized) return@runOnUiThread
            dualWebViewGroup.showChat()
            dualWebViewGroup.postDelayed(
                    {
                        dualWebViewGroup.sendTextToChatInput(prompt)
                        dualWebViewGroup.postDelayed(
                                { dualWebViewGroup.sendEnterToChatInput() },
                                100
                        )
                    },
                    100
            )
        }
    }

    override fun onControllerAirMouseRay(x: Float, y: Float, select: Boolean) {
        if (::cursorController.isInitialized) {
            cursorController.onControllerAirMouseRay(x, y, select)
        }
    }

    override fun onControllerScroll(dx: Float, dy: Float) {
        runOnUiThread {
            if (!::dualWebViewGroup.isInitialized || !::webView.isInitialized) return@runOnUiThread
            if (metaModeEnabled || remoteControllerMode == ControllerMode.META) {
                if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    dispatchMetaKeyToWebView(if (dy > 0f) "ArrowDown" else "ArrowUp")
                } else if (dx != 0f) {
                    dispatchMetaKeyToWebView(if (dx > 0f) "ArrowRight" else "ArrowLeft")
                }
                return@runOnUiThread
            }

            // Dispatch mouse wheel scroll event
            val pointerCoords = MotionEvent.PointerCoords()
            pointerCoords.x = lastCursorX
            pointerCoords.y = lastCursorY
            // Map positive dy (drag down) to scroll down (content moves up). AXIS_VSCROLL negative
            // means scroll down.
            pointerCoords.setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / 30f)
            pointerCoords.setAxisValue(MotionEvent.AXIS_HSCROLL, -dx / 30f)

            val pointerProperties = MotionEvent.PointerProperties()
            pointerProperties.id = 0
            pointerProperties.toolType = MotionEvent.TOOL_TYPE_MOUSE

            val event =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_SCROLL,
                            1,
                            arrayOf(pointerProperties),
                            arrayOf(pointerCoords),
                            0,
                            0,
                            1.0f,
                            1.0f,
                            0,
                            0,
                            InputDevice.SOURCE_MOUSE,
                            0
                    )

            webView.dispatchGenericMotionEvent(event)
            event.recycle()
        }
    }

    override fun onControllerTrackpadDelta(dx: Float, dy: Float) {
        if (::cursorController.isInitialized) {
            cursorController.onControllerTrackpadDelta(dx, dy)
        }
    }

    override fun onControllerTrackpadGesture(
            action: ControllerTrackpadAction,
            dx: Float,
            dy: Float,
            pointerCount: Int
    ) {
        if (metaModeEnabled || remoteControllerMode == ControllerMode.META) {
            handleMetaTrackpadGesture(action, dx, dy, pointerCount)
            return
        }
        if (::cursorController.isInitialized) {
            cursorController.onControllerTrackpadGesture(action, dx, dy, pointerCount)
        }
    }

    override fun onControllerTap() {
        runOnUiThread {
            if (!::dualWebViewGroup.isInitialized) return@runOnUiThread
            if (metaModeEnabled || remoteControllerMode == ControllerMode.META) {
                dispatchMetaKeyToWebView("Enter")
                dualWebViewGroup.noteUserInteraction()
                return@runOnUiThread
            }

            // Route through the same triple-tap detection as the temple touchpad
            val currentTime = SystemClock.uptimeMillis()
            if (currentTime - lastTapTime > TAP_INTERVAL) {
                tapCount = 1
                firstTapTime = currentTime
                isTripleTapInProgress = false
            } else {
                tapCount++
            }
            lastTapTime = currentTime

            // Check for triple tap
            if (tapCount == 3 && (currentTime - firstTapTime) <= TRIPLE_TAP_DURATION) {
                DebugLog.d("TripleTapDebug", "Triple tap from controller detected!")
                isTripleTapInProgress = true
                tapCount = 0

                if (isAnchored) {
                    shouldResetInitialQuaternion = true
                    dualWebViewGroup.updateLeftEyePosition(0f, 0f, 0f)
                    dualWebViewGroup.showToast("Screen Re-centered")
                } else {
                    if (dualWebViewGroup.isInScrollMode()) {
                        toggleCursorVisibility(forceShow = true)
                        dualWebViewGroup.showToast("Cursor mode activated")
                    } else {
                        toggleCursorVisibility(forceHide = true)
                        dualWebViewGroup.showToast(
                                "Scroll mode activated, triple tap again to leave"
                        )
                    }
                }
                return@runOnUiThread
            }

            // Not a triple tap — dispatch click at cursor
            if (!isTripleTapInProgress) {
                dispatchTouchEventAtCursor()
            }
            isTripleTapInProgress = false
            dualWebViewGroup.noteUserInteraction()
        }
    }

    override fun onControllerTouch(action: ControllerTouchAction, x: Float, y: Float) {
        runOnUiThread {
            if (!::dualWebViewGroup.isInitialized || !::webView.isInitialized) return@runOnUiThread
            val screenPoint = controllerNormalizedToScreen(x, y)
            val motionAction =
                    when (action) {
                        ControllerTouchAction.DOWN -> MotionEvent.ACTION_DOWN
                        ControllerTouchAction.MOVE -> MotionEvent.ACTION_MOVE
                        ControllerTouchAction.UP -> MotionEvent.ACTION_UP
                        ControllerTouchAction.CANCEL -> MotionEvent.ACTION_CANCEL
                    }
            val eventTime = SystemClock.uptimeMillis()
            if (motionAction == MotionEvent.ACTION_DOWN) {
                controllerTouchDownTime = eventTime
            }
            val downTime = if (controllerTouchDownTime > 0L) controllerTouchDownTime else eventTime

            if (motionAction == MotionEvent.ACTION_UP &&
                            handleMouseClickForCustomUi(screenPoint.first, screenPoint.second)
            ) {
                controllerTouchDownTime = 0L
                return@runOnUiThread
            }

            dispatchWebTouchFromScreen(
                    motionAction,
                    screenPoint.first,
                    screenPoint.second,
                    eventTime,
                    downTime
            )
            if (motionAction == MotionEvent.ACTION_UP) {
                maybeShowKeyboardForMouseClick(screenPoint.first, screenPoint.second)
            }
            if (motionAction == MotionEvent.ACTION_UP || motionAction == MotionEvent.ACTION_CANCEL
            ) {
                controllerTouchDownTime = 0L
            }
            dualWebViewGroup.noteUserInteraction()
        }
    }

    fun setMetaModeEnabled(enabled: Boolean) {
        if (metaModeEnabled == enabled) return
        metaModeEnabled = enabled
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_META_MODE_ENABLED, enabled)
                .apply()
        applyMetaModeCursorVisibility()
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.showToast(
                    if (enabled) "Meta mode enabled: swipes send arrows, taps send Enter"
                    else "Meta mode disabled",
                    1800L
            )
        }
    }

    fun isMetaModeEnabled(): Boolean = metaModeEnabled

    private fun isMetaNavigationActive(): Boolean =
            metaModeEnabled || remoteControllerMode == ControllerMode.META

    private fun applyMetaModeCursorVisibility() {
        if (!::dualWebViewGroup.isInitialized) return
        if (isMetaNavigationActive()) {
            if (savedCursorVisibleBeforeMetaMode == null) {
                savedCursorVisibleBeforeMetaMode = isCursorVisible
            }
            refreshCursor(false)
        } else {
            val shouldRestoreCursor = savedCursorVisibleBeforeMetaMode ?: return
            savedCursorVisibleBeforeMetaMode = null
            refreshCursor(shouldRestoreCursor)
        }
    }

    private fun handleMetaTrackpadGesture(
            action: ControllerTrackpadAction,
            dx: Float,
            dy: Float,
            pointerCount: Int
    ) {
        runOnUiThread {
            if (pointerCount > 1) return@runOnUiThread
            when (action) {
                ControllerTrackpadAction.DOWN -> {
                    metaGestureStartX = 0f
                    metaGestureStartY = 0f
                    metaGestureLastX = 0f
                    metaGestureLastY = 0f
                    metaGestureTotalDistance = 0f
                }
                ControllerTrackpadAction.MOVE -> {
                    metaGestureLastX += dx
                    metaGestureLastY += dy
                    metaGestureTotalDistance += kotlin.math.sqrt(dx * dx + dy * dy)
                }
                ControllerTrackpadAction.UP -> {
                    val key = metaKeyForGesture(metaGestureLastX, metaGestureLastY, metaGestureTotalDistance)
                    dispatchMetaKeyToWebView(key)
                    metaGestureTotalDistance = 0f
                }
                ControllerTrackpadAction.CANCEL -> metaGestureTotalDistance = 0f
                ControllerTrackpadAction.POINTER -> Unit
            }
        }
    }

    private fun handleMetaTouchEvent(ev: MotionEvent): Boolean {
        if (!metaModeEnabled || isPointOnCustomUi(ev.rawX, ev.rawY)) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                metaGestureStartX = ev.rawX
                metaGestureStartY = ev.rawY
                metaGestureLastX = ev.rawX
                metaGestureLastY = ev.rawY
                metaGestureTotalDistance = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - metaGestureLastX
                val dy = ev.rawY - metaGestureLastY
                metaGestureTotalDistance += kotlin.math.sqrt(dx * dx + dy * dy)
                metaGestureLastX = ev.rawX
                metaGestureLastY = ev.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                val key = metaKeyForGesture(
                        ev.rawX - metaGestureStartX,
                        ev.rawY - metaGestureStartY,
                        metaGestureTotalDistance
                )
                dispatchMetaKeyToWebView(key)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun metaKeyForGesture(dx: Float, dy: Float, totalDistance: Float): String {
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val swipeThreshold = 36f
        return if (distance < swipeThreshold && totalDistance < swipeThreshold * 1.5f) {
            "Enter"
        } else if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            if (dx < 0f) "ArrowLeft" else "ArrowRight"
        } else {
            if (dy < 0f) "ArrowUp" else "ArrowDown"
        }
    }

    private fun dispatchMetaKeyToWebView(keyName: String) {
        if (!::webView.isInitialized) return
        val keyCode =
                when (keyName) {
                    "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP
                    "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN
                    "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT
                    "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT
                    "Enter" -> KeyEvent.KEYCODE_ENTER
                    else -> return
                }
        val now = SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))

        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.noteUserInteraction()
        }
    }

    private fun isMetaNavigationKey(keyCode: Int): Boolean =
            metaKeyNameForKeyCode(keyCode) != null

    private fun metaKeyNameForKeyCode(keyCode: Int): String? =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
                KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
                KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> "Enter"
                else -> null
            }

    private fun maybeShowGlassAppsMetaModeToast(url: String?) {
        val host = runCatching { url?.let { Uri.parse(it).host } }.getOrNull() ?: return
        if (!host.equals("glassapps.io", ignoreCase = true) &&
                        !host.endsWith(".glassapps.io", ignoreCase = true)
        ) {
            return
        }
        if (glassAppsMetaToastShownForUrl == url) return
        glassAppsMetaToastShownForUrl = url
        dualWebViewGroup.showToast(
                if (metaModeEnabled) "Meta mode is enabled for Glass Apps"
                else "For maximum compatibility on Glass Apps, enable Meta mode in Settings.",
                3500L
        )
    }

    private fun controllerNormalizedToScreen(x: Float, y: Float): Pair<Float, Float> {
        val groupLocation = reusableLocation1
        dualWebViewGroup.getLocationOnScreen(groupLocation)
        return groupLocation[0] + x * CONTROLLER_CURSOR_WIDTH to
                groupLocation[1] + y * CONTROLLER_CURSOR_HEIGHT
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controllerBluetoothClient.isInitialized) {
            controllerBluetoothClient.stop()
        }
        if (::controllerNetworkInputServer.isInitialized) {
            controllerNetworkInputServer.stop()
        }
        pendingActiveStateSave?.let { handler.removeCallbacks(it) }
        pendingActiveStateSave = null
        pendingActiveStateWebView = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        cameraDevice?.close()
        imageReader?.close()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        sensorManager.unregisterListener(sensorEventListener)
        stopGpsUpdates()
    }

    override fun onStop() {
        super.onStop()

        // Save all windows state
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.flushPendingWindowsStateSave(force = true)
        }

        // Persist active state on stop as a final snapshot.
        flushPendingActiveWebViewState("onStop")
        stopGpsUpdates()
    }

    private fun schedulePersistActiveWebViewState(
            reason: String,
            activeView: WebView? = webView,
            delayMs: Long = ACTIVE_STATE_SAVE_DEBOUNCE_MS
    ) {
        val targetView = activeView ?: return
        val currentUrl = targetView.url
        if (currentUrl.isNullOrBlank() || currentUrl.startsWith("about:blank")) {
            return
        }

        persistActiveUrl(reason, currentUrl, targetView)

        pendingActiveStateSave?.let { handler.removeCallbacks(it) }
        pendingActiveStateWebView = targetView
        pendingActiveStateReason = reason
        pendingActiveStateSave = Runnable {
            val viewToSave = pendingActiveStateWebView
            val saveReason = pendingActiveStateReason
            pendingActiveStateSave = null
            pendingActiveStateWebView = null
            persistActiveWebViewStateNow(saveReason, viewToSave)
        }
        handler.postDelayed(pendingActiveStateSave!!, delayMs)
    }

    private fun flushPendingActiveWebViewState(reason: String) {
        val pendingSave = pendingActiveStateSave
        val viewToSave = pendingActiveStateWebView ?: if (::webView.isInitialized) webView else null
        pendingActiveStateSave = null
        pendingActiveStateWebView = null
        if (pendingSave != null) {
            handler.removeCallbacks(pendingSave)
        }
        persistActiveWebViewStateNow(reason, viewToSave)
    }

    private fun persistActiveWebViewStateNow(reason: String, activeView: WebView? = webView) {
        if (!::dualWebViewGroup.isInitialized) {
            return
        }

        val targetView = activeView ?: return
        if (!dualWebViewGroup.isActiveWebView(targetView)) {
            return
        }

        val currentUrl = targetView.url
        if (currentUrl.isNullOrBlank() || currentUrl.startsWith("about:blank")) {
            return
        }

        DebugLog.d("WebViewDebug") { "Persisting active state ($reason): $currentUrl" }

        getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putString(keyLastUrl, currentUrl)
                .apply()
        lastUrl = currentUrl

        try {
            val webViewState = Bundle()
            targetView.saveState(webViewState)

            val parcel = Parcel.obtain()
            val serializedState =
                    try {
                        webViewState.writeToParcel(parcel, 0)
                        Base64.encodeToString(parcel.marshall(), Base64.DEFAULT)
                    } finally {
                        parcel.recycle()
                    }

            getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                putString(Constants.KEY_WEBVIEW_STATE, serializedState)
            }

            DebugLog.d("WebViewDebug") { "WebView state persisted successfully ($reason)" }
        } catch (e: Exception) {
            DebugLog.e("WebViewDebug", "Error persisting WebView state ($reason)", e)
        }
    }

    private fun persistActiveUrl(reason: String, url: String, activeView: WebView? = webView) {
        if (!::dualWebViewGroup.isInitialized) {
            return
        }

        val targetView = activeView ?: return
        if (!dualWebViewGroup.isActiveWebView(targetView)) {
            return
        }

        if (url.startsWith("about:blank")) {
            return
        }

        DebugLog.d("WebViewDebug") { "Persisting last URL ($reason): $url" }
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString(keyLastUrl, url).apply()
        lastUrl = url
    }

    // Add JavaScript interface to reset capturing state
    class AndroidInterface(private val activity: MainActivity, private val webView: WebView) {
        @JavascriptInterface
        fun onScrollMetrics(
                rangeX: Double,
                extentX: Double,
                offsetX: Double,
                rangeY: Double,
                extentY: Double,
                offsetY: Double
        ) {
            if (!activity.dualWebViewGroup.isActiveWebView(webView)) {
                return
            }
            activity.runOnUiThread {
                activity.dualWebViewGroup.updateExternalScrollMetrics(
                        rangeX.toInt(),
                        extentX.toInt(),
                        offsetX.toInt(),
                        rangeY.toInt(),
                        extentY.toInt(),
                        offsetY.toInt()
                )
            }
        }

        @JavascriptInterface
        fun onCaptureComplete() {
            activity.runOnUiThread { activity.isCapturing = false }
        }

        @JavascriptInterface
        fun startNativeQrScanner() {
            activity.runOnUiThread { activity.startNativeQrScanner(webView) }
        }

        @JavascriptInterface
        fun requestCurrentLocation() {
            activity.runOnUiThread {
                activity.noteGeolocationUse()
                val location = activity.getLastLocation()
                if (location != null) {
                    activity.dualWebViewGroup.injectLocation(location.first, location.second)
                }
            }
        }

        @JavascriptInterface
        fun fetchHtml(url: String, callbackName: String) {
            Thread {
                try {
                    val urlConnection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    urlConnection.requestMethod = "GET"
                    urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    val inputStream = urlConnection.inputStream
                    val html = inputStream.bufferedReader().use { it.readText() }
                    
                    val encodedHtml = android.util.Base64.encodeToString(html.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    
                    activity.runOnUiThread {
                        val js = "window['$callbackName'](atob('$encodedHtml'));"
                        webView.evaluateJavascript(js, null)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        val errorMsg = e.message?.replace("'", "\\'") ?: "Unknown error"
                        val js = "window['$callbackName'](null, '$errorMsg');"
                        webView.evaluateJavascript(js, null)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            activity.runOnUiThread {
                val formattedUrl = activity.formatUrl(url)
                val requiresDesktopBrowser = activity.isDesktopBrowserRequiredSite(formattedUrl)
                if (requiresDesktopBrowser) {
                    activity.dualWebViewGroup.prepareDesktopBrowsingMode(webView)
                }
                activity.applyUserAgentForUrl(webView, formattedUrl)
                activity.persistActiveUrl("dashboardOpenUrl", formattedUrl, webView)
                if (requiresDesktopBrowser) {
                    webView.settings.loadWithOverviewMode = true
                    webView.settings.useWideViewPort = true
                    activity.markMessagesCompatibilityReloadNeeded(webView)
                    webView.loadUrl(
                            formattedUrl,
                            mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")
                    )
                } else {
                    webView.loadUrl(formattedUrl)
                }
            }
        }

        @JavascriptInterface
        fun stopNativeQrScanner() {
            activity.runOnUiThread { activity.stopNativeQrScannerSession() }
        }
    }
}
