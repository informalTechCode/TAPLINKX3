package com.TapLinkX3.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import org.json.JSONObject

@SuppressLint("ClickableViewAccessibility")
class DualWebViewGroup
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        ViewGroup(context, attrs, defStyleAttr) {

    // Custom WebView to expose protected scroll methods
    private inner class InternalWebView(context: Context) : WebView(context) {
        fun getHorizontalScrollRange() = super.computeHorizontalScrollRange()
        fun getHorizontalScrollExtent() = super.computeHorizontalScrollExtent()
        fun getHorizontalScrollOffset() = super.computeHorizontalScrollOffset()
        fun getVerticalScrollRange() = super.computeVerticalScrollRange()
        fun getVerticalScrollExtent() = super.computeVerticalScrollExtent()
        fun getVerticalScrollOffset() = super.computeVerticalScrollOffset()
    }

    private val PREFS_NAME = "TapLinkPrefs"
    private val KEY_WINDOWS_STATE = "saved_windows_state"

    private data class BrowserWindow(
            val id: String = java.util.UUID.randomUUID().toString(),
            val webView: InternalWebView,
            var thumbnail: Bitmap? = null,
            var title: String = "New Tab"
    )

    private data class ScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int
    )

    private data class ExternalScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int,
            val timestamp: Long
    )

    private val windows = mutableListOf<BrowserWindow>()
    private var activeWindowId: String? = null

    interface WindowCallback {
        fun onWindowCreated(webView: WebView)
        fun onWindowSwitched(webView: WebView)
    }

    var windowCallback: WindowCallback? = null

    private var webView: InternalWebView

    val webViewsContainer: FrameLayout =
            FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

    private val rightEyeView: SurfaceView = SurfaceView(context)

    val dialogContainer: FrameLayout =
            FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(640, 480) // Full left eye size
                setBackgroundColor(Color.parseColor("#CC000000")) // Semi-transparent black
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                elevation = 2000f
            }
    private var customKeyboard: CustomKeyboardView? = null
    private var bitmap: Bitmap? = null

    private var velocityTracker: android.view.VelocityTracker? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshInterval = 16L // ~60fps for smooth mirroring
    private val maskedRefreshIntervalMs = 100L // ~10fps while the screen is masked
    private var lastCaptureTime = 0L
    private var lastScrollBarCheckTime = 0L
    private val scrollBarVisibilityThrottleMs = 50L
    private val MIN_CAPTURE_INTERVAL = 16L // Cap at ~60fps
    private var lastCursorUpdateTime = 0L
    private val CURSOR_UPDATE_INTERVAL = 16L // 60fps cap for cursor updates
    private var lastScrollBarInteractionTime = 0L
    private val scrollBarHoldMs = 1200L
    private var lastHorzScrollableAt = 0L
    private var lastVertScrollableAt = 0L
    private var externalScrollMetrics: ExternalScrollMetrics? = null
    private val externalScrollMetricsStaleMs = 3500L
    private var isMediaPlaying = false
    private var lastMediaPlayingAt = 0L
    private val mediaScrollFreezeMs = 1500L
    private val mediaStateByWindowId = mutableMapOf<String, Boolean>()
    private val mediaLastPlayedAtByWindowId = mutableMapOf<String, Long>()

    private var leftSystemInfoView: SystemInfoView

    lateinit var leftNavigationBar: View
    private val navBarHeightPx = 32.dp()
    private val toggleBarWidthPx = 32.dp()
    private val toggleButtonSizePx = toggleBarWidthPx

    val keyboardContainer: FrameLayout =
            FrameLayout(context).apply {
                val containerWidth = 640 - toggleBarWidthPx
                val containerHeight = 480 - navBarHeightPx
                layoutParams =
                        FrameLayout.LayoutParams(
                                        containerWidth,
                                        FrameLayout.LayoutParams.WRAP_CONTENT
                                )
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    gravity = Gravity.TOP or Gravity.START
                                }
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
    private val buttonFeedbackDuration = 200L
    var lastCursorX = 0f
    var lastCursorY = 0f

    private var anchoredGestureActive = false
    private var anchoredTarget = 0 // 0: None, 1: Keyboard, 2: Bookmarks, 3: Menu
    private var anchoredTouchStartX = 0f
    private var anchoredTouchStartY = 0f
    private var lastAnchoredY = 0f
    private var isAnchoredDrag = false
    private val ANCHORED_TOUCH_SLOP = 10f

    lateinit var leftToggleBar: View
    var progressBar: android.widget.ProgressBar =
            android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                    .apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 4)
                        progressDrawable.setTint(Color.BLUE)
                        max = 100
                        visibility = View.GONE
                        elevation = 200f // Ensure it's above other views
                    }
    private var btnShowNavBars: ImageButton =
            ImageButton(context).apply {
                layoutParams =
                        FrameLayout.LayoutParams(toggleButtonSizePx, toggleButtonSizePx).apply {
                            gravity = Gravity.BOTTOM or Gravity.END
                            rightMargin = 8
                            bottomMargin = 8
                        }
                setImageResource(R.drawable.ic_visibility_on)
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(8, 8, 8, 8)
                alpha = 1.0f
                visibility = View.GONE
                elevation = 2000f
                setOnClickListener { setScrollMode(false) }
            }

    @Volatile private var isRefreshing = false
    private val refreshLock = Any()

    private var isDesktopMode = false
    private var currentWebZoom = 1.0f
    private var isHoveringModeToggle = false
    private var isHoveringDashboardToggle = false
    private var isHoveringBookmarksMenu = false

    private lateinit var leftBookmarksView: BookmarksView
    private lateinit var chatView: ChatView

    var navigationListener: NavigationListener? = null
    var linkEditingListener: LinkEditingListener? = null

    private var isBookmarkEditing = false

    private lateinit var mobileUserAgent: String
    private val desktopUserAgent: String =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    private val verticalScrollFraction = 0.25f // Scroll vertically by 25% of the viewport per tap

    private var isHoveringZoomIn = false
    private var isHoveringZoomOut = false
    private var isHoveringWindowsToggle = false
    private var windowsButton: FontIconView? = null

    private var fullScreenTapDetector: GestureDetector =
            GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean {
                            // Always accept the initial down event so we can track the full gesture
                            return fullScreenOverlayContainer.visibility == View.VISIBLE
                        }

                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
                                (context as? AppCompatActivity)?.onBackPressedDispatcher
                                        ?.onBackPressed()
                                return true
                            }
                            return false
                        }
                    }
            )

    var isAnchored = false
        set(value) {
            field = value
            updateRefreshRate()
        }
    private var isHoveringAnchorToggle = false

    private val bitmapLock = Any()
    private var settingsMenu: View? = null
    private var isSettingsVisible = false

    interface DualWebViewGroupListener {
        fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean)
    }

    interface MaskToggleListener {
        fun onMaskTogglePressed()
    }

    interface AnchorToggleListener {
        fun onAnchorTogglePressed()
    }

    interface FullscreenListener {
        fun onEnterFullscreen()
        fun onExitFullscreen()
    }

    var fullscreenListener: FullscreenListener? = null

    private var hideProgressBarRunnable: Runnable? = null

    fun updateLoadingProgress(progress: Int) {

        post {
            // Cancel any pending hide action whenever we get an update
            hideProgressBarRunnable?.let { removeCallbacks(it) }
            hideProgressBarRunnable = null

            if (progress < 100) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = progress
                progressBar.bringToFront()
                requestLayout() // Force layout update to position progress bar correctly
            } else {
                progressBar.progress = 100
                // Delay hiding to ensure user sees 100%
                hideProgressBarRunnable = Runnable { progressBar.visibility = View.GONE }
                postDelayed(hideProgressBarRunnable!!, 500)
            }
        }
    }

    private data class NavButton(
            val left: FontIconView,
            val right: FontIconView,
            var isHovered: Boolean = false
    )

    private fun FontIconView.configureToggleButton(iconRes: Int) {
        visibility = View.VISIBLE
        setText(iconRes)
        setBackgroundResource(R.drawable.nav_button_background)
        gravity = android.view.Gravity.CENTER
        setPadding(8, 8, 8, 8)
        alpha = 1.0f
        elevation = 2f
        stateListAnimator = null
    }

    private fun clearNavigationButtonStates() {
        navButtons.values.forEach { navButton ->
            navButton.isHovered = false
            navButton.left.isHovered = false
            navButton.right.isHovered = false
        }
    }

    // Properties for link editing
    var urlEditText: EditText
    private val urlFieldMinHeight = 56.dp()

    private var leftEditField: EditText
    private var rightEditField: EditText
    private var _isUrlEditing = false

    // Keyboard listener interface
    interface KeyboardListener {
        fun onShowKeyboard()
        fun onHideKeyboard()
    }

    var keyboardListener: KeyboardListener? = null
        set(value) {
            field = value
            if (::chatView.isInitialized) {
                chatView.keyboardListener = value
            }
        }

    private var navButtons: Map<String, NavButton>

    var listener: DualWebViewGroupListener? = null
    var maskToggleListener: MaskToggleListener? = null

    val leftEyeUIContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true

                setBackgroundColor(Color.TRANSPARENT) // Make sure background is transparent
            }

    fun isActiveWebView(webView: WebView): Boolean {
        return this.webView == webView
    }

    fun pauseBackgroundMedia(sourceWebView: WebView) {
        windows.forEach { win ->
            if (win.webView != sourceWebView) {
                // Pause all media elements
                win.webView.evaluateJavascript(
                        "document.querySelectorAll('video, audio').forEach(function(e) { e.pause(); });",
                        null
                )
            }
        }
    }

    private val fullScreenOverlayContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true // Ensure clipping to bounds
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                isClickable = true
                isFocusable = true
            }

    // UI scale factor (0.5 to 1.0) - controlled by screen size slider
    var uiScale = 1.0f

    private val fullScreenHiddenViews: List<View> by lazy {
        listOf(
                webViewsContainer,
                leftToggleBar,
                leftNavigationBar,
                keyboardContainer,
                leftSystemInfoView,
                urlEditText
        )
    }

    private val previousFullScreenVisibility = mutableMapOf<View, Int>()

    val leftEyeClipParent =
            FrameLayout(context).apply {
                // Force it to be exactly 640px wide and match height (or some fixed height).
                // Using MATCH_PARENT for height is common if you want the full vertical space.
                layoutParams = FrameLayout.LayoutParams(640, FrameLayout.LayoutParams.MATCH_PARENT)

                // Ensure that children are clipped to our bounds
                clipToPadding = true
                clipChildren = true
                setBackgroundColor(Color.BLACK) // Set background to ensure proper rendering
            }

    fun updateUiScale(scale: Float) {
        uiScale = scale

        // Set pivot point to center (320, 240) so scaling happens around the center
        leftEyeUIContainer.pivotX = 320f
        leftEyeUIContainer.pivotY = 240f
        leftEyeUIContainer.scaleX = scale
        leftEyeUIContainer.scaleY = scale

        fullScreenOverlayContainer.pivotX = 320f
        fullScreenOverlayContainer.pivotY = 240f
        fullScreenOverlayContainer.scaleX = scale
        fullScreenOverlayContainer.scaleY = scale

        // Ensure parent is not scaled so it acts as a fixed window
        leftEyeClipParent.scaleX = 1f
        leftEyeClipParent.scaleY = 1f

        updateUiTranslation()

        // Update scroll bar visibility based on scale and anchor mode
        updateScrollBarsVisibility()

        // Notify listener to refresh cursor scale visually
        listener?.onCursorPositionChanged(lastCursorX, lastCursorY, true)

        requestLayout()
        invalidate()
    }

    private fun updateUiTranslation() {
        if (isAnchored) {
            leftEyeUIContainer.translationX = 0f
            leftEyeUIContainer.translationY = 0f
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            return
        }

        // Calculate max allowed translation based on current scale
        val maxTransX = 320f * (1f - uiScale)
        val maxTransY = 240f * (1f - uiScale)

        // Get saved progress (default 50)
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val xProgress = prefs.getInt("uiTransXProgress", 50)
        val yProgress = prefs.getInt("uiTransYProgress", 50)

        // Calculate translation
        val transX = ((xProgress - 50) / 50f) * maxTransX
        val transY = ((yProgress - 50) / 50f) * maxTransY

        leftEyeUIContainer.translationX = transX
        leftEyeUIContainer.translationY = transY

        fullScreenOverlayContainer.translationX = transX
        fullScreenOverlayContainer.translationY = transY

        // Update scroll bar thumb positions
        updateScrollBarThumbs(xProgress, yProgress)
        applyScrollbarTransform()
    }

    private fun isWebViewScrollEnabled(): Boolean {
        // Always return true to ensure scrollbars ONLY scroll the WebView content
        // and never move the screen position (viewport panning).
        return true
    }

    private fun scrollPageHorizontal(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = scrollAmount,
                        top = null,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(scrollAmount, 0)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransXProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun scrollPageVertical(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = null,
                        top = scrollAmount,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(0, scrollAmount)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransYProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun shouldFreezeScrollBars(): Boolean {
        val now = SystemClock.uptimeMillis()
        return isMediaPlaying || (now - lastMediaPlayingAt < mediaScrollFreezeMs)
    }

    private fun updateScrollBarThumbs(xProgress: Int, yProgress: Int) {
        val now = SystemClock.uptimeMillis()
        if (isInteractingWithScrollBar && now - lastScrollBarInteractionTime < 120L) return

        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(now)
            // Update Horizontal Thumb based on WebView scroll
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                // Calculate ratio: scrollX / (contentWidth - viewportWidth)
                // Since we can't easily get full content width without computeHorizontalScrollRange
                // (protected),
                // we'll rely on an approximation or need to subclass WebView.
                // For now, let's try using the standard range approximation if possible, or just
                // skip if we can't get it.
                // Actually, we can use computeHorizontalScrollRange via reflection or just use
                // scrollX/ArbitraryLargeNumber if needed,
                // but simpler is to use `webView.scrollX` relative to estimated width.
                // Let's defer exact horizontal proportion calculation or use a safe fallback.

                // Using standard view methods available on WebView (which is a View)
                val range = metrics.rangeX
                val extent = metrics.extentX
                val offset = metrics.offsetX

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val hMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    hScrollThumb.translationX = hMargin.toFloat()
                    hScrollThumb.invalidate()
                }
            }

            // Update Vertical Thumb based on WebView scroll
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight

                val range = metrics.rangeY
                val extent = metrics.extentY
                val offset = metrics.offsetY

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val vMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    vScrollThumb.translationY = vMargin.toFloat()
                    vScrollThumb.invalidate()
                }
            }
        } else {
            // Existing logic for non-anchored (viewport pan)
            // Update horizontal thumb position
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                val hMargin = (xProgress / 100f * maxMargin).toInt()
                hScrollThumb.translationX = hMargin.toFloat()
                hScrollThumb.invalidate()
            }

            // Update vertical thumb position
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight
                val vMargin = (yProgress / 100f * maxMargin).toInt()
                vScrollThumb.translationY = vMargin.toFloat()
                vScrollThumb.invalidate()
            }
        }
    }

    fun updateScrollBarsVisibility() {
        // Log.d("ScrollDebug", "updateScrollBarsVisibility called. isAnchored=$isAnchored,
        // isInScrollMode=$isInScrollMode, uiScale=$uiScale")
        val now = SystemClock.uptimeMillis()
        if (shouldFreezeScrollBars() && !isInteractingWithScrollBar) {
            return
        }

        // Determine mode-specific base constraints
        val isScrollModeActive = isInScrollMode

        // Base dimensions
        val containerWidth = 640
        val baseLeftMargin = if (isScrollModeActive) 0 else toggleBarWidthPx
        val rawBottomMargin = if (isScrollModeActive) 0 else navBarHeightPx
        val keyboardVisible = keyboardContainer.visibility == View.VISIBLE
        if (keyboardVisible) {
            val keyboardWidth = 640 - toggleBarWidthPx
            keyboardContainer.measure(
                    MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val keyboardHeight =
                if (keyboardVisible) {
                    val measured = keyboardContainer.measuredHeight
                    if (measured > 0) measured else 160
                } else {
                    0
                }
        val baseBottomMargin = if (keyboardVisible) 0 else rawBottomMargin

        // If anchored, scrollbars are always hidden
        if (isAnchored) {
            lastHorzScrollableAt = 0L
            lastVertScrollableAt = 0L
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE

            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                var targetWidth = 0
                var targetHeight = 0
                if (isScrollModeActive) {
                    targetWidth = containerWidth
                    targetHeight = (480 - keyboardHeight).coerceAtLeast(0)
                } else {
                    targetWidth = containerWidth - baseLeftMargin
                    targetHeight = (480 - baseBottomMargin - keyboardHeight).coerceAtLeast(0)
                }

                var changed = false
                if (p.width != targetWidth) changed = true
                if (p.height != targetHeight) changed = true
                if (p.leftMargin != baseLeftMargin) changed = true
                if (p.rightMargin != 0) changed = true
                if (p.bottomMargin != baseBottomMargin) changed = true

                if (changed) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = baseLeftMargin
                    p.rightMargin = 0
                    p.bottomMargin = baseBottomMargin
                    webViewsContainer.layoutParams = p
                    webViewsContainer.requestLayout()
                    webViewsContainer.invalidate()
                }
            }
            return
        }

        // Always check WebView scrollability since we disabled viewport panning
        val metrics = resolveScrollMetrics(now)
        val webHRange = metrics.rangeX
        val webHExtent = metrics.extentX
        val webVRange = metrics.rangeY
        val webVExtent = metrics.extentY
        val scrollDeltaThreshold = 6
        val webHDelta = webHRange - webHExtent
        val webVDelta = webVRange - webVExtent
        val showHorzRaw = webHDelta > scrollDeltaThreshold
        val showVertRaw = webVDelta > scrollDeltaThreshold
        if (showHorzRaw) {
            lastHorzScrollableAt = now
        }
        if (showVertRaw) {
            lastVertScrollableAt = now
        }
        val showHorz = showHorzRaw || (now - lastHorzScrollableAt < scrollBarHoldMs)
        val showVert = showVertRaw || (now - lastVertScrollableAt < scrollBarHoldMs)

        horizontalScrollBar.apply {
            visibility = if (showHorz) View.VISIBLE else View.INVISIBLE
            isClickable = showHorz
            isFocusable = false
        }

        verticalScrollBar.apply {
            visibility = if (showVert) View.VISIBLE else View.INVISIBLE
            isClickable = showVert
            isFocusable = false
        }

        // Apply layout adjustments
        (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            // Keep WebView sizing stable to avoid layout churn (prevents media pauses/flicker).
            val rightMarginShift = if (verticalScrollBar.visibility == View.VISIBLE) 20 else 0
            val bottomMarginShift = if (horizontalScrollBar.visibility == View.VISIBLE) 20 else 0

            var targetWidth = 0
            var targetHeight = 0
            var targetLeftMargin = 0
            var targetBottomMargin = 0
            var targetRightMargin = 0

            if (isScrollModeActive) {
                // Scroll Mode: 640 total width
                targetWidth = 640 - rightMarginShift
                targetHeight = (480 - bottomMarginShift - keyboardHeight).coerceAtLeast(0)
                targetLeftMargin = 0
                targetRightMargin = rightMarginShift
                targetBottomMargin = bottomMarginShift
            } else {
                // Normal Mode:
                // Width: 640 total - toggle bar - margin
                targetWidth = (640 - baseLeftMargin) - rightMarginShift

                // Height: 480 total - nav bar - margin
                // We must be explicit here so onMeasure picks it up
                targetHeight =
                        (480 - baseBottomMargin - bottomMarginShift - keyboardHeight).coerceAtLeast(
                                0
                        )

                targetLeftMargin = baseLeftMargin
                targetRightMargin = rightMarginShift
                targetBottomMargin = baseBottomMargin + bottomMarginShift
            }

            var changed = false
            if (p.width != targetWidth) changed = true
            if (p.height != targetHeight) changed = true
            if (p.leftMargin != targetLeftMargin) changed = true
            if (p.rightMargin != targetRightMargin) changed = true
            if (p.bottomMargin != targetBottomMargin) changed = true

            if (changed) {
                p.width = targetWidth
                p.height = targetHeight
                p.leftMargin = targetLeftMargin
                p.rightMargin = targetRightMargin
                p.bottomMargin = targetBottomMargin

                // Log.d("ScrollDebug", "Applying Layout: Mode=${if(isScrollModeActive)"Scroll" else
                // "Normal"}, [${p.width} x ${p.height}], Margins: L=${p.leftMargin},
                // R=${p.rightMargin}, B=${p.bottomMargin}")
                webViewsContainer.layoutParams = p
                webViewsContainer.requestLayout()
                webViewsContainer.invalidate()
            }
        }

        // Remove unconditional requestLayout/invalidate here
        // webView.requestLayout()
        // webView.invalidate()

        if (horizontalScrollBar.visibility == View.VISIBLE ||
                        verticalScrollBar.visibility == View.VISIBLE
        ) {
            updateScrollBarThumbs(0, 0)
        }
    }

    fun updateExternalScrollMetrics(
            rangeX: Int,
            extentX: Int,
            offsetX: Int,
            rangeY: Int,
            extentY: Int,
            offsetY: Int
    ) {
        val now = SystemClock.uptimeMillis()
        externalScrollMetrics =
                ExternalScrollMetrics(
                        rangeX = rangeX.coerceAtLeast(0),
                        extentX = extentX.coerceAtLeast(0),
                        offsetX = offsetX.coerceAtLeast(0),
                        rangeY = rangeY.coerceAtLeast(0),
                        extentY = extentY.coerceAtLeast(0),
                        offsetY = offsetY.coerceAtLeast(0),
                        timestamp = now
                )

        if (!isAnchored && now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
            updateScrollBarsVisibility()
            lastScrollBarCheckTime = now
        } else {
            updateScrollBarThumbs(0, 0)
        }
    }

    fun clearExternalScrollMetrics() {
        externalScrollMetrics = null
    }

    private fun resolveScrollMetrics(now: Long): ScrollMetrics {
        val webRangeX = webView.getHorizontalScrollRange()
        val webExtentX = webView.getHorizontalScrollExtent()
        val webOffsetX = webView.getHorizontalScrollOffset()
        val webRangeY = webView.getVerticalScrollRange()
        val webExtentY = webView.getVerticalScrollExtent()
        val webOffsetY = webView.getVerticalScrollOffset()

        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
        val useExternalH =
                external != null && external.rangeX > external.extentX && external.extentX > 0
        val useExternalV =
                external != null && external.rangeY > external.extentY && external.extentY > 0

        return ScrollMetrics(
                rangeX = if (useExternalH && external != null) external.rangeX else webRangeX,
                extentX = if (useExternalH && external != null) external.extentX else webExtentX,
                offsetX = if (useExternalH && external != null) external.offsetX else webOffsetX,
                rangeY = if (useExternalV && external != null) external.rangeY else webRangeY,
                extentY = if (useExternalV && external != null) external.extentY else webExtentY,
                offsetY = if (useExternalV && external != null) external.offsetY else webOffsetY
        )
    }

    private fun shouldUseJsScroll(metrics: ScrollMetrics): Boolean {
        val now = SystemClock.uptimeMillis()
        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
        return external != null &&
                (metrics.rangeX > metrics.extentX || metrics.rangeY > metrics.extentY)
    }

    private fun scrollWebViewByJs(left: Int?, top: Int?, smooth: Boolean, useScrollTo: Boolean) {
        val leftValue = left?.toString() ?: "undefined"
        val topValue = top?.toString() ?: "undefined"
        val behavior = if (smooth) "'smooth'" else "'auto'"
        val useScrollToJs = if (useScrollTo) "true" else "false"
        webView.evaluateJavascript(
                """
            (function() {
                if ($useScrollToJs && typeof window.scrollTo === 'function') {
                    window.scrollTo({ left: $leftValue, top: $topValue, behavior: $behavior });
                } else if (!$useScrollToJs && typeof window.scrollBy === 'function') {
                    window.scrollBy({ left: $leftValue, top: $topValue, behavior: $behavior });
                } else if (typeof window.scrollTo === 'function') {
                    window.scrollTo({ left: $leftValue, top: $topValue, behavior: $behavior });
                }
            })();
        """,
                null
        )
    }

    private fun applyScrollbarTransform() {
        val scale = if (uiScale <= 0f) 1f else 1f / uiScale
        val transX = -leftEyeUIContainer.translationX
        val transY = -leftEyeUIContainer.translationY
        listOf(horizontalScrollBar, verticalScrollBar).forEach { bar ->
            bar.pivotX = 0f
            bar.pivotY = 0f
            bar.scaleX = scale
            bar.scaleY = scale
            bar.translationX = transX
            bar.translationY = transY
        }
    }

    private fun updateHorizontalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            val range = metrics.rangeX
            val extent = metrics.extentX
            if (range > extent) {
                val targetX = percent * (range - extent)
                if (shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = targetX.toInt(),
                            top = null,
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(targetX.toInt(), webView.scrollY)
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun updateVerticalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            val range = metrics.rangeY
            val extent = metrics.extentY
            if (range > extent) {
                val targetY = percent * (range - extent)
                if (shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = null,
                            top = targetY.toInt(),
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(webView.scrollX, targetY.toInt())
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    // Function to update the cursor positions and visibility
    fun updateCursorPosition(x: Float, y: Float, isVisible: Boolean) {
        val currentTime = System.currentTimeMillis()
        lastCursorX = x
        lastCursorY = y

        if (!isAttachedToWindow) {
            return
        }

        if (currentTime - lastCursorUpdateTime >= CURSOR_UPDATE_INTERVAL) {
            if (isVisible) {
                // Convert cursor from container-local to screen coordinates
                val containerLocation = IntArray(2)
                getLocationOnScreen(containerLocation)

                // Account for UI scale and translation when calculating screen position
                // Visual cursor is scaled around (320, 240) and then translated (only in
                // non-anchored mode)
                val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
                val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

                val visualX = 320f + (x - 320f) * uiScale + transX
                val visualY = 240f + (y - 240f) * uiScale + transY

                val screenX = visualX + containerLocation[0]
                val screenY = visualY + containerLocation[1]

                // Pass screen coordinates - buttons also use screen coordinates
                updateButtonHoverStates(screenX, screenY)
            }
            listener?.onCursorPositionChanged(x, y, isVisible)
            lastCursorUpdateTime = currentTime
        }
    }

    private var isScreenMasked = false
    private var isHoveringMaskToggle = false
    private var maskOverlay: FrameLayout =
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT) // Left eye width only
                elevation = 1000f // Put it above everything except cursors
                isClickable = true
                isFocusable = true

                // Consume all touch events to prevent propagation to navbar/webview behind
                // Child buttons (unmask, media controls) will still work because they handle events
                // first
                setOnTouchListener { _, _ -> true }
            }

    // Mask mode UI elements
    private lateinit var maskMediaControlsContainer: LinearLayout
    private lateinit var btnMaskPrevTrack: FontIconView // Skip to previous song
    private lateinit var btnMaskPrev: FontIconView // 10s back
    private lateinit var btnMaskPlay: FontIconView
    private lateinit var btnMaskPause: FontIconView
    private lateinit var btnMaskNext: FontIconView // 10s forward
    private lateinit var btnMaskNextTrack: FontIconView // Skip to next song
    private lateinit var btnMaskUnmask: ImageButton

    var anchorToggleListener: AnchorToggleListener? = null

    // Add properties to track translations
    private var _translationX = 0f
    private var _translationY = 0f
    private var _rotationZ = 0f

    private var isInScrollMode = false
    private var settingsScrim: View? = null

    // Scroll bar containers for non-anchored mode
    private var horizontalScrollBar: LinearLayout
    private var verticalScrollBar: LinearLayout
    private var hScrollThumb: View
    private var vScrollThumb: View
    private var isInteractingWithScrollBar = false

    private var windowsOverviewContainer: android.widget.ScrollView? = null
    private var hoveredWindowsOverviewItem: View? = null

    fun showWindowsOverview() {
        Log.d(
                "WindowsOverview",
                "showWindowsOverview called, windowsOverviewContainer=${windowsOverviewContainer != null}"
        )
        if (windowsOverviewContainer == null) {
            createWindowsOverviewUI()
            Log.d("WindowsOverview", "Created windows overview UI")
        }

        // Populate container with current windows
        val container = windowsOverviewContainer?.getChildAt(0) as? LinearLayout
        if (container == null) {
            Log.e(
                    "WindowsOverview",
                    "Container is null! windowsOverviewContainer has ${windowsOverviewContainer?.childCount ?: 0} children"
            )
            return
        }
        Log.d("WindowsOverview", "Container found, clearing views. Windows count: ${windows.size}")
        container.removeAllViews()

        // Add "Add Window" button at the top - shorter with label
        val addButton =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50)
                                    .apply { bottomMargin = 12 }
                    // Create StateListDrawable for hover feedback
                    val normalBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#2A5298"))
                                cornerRadius = 12f
                            }
                    val hoveredBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#3A72C8"))
                                cornerRadius = 12f
                                setStroke(2, Color.parseColor("#6BAAFF"))
                            }
                    val pressedBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#4A82D8"))
                                cornerRadius = 12f
                            }
                    background =
                            android.graphics.drawable.StateListDrawable().apply {
                                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                                addState(intArrayOf(android.R.attr.state_hovered), hoveredBg)
                                addState(intArrayOf(), normalBg)
                            }
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { createNewWindow() }
                }

        val addIcon =
                FontIconView(context).apply {
                    setText(R.string.fa_plus)
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { rightMargin = 12 }
                }
        val addLabel =
                TextView(context).apply {
                    text = "Open New Tab"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                }
        addButton.addView(addIcon)
        addButton.addView(addLabel)
        container.addView(addButton)
        Log.d("WindowsOverview", "Added 'Add Window' button")

        // Calculate item dimensions for 3-column grid with stretching
        // Container width = 608 - 32 (padding) = 576
        val itemMargin = 8
        val columnsPerRow = 3
        val itemHeight = 120 // Fixed height for items

        // Create rows for 3-column grid
        var currentRow: LinearLayout? = null
        windows.forEachIndexed { index, win ->
            Log.d("WindowsOverview", "Adding window item: ${win.id}, title: ${win.title}")

            // Create new row every 3 items
            if (index % columnsPerRow == 0) {
                currentRow =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                            )
                                            .apply { bottomMargin = itemMargin }
                        }
                container.addView(currentRow)
            }

            // Calculate position in current row
            val positionInRow = index % columnsPerRow

            val item =
                    FrameLayout(context).apply {
                        // Use weight=1 so items stretch to fill available width
                        layoutParams =
                                LinearLayout.LayoutParams(0, itemHeight, 1f).apply {
                                    marginStart = if (positionInRow == 0) 0 else itemMargin / 2
                                    marginEnd =
                                            if (positionInRow == columnsPerRow - 1) 0
                                            else itemMargin / 2
                                }
                        background =
                                android.graphics.drawable.StateListDrawable().apply {
                                    val isActive = win.id == activeWindowId

                                    // Colors
                                    val normalBgColor =
                                            if (isActive) Color.parseColor("#444444")
                                            else Color.parseColor("#252525")
                                    val hoverBgColor =
                                            if (isActive) Color.parseColor("#555555")
                                            else Color.parseColor("#353535")
                                    val normalStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else Color.parseColor("#404040")
                                    val hoverStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else
                                                    Color.parseColor(
                                                            "#505050"
                                                    ) // Lighter stroke on hover for inactive

                                    val hoveredDrawable =
                                            GradientDrawable().apply {
                                                setColor(hoverBgColor)
                                                setStroke(2, hoverStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    val normalDrawable =
                                            GradientDrawable().apply {
                                                setColor(normalBgColor)
                                                setStroke(2, normalStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    addState(
                                            intArrayOf(android.R.attr.state_hovered),
                                            hoveredDrawable
                                    )
                                    addState(intArrayOf(), normalDrawable)
                                }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { switchToWindow(win.id) }
                    }

            // Thumbnail (Placeholder or actual bitmap)
            val thumbView =
                    ImageView(context).apply {
                        if (win.thumbnail != null) {
                            setImageBitmap(win.thumbnail)
                        } else {
                            setBackgroundColor(Color.parseColor("#1A1A1A"))
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                        .apply { setMargins(3, 3, 3, 3) }
                        alpha = 0.6f
                    }
            item.addView(thumbView)

            // Title - smaller text, truncated
            val titleView =
                    TextView(context).apply {
                        text = win.title.take(20) + if (win.title.length > 20) "..." else ""
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        maxLines = 2
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM
                                            setMargins(6, 0, 6, 6)
                                        }
                        setShadowLayer(3f, 0f, 0f, Color.BLACK)
                    }
            item.addView(titleView)

            // Delete button - smaller
            val deleteBtn =
                    FontIconView(context).apply {
                        setText(R.string.fa_xmark)
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#CC333333"))
                                    shape = GradientDrawable.OVAL
                                }
                        layoutParams =
                                FrameLayout.LayoutParams(28, 28).apply {
                                    gravity = Gravity.TOP or Gravity.END
                                    topMargin = 4
                                    rightMargin = 4
                                }
                        setOnClickListener { closeWindow(win.id) }
                    }
            item.addView(deleteBtn)

            currentRow?.addView(item)
        }

        Log.d("WindowsOverview", "Setting container visible, total items: ${container.childCount}")
        windowsOverviewContainer?.visibility = View.VISIBLE
        webView.visibility = View.GONE

        // Force the container to the front by removing and re-adding at the end
        // Preserve the layout params
        // Force the container to the front using bringToFront() instead of remove/add
        // which can cause layout state loss
        val params =
                windowsOverviewContainer?.layoutParams as? FrameLayout.LayoutParams
                        ?: FrameLayout.LayoutParams(640 - toggleBarWidthPx, 480 - navBarHeightPx)
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    // Explicitly set Gravity to avoid any ambiguity
                                    gravity = Gravity.TOP or Gravity.START
                                }

        // Ensure params are applied
        windowsOverviewContainer?.layoutParams = params

        windowsOverviewContainer?.bringToFront()

        requestLayout()
        invalidate()

        // Log layout info after layout pass
        windowsOverviewContainer?.post {
            val woc = windowsOverviewContainer
            Log.d(
                    "WindowsOverview",
                    "Post-layout: width=${woc?.width}, height=${woc?.height}, " +
                            "x=${woc?.x}, y=${woc?.y}, visibility=${woc?.visibility}, " +
                            "layoutParams=${woc?.layoutParams?.width}x${woc?.layoutParams?.height}"
            )
        }
    }

    fun hideWindowsOverview() {
        windowsOverviewContainer?.visibility = View.GONE
        webView.visibility = View.VISIBLE
        requestLayout()
        invalidate()
    }

    private fun createWindowsOverviewUI() {
        Log.d("WindowsOverview", "createWindowsOverviewUI called")
        // Use explicit dimensions since MATCH_PARENT wasn't resolving
        val containerWidth = 640 - toggleBarWidthPx // 608
        val containerHeight = 480 - navBarHeightPx // 448

        windowsOverviewContainer =
                android.widget.ScrollView(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(containerWidth, containerHeight).apply {
                                leftMargin = toggleBarWidthPx
                                gravity = Gravity.TOP or Gravity.START
                            }
                    setBackgroundColor(Color.parseColor("#101010"))
                    visibility = View.GONE
                    elevation = 1500f
                    isFillViewport = true // Ensure content fills the viewport
                }
        Log.d("WindowsOverview", "Container created: ${containerWidth}x${containerHeight}")

        val content =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Use ViewGroup.LayoutParams for ScrollView children
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                    setPadding(16, 16, 16, 16)
                    setBackgroundColor(Color.parseColor("#101010")) // Match parent background
                }

        windowsOverviewContainer?.addView(content)
        leftEyeUIContainer.addView(windowsOverviewContainer)
        Log.d(
                "WindowsOverview",
                "UI created: ScrollView has ${windowsOverviewContainer?.childCount} children, added to leftEyeUIContainer (${leftEyeUIContainer.childCount} children)"
        )
    }

    fun toggleWindowMode() {
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            hideWindowsOverview()
        } else {
            // Capture thumbnail of current window before showing overview
            val currentWin = windows.find { it.id == activeWindowId }
            if (currentWin != null) {
                try {
                    // Simple capture of the webview drawing cache or similar
                    // Using drawing cache is deprecated but works for simple needs, or
                    // PixelCopy/draw
                    // Here we'll use a simple draw to canvas if possible
                    val w = webView.width
                    val h = webView.height
                    if (w > 0 && h > 0) {
                        val bmp = Bitmap.createBitmap(w / 4, h / 4, Bitmap.Config.RGB_565)
                        val c = Canvas(bmp)
                        c.scale(0.25f, 0.25f)
                        webView.draw(c)
                        currentWin.thumbnail = bmp
                    }
                } catch (e: Exception) {
                    Log.e("Windows", "Failed to capture thumbnail", e)
                }
                currentWin.title = webView.title ?: "Tab"
            }
            showWindowsOverview()
        }
    }

    fun createNewWindow() {
        val newWebView = InternalWebView(context)
        configureWebView(newWebView)
        // Load default URL for fresh windows
        newWebView.loadUrl(Constants.DEFAULT_URL)
        val newWindow = BrowserWindow(webView = newWebView, title = "New Tab")

        // Add to container but invisible
        newWebView.visibility = View.INVISIBLE
        webViewsContainer.addView(
                newWebView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Notify MainActivity to configure the new WebView (clients, settings, etc.)
        windowCallback?.onWindowCreated(newWebView)

        windows.add(newWindow)
        switchToWindow(newWindow.id)
        saveAllWindowsState()
    }

    fun switchToWindow(id: String) {
        val targetWindow = windows.find { it.id == id } ?: return

        if (activeWindowId == id) {
            // Already active, just hide overview if visible
            hideWindowsOverview()
            return
        }

        // Hide current active webview
        webView.visibility = View.INVISIBLE

        // Switch active window
        activeWindowId = id
        webView = targetWindow.webView

        // Show new active webview and bring to front within container to ensure z-ordering
        webView.visibility = View.VISIBLE
        webView.bringToFront()

        // Ensure settings are applied (zoom, font size, etc.) which might be instance specific if
        // not global
        // MainActivity's setup should handle most, but we might need to re-apply UI specific things
        updateScrollBarsVisibility()

        // Notify callback
        windowCallback?.onWindowSwitched(webView)

        hideWindowsOverview()
        saveAllWindowsState()
    }

    fun closeWindow(id: String) {
        val windowToRemove = windows.find { it.id == id } ?: return

        // Don't close the last window, or create a new one if we do
        val wasActive = activeWindowId == id

        windows.remove(windowToRemove)
        mediaStateByWindowId.remove(id)
        mediaLastPlayedAtByWindowId.remove(id)
        webViewsContainer.removeView(windowToRemove.webView)
        windowToRemove.webView.destroy()
        windowToRemove.thumbnail?.recycle()

        if (windows.isEmpty()) {
            createNewWindow()
        } else if (wasActive) {
            // Switch to the last window in the list
            switchToWindow(windows.last().id)
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        } else {
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        }
        saveAllWindowsState()
        if (mediaStateByWindowId.isNotEmpty()) {
            updateMediaState(mediaStateByWindowId.values.any { it })
        } else {
            isMediaPlaying = false
            hideMediaControls()
        }
    }

    fun saveAllWindowsState() {
        try {
            val root = org.json.JSONObject()
            root.put("activeId", activeWindowId)

            val windowsArray = org.json.JSONArray()
            windows.forEach { win ->
                // Update title from WebView if available
                if (!win.webView.title.isNullOrEmpty()) {
                    win.title = win.webView.title!!
                }

                val winObj = org.json.JSONObject()
                winObj.put("id", win.id)
                winObj.put("title", win.title)
                winObj.put("url", win.webView.url ?: "")

                // Save full WebView state (history, etc)
                val state = Bundle()
                win.webView.saveState(state)
                val parcel = Parcel.obtain()
                state.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                parcel.recycle()
                val stateString = Base64.encodeToString(bytes, Base64.DEFAULT)
                winObj.put("state", stateString)

                windowsArray.put(winObj)
            }
            root.put("windows", windowsArray)

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_WINDOWS_STATE, root.toString())
                    .apply()

            Log.d("Persistence", "Saved ${windows.size} windows with state")
        } catch (e: Exception) {
            Log.e("Persistence", "Error saving window state", e)
        }
    }

    fun restoreState() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_WINDOWS_STATE, null)

            if (jsonString.isNullOrEmpty()) return

            val root = org.json.JSONObject(jsonString)
            val savedActiveId = if (root.has("activeId")) root.getString("activeId") else null
            val windowsArray = root.optJSONArray("windows")

            if (windowsArray != null && windowsArray.length() > 0) {
                // Clear existing windows (default one)
                windows.toList().forEach {
                    it.webView.destroy()
                    it.thumbnail?.recycle()
                }
                windows.clear()

                webViewsContainer.removeAllViews()

                for (i in 0 until windowsArray.length()) {
                    val winObj = windowsArray.getJSONObject(i)
                    val id = winObj.getString("id")
                    val title = winObj.getString("title")
                    val url = winObj.getString("url")
                    val stateString = winObj.optString("state", "")

                    val newWebView = InternalWebView(context)
                    configureWebView(newWebView)
                    // Important: notify MainActivity to attach its logic
                    windowCallback?.onWindowCreated(newWebView)

                    var restored = false
                    if (stateString.isNotEmpty()) {
                        try {
                            val bytes = Base64.decode(stateString, Base64.DEFAULT)
                            val parcel = Parcel.obtain()
                            parcel.unmarshall(bytes, 0, bytes.size)
                            parcel.setDataPosition(0)
                            val state = Bundle()
                            state.readFromParcel(parcel)
                            parcel.recycle()
                            // Restore state returns the WebBackForwardList but we don't need it
                            // explicitly
                            newWebView.restoreState(state)
                            restored = true
                        } catch (e: Exception) {
                            Log.e("Persistence", "Failed to restore webview bundle", e)
                        }
                    }

                    if (!restored) {
                        if (url.isNotEmpty()) {
                            newWebView.loadUrl(url)
                        } else {
                            newWebView.loadUrl(Constants.DEFAULT_URL)
                        }
                    }

                    val win = BrowserWindow(id = id, webView = newWebView, title = title)
                    windows.add(win)

                    // Add to container
                    newWebView.visibility = View.INVISIBLE
                    webViewsContainer.addView(
                            newWebView,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }

                if (windows.isNotEmpty()) {
                    val targetId =
                            if (savedActiveId != null && windows.any { it.id == savedActiveId }) {
                                savedActiveId
                            } else {
                                windows.last().id
                            }
                    switchToWindow(targetId)
                } else {
                    // Fallback if parsing failed
                    createNewWindow()
                }
            }
        } catch (e: Exception) {
            Log.e("Persistence", "Error restoring window state", e)
            // Restore default if failed
            if (windows.isEmpty()) createNewWindow()
        }
    }

    private fun configureWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION") // Suppress for extensive database usage
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(MediaInterface(this, webView), "MediaInterface")

        // Keep WebAppInterface for referencing context/logic if needed, but primary comms via URL
        // scheme
        // Enable Native Bridge for Chat
        // GroqBridge removed

        webView.webViewClient =
                object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        Log.d("GroqUrl", "Checking URL: $url")

                        if (url.startsWith("taplink://chat")) {
                            Log.d("GroqUrl", "Intercepted taplink://chat")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                // Use the top-level WebAppInterface class we created
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        return false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            url: String?
                    ): Boolean {
                        Log.d("GroqUrl", "Checking URL (deprecated): $url")
                        if (url != null && url.startsWith("taplink://chat")) {
                            Log.d("GroqUrl", "Intercepted taplink://chat (deprecated)")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        try {
                            val mediaInterfaceClass =
                                    Class.forName(
                                            "com.TapLinkX3.app.DualWebViewGroup\$MediaInterface"
                                    )
                            // Actually we are inside DualWebViewGroup, so can call method directly?
                            // Yes, injectMediaListeners() is a private method of DualWebViewGroup.
                            // But WebViewClient is an anonymous inner class.
                            // So we need to call DualWebViewGroup.this.injectMediaListeners()
                            // But in Kotlin inner class, we can just call it if it's visible.
                            // injectMediaListeners is private in DualWebViewGroup.
                            // Kotlin anonymous object inside a method (configureWebView)
                            // configureWebView is a method of DualWebViewGroup.
                            // So yes, we can call injectMediaListeners() directly.
                            view?.let { injectMediaListeners(it) }
                            updateScrollBarsVisibility()
                        } catch (e: Exception) {
                            android.util.Log.e("TapLink", "Error in onPageFinished", e)
                        }
                    }
                }

        webView.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setOnTouchListener { _, _ -> keyboardContainer.visibility == View.VISIBLE }
            setOnLongClickListener { true }

            setOnScrollChangeListener { _, _, _, _, _ ->
                if (isWebViewScrollEnabled()) {
                    updateScrollBarThumbs(0, 0)
                    val now = System.currentTimeMillis()
                    if (now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
                        updateScrollBarsVisibility()
                        lastScrollBarCheckTime = now
                    }
                }
            }
        }
    }

    init {
        // Initialize the first WebView
        // Initial WebView configuration
        val initialWebView = InternalWebView(context)
        webView = initialWebView
        configureWebView(webView) // Local basic config
        mobileUserAgent = webView.settings.userAgentString

        // CRITICAL FIX: Do NOT add the initial webview to the container or windows list yet.
        // This prevents the "Dashboard flash" on startup.
        // The container starts empty.
        // restoreState() will either:
        // 1. Restore saved windows (and set active one)
        // 2. Or call createNewWindow() calls which will add a window and load the default URL.


        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        isDesktopMode = prefs.getBoolean("isDesktopMode", false)
        currentWebZoom = prefs.getFloat("webZoomLevel", 1.0f)
        updateBrowsingMode(isDesktopMode)

        // Set the background of the entire DualWebViewGroup to black
        setBackgroundColor(Color.BLACK)

        // Ensure the left eye (Activity Window) uses the same pixel format as the right eye
        // (SurfaceView)
        // This ensures consistent color saturation between both eyes.
        (context as? Activity)?.window?.setFormat(PixelFormat.RGBA_8888)

        fullScreenOverlayContainer.setOnTouchListener { _, event ->
            if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
                fullScreenTapDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        // Initial WebView configuration moved to configureWebView() and MainActivity

        // Configure SurfaceView for right eye mirroring
        rightEyeView.apply {
            isClickable = false
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            setupBitmap(width, height)
                            startRefreshing()
                        }

                        override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                        ) {
                            setupBitmap(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            synchronized(bitmapLock) {
                                val currentBitmap = bitmap
                                bitmap = null // Set to null first
                                currentBitmap?.let { bmp ->
                                    if (!bmp.isRecycled) {
                                        bmp.recycle()
                                    }
                                }
                            }
                            stopRefreshing()
                        }
                    }
            )
        }

        // Initialize keyboard containers
        keyboardContainer.apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // Log.d("KeyboardDebug", "leftKeyboardContainer clicked")
            }
            setOnTouchListener { _, event ->
                // Log.d("KeyboardDebug", "leftKeyboardContainer received touch event:
                // ${event.action}")
                true
            }
        }

        // Initialize navigation bars
        leftNavigationBar =
                LayoutInflater.from(context).inflate(R.layout.navigation_bar, this, false).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    setPadding(16, 0, 16, 0)
                }

        // Initialize navigation buttons
        navButtons =
                mapOf(
                        "back" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnBack),
                                        right = leftNavigationBar.findViewById(R.id.btnBack)
                                ),
                        "forward" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnForward),
                                        right = leftNavigationBar.findViewById(R.id.btnForward)
                                ),
                        "home" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHome),
                                        right = leftNavigationBar.findViewById(R.id.btnHome)
                                ),
                        "link" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnLink),
                                        right = leftNavigationBar.findViewById(R.id.btnLink)
                                ),
                        "settings" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnSettings),
                                        right = leftNavigationBar.findViewById(R.id.btnSettings)
                                ),
                        "refresh" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnRefresh),
                                        right = leftNavigationBar.findViewById(R.id.btnRefresh)
                                ),
                        "hide" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHide),
                                        right = leftNavigationBar.findViewById(R.id.btnHide)
                                ),
                        "quit" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnQuit),
                                        right = leftNavigationBar.findViewById(R.id.btnQuit)
                                ),
                        "chat" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnChat),
                                        right = leftNavigationBar.findViewById(R.id.btnChat)
                                )
                )

        // Initialize all buttons with same base properties
        navButtons.values.forEach { navButton ->
            navButton.left.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
            navButton.right.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
        }

        // Initialize left toggle bar
        leftToggleBar =
                LayoutInflater.from(context).inflate(R.layout.toggle_bar, this, false).apply {
                    layoutParams = LayoutParams(toggleBarWidthPx, 480 - navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    clipToOutline = true // Add this
                    clipChildren = true // Add this
                    isClickable = true // Add this
                    isFocusable = true // Add this
                }

        // Log.d("ViewDebug", "Toggle bar initialized with hash: ${leftToggleBar.hashCode()}")

        setupMaskOverlayUI()

        // Set background styles - use gradient drawables for modern look
        setBackgroundColor(Color.BLACK)
        leftNavigationBar.background =
                ContextCompat.getDrawable(context, R.drawable.nav_bar_background)
        leftToggleBar.background =
                ContextCompat.getDrawable(context, R.drawable.toggle_bar_background)

        // Set up the toggle buttons with explicit configurations
        leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle).apply {
            configureToggleButton(R.string.fa_mobile_screen)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube).apply {
            configureToggleButton(R.string.fa_glasses)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks).apply {
            visibility = View.VISIBLE
            setText(R.string.fa_bookmark)
            setBackgroundResource(R.drawable.nav_button_background)
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
            alpha = 1.0f
            elevation = 2f
            stateListAnimator = null
        }

        // Initialize URL EditTexts
        urlEditText = setupUrlEditText(true)


        // Bring urlEditTextLeft to front
        urlEditText.bringToFront()

        // Disable text handles for both EditTexts
        disableTextHandles(urlEditText)

        //

        // Initialize the edit fields
        leftEditField =
                EditText(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)

                    // Style the edit field
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        rightEditField =
                EditText(context).apply {
                    // Same styling as leftEditField
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        // Add edit fields to view hierarchy
        addView(leftEditField)
        addView(rightEditField)

        leftSystemInfoView =
                SystemInfoView(context).apply {
                    layoutParams =
                            LayoutParams(
                                            200, // Fixed initial width, will be adjusted after
                                            // measure
                                            24
                                    )
                                    .apply { gravity = Gravity.TOP or Gravity.END }
                    elevation = 900f
                    visibility = View.VISIBLE // Explicitly set visibility
                }

        viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
        )

        // Make sure they're above other elements
        leftSystemInfoView.bringToFront()

        post {
            // Ensure bookmarks views are always on top when added to view hierarchy
            if (::leftBookmarksView.isInitialized) {}
            if (::chatView.isInitialized) {
                chatView.bringToFront()
            }
        }

        // Set up the container hierarchy
        leftEyeClipParent.addView(leftEyeUIContainer)
        leftEyeClipParent.addView(
                fullScreenOverlayContainer
        ) // Add to clip parent for proper clipping

        // Add views to UI container
        leftEyeUIContainer.apply {
            // Add views in the correct z-order
            // Add webViewsContainer with correct position
            addView(
                    webViewsContainer,
                    FrameLayout.LayoutParams(640 - toggleBarWidthPx, LayoutParams.MATCH_PARENT)
                            .apply {
                                leftMargin = toggleBarWidthPx // Position after toggle bar
                                bottomMargin = navBarHeightPx // Account for nav bar
                            }
            )
            addView(leftToggleBar)
            // Log.d("ViewDebug", "Toggle bar added to UI container with hash:
            // ${leftToggleBar.hashCode()}")

            addView(leftNavigationBar.apply { elevation = 101f })
            addView(btnShowNavBars) // Add show nav bars button
            addView(progressBar) // Add progress bar
            addView(keyboardContainer)
            addView(dialogContainer)
            addView(leftSystemInfoView)
            addView(urlEditText)
            addView(
                    maskOverlay
            ) // Add mask overlay for proper mirroring to both eyes // Add mask overlay for proper
            // mirroring to both eyes

            // Initialize ChatView here
            chatView =
                    ChatView(context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(560, 420)
                                        .apply { // Slightly smaller than full window
                                            gravity = Gravity.CENTER
                                        }
                        visibility = View.GONE
                        elevation = 2000f // High elevation
                        keyboardListener = this@DualWebViewGroup.keyboardListener
                    }
            addView(chatView)
            chatView.disableSystemKeyboard()

            // Setup listener for Chat button
            leftNavigationBar.findViewById<View>(R.id.btnChat)?.setOnClickListener { toggleChat() }
            postDelayed(
                    {
                        initializeToggleButtons()
                        requestLayout()
                        invalidate()
                    },
                    100
            )

            post {
                leftSystemInfoView.measure(
                        MeasureSpec.makeMeasureSpec(640, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(24, MeasureSpec.EXACTLY)
                )
                leftSystemInfoView.requestLayout()
                leftSystemInfoView.invalidate()
            }

            // Make sure container is visible and properly layered
            visibility = View.VISIBLE
            elevation = 100f // Keep it above webview
        }

        // After other view initializations

        // Add the clip parent to the main view
        addView(leftEyeClipParent)
        addView(rightEyeView) // Keep right eye view separate
        // maskOverlay now added to leftEyeUIContainer above for proper mirroring

        // Create horizontal scroll bar
        horizontalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Left arrow button
                    // Left arrow button
                    val btnLeft =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_left)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnLeft)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 20, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    hScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(60, 16).apply {
                                            gravity = Gravity.CENTER_VERTICAL
                                            leftMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(hScrollThumb)
                    addView(trackContainer)

                    // Right arrow button
                    // Right arrow button
                    val btnRight =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_right)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnRight)

                    // Click handlers
                    btnLeft.setOnClickListener { scrollPageHorizontal(-10) }
                    btnRight.setOnClickListener { scrollPageHorizontal(10) }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullWidth = v.width
                        val thumbWidth = hScrollThumb.width
                        val trackableWidth = fullWidth - thumbWidth

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isInteractingWithScrollBar = true
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isInteractingWithScrollBar = false
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                updateScrollBarThumbs(0, 0)
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Create vertical scroll bar
        verticalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Up arrow button
                    // Up arrow button
                    val btnUp =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_up)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnUp)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 0, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    vScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(16, 60).apply {
                                            gravity = Gravity.CENTER_HORIZONTAL
                                            topMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(vScrollThumb)
                    addView(trackContainer)

                    // Down arrow button
                    // Down arrow button
                    val btnDown =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_down)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnDown)

                    // Click handlers
                    btnUp.setOnClickListener { scrollPageVertical(-10) }
                    btnDown.setOnClickListener { scrollPageVertical(10) }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullHeight = v.height
                        val thumbHeight = vScrollThumb.height
                        val trackableHeight = fullHeight - thumbHeight

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isInteractingWithScrollBar = true
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                Log.d(
                                        "ScrollDebug",
                                        "Vertical Down: y=$clickY, height=$fullHeight, percent=$percent"
                                )

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                // Log.d("ScrollDebug", "Vertical Move: y=$clickY,
                                // percent=$percent")

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isInteractingWithScrollBar = false
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                updateScrollBarThumbs(0, 0)
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Add scroll bars to UI container
        leftEyeUIContainer.addView(
                horizontalScrollBar,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 30).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = 0
                    rightMargin = 30 // Prevent overlap with vertical scroll bar
                    bottomMargin = navBarHeightPx // Sit on top of the nav bar
                }
        )
        leftEyeUIContainer.addView(
                verticalScrollBar,
                FrameLayout.LayoutParams(30, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                    bottomMargin = navBarHeightPx // End at the nav bar
                }
        )

        // Load and apply saved UI scale after view hierarchy is ready
        post {
            val savedScaleProgress =
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .getInt("uiScaleProgress", 100)
            val savedScale = 0.35f + (savedScaleProgress / 100f) * 0.65f
            updateUiScale(savedScale)
        }
    }

    // Track fullscreen toggles for debugging
    private var fullscreenEntryCount = 0
    private var lastFullscreenViewHashCode = 0

    fun showFullScreenOverlay(view: View) {
        fullscreenEntryCount++
        val viewHashCode = view.hashCode()
        val isSameView = viewHashCode == lastFullscreenViewHashCode
        lastFullscreenViewHashCode = viewHashCode


        // Remove from current parent if any
        if (view.parent is ViewGroup) {
            // Log.d("FullscreenDebug", "  Removing view from parent: ${(view.parent as
            // ViewGroup).javaClass.simpleName}")
            (view.parent as ViewGroup).removeView(view)
        }

        // Clear any existing children
        if (fullScreenOverlayContainer.childCount > 0) {
            // Log.d("FullscreenDebug", "  Clearing ${fullScreenOverlayContainer.childCount}
            // existing children from container")
            fullScreenOverlayContainer.removeAllViews()
        }

        // Add the new view
        fullScreenOverlayContainer.addView(
                view,
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        )

        // Log.d("FullscreenDebug", "  View added. Container child count:
        // ${fullScreenOverlayContainer.childCount}")

        previousFullScreenVisibility.clear()
        Log.d("FullscreenDebug", "Hiding ${fullScreenHiddenViews.size} UI elements")
        fullScreenHiddenViews.forEach { target ->
            val name =
                    when (target) {
                        webView -> "webView"
                        leftToggleBar -> "leftToggleBar"
                        leftNavigationBar -> "leftNavigationBar"
                        keyboardContainer -> "keyboardContainer"
                        leftSystemInfoView -> "leftSystemInfoView"
                        urlEditText -> "urlEditText"
                        else -> "unknown"
                    }
            // Log.d("FullscreenDebug", "  Hiding $name (was ${if (target.visibility ==
            // View.VISIBLE) "VISIBLE" else "GONE/INVISIBLE"})")
            previousFullScreenVisibility[target] = target.visibility
            // Use GONE for everything to maximize power saving (remove from layout)
            target.visibility = View.GONE
        }

        fullScreenOverlayContainer.visibility = View.VISIBLE
        fullScreenOverlayContainer.elevation = 2000f
        fullScreenOverlayContainer.bringToFront()

        // Force refresh to ensure the fullscreen content is captured
        post {
            fullScreenOverlayContainer.invalidate()
            fullScreenOverlayContainer.requestLayout()
            startRefreshing()
            // Log.d("FullscreenDebug", "  Post-show refresh triggered")
        }

        // Log.d("FullscreenDebug", "About to call hideSystemUI()")
        hideSystemUI()

        // Power saving: reduce refresh rate and notify listener
        fullscreenListener?.onEnterFullscreen()
        updateRefreshRate()
    }

    fun hideFullScreenOverlay() {

        // Get reference to the view being removed for logging
        val removedView =
                if (fullScreenOverlayContainer.childCount > 0) {
                    fullScreenOverlayContainer.getChildAt(0)
                } else null

        if (removedView != null) {
            // Log.d("FullscreenDebug", "  Removing view: ${removedView.javaClass.simpleName},
            // hashCode: ${removedView.hashCode()}")
        }

        fullScreenOverlayContainer.removeAllViews()

        // Use INVISIBLE instead of GONE to keep the container surface attached
        // This may help prevent surface corruption on second fullscreen entry
        fullScreenOverlayContainer.visibility = View.INVISIBLE
        fullScreenOverlayContainer.elevation = 0f

        previousFullScreenVisibility.forEach { (target, visibility) ->
            val name =
                    when (target) {
                        webView -> "webView"
                        leftToggleBar -> "leftToggleBar"
                        leftNavigationBar -> "leftNavigationBar"
                        keyboardContainer -> "keyboardContainer"
                        leftSystemInfoView -> "leftSystemInfoView"
                        urlEditText -> "urlEditText"
                        else -> "unknown"
                    }
            // Log.d("FullscreenDebug", "  Restoring $name to ${if (visibility == View.VISIBLE)
            // "VISIBLE" else "GONE/INVISIBLE"}")
            target.visibility = visibility
        }
        previousFullScreenVisibility.clear()

        // Force WebView to redraw
        webView.invalidate()
        webView.requestLayout()

        // Force the entire UI container to relayout and redraw
        leftEyeUIContainer.invalidate()
        leftEyeUIContainer.requestLayout()

        // Restart the mirroring refresh
        post {
            startRefreshing()
            // Log.d("FullscreenDebug", "  Post-hide refresh triggered")
        }

        showSystemUI()

        // Restore normal refresh rate and notify listener
        fullscreenListener?.onExitFullscreen()
        updateRefreshRate()

        // Log.d("FullscreenDebug", "hideFullScreenOverlay complete")
    }

    private fun updateRefreshRate() {
        val isFullscreen = fullScreenOverlayContainer.visibility == View.VISIBLE
        // Logic:
        // - Non-anchored: 30fps (33ms)
        // - Anchored + Fullscreen (Video): ~24fps (42ms) for power saving
        // - Anchored + Normal: 60fps (16ms)
        refreshInterval =
                when {
                    isScreenMasked -> maskedRefreshIntervalMs
                    isAnchored && !isFullscreen -> 16L
                    else -> 42L
                }
        // Log.d("PowerSaving", "Refresh rate updated: ${if (refreshInterval == 16L) "60fps" else if
        // (refreshInterval == 33L) "30fps" else "24fps"} (Anchored=$isAnchored,
        // Fullscreen=$isFullscreen)")
    }

    private fun hideSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot hide system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // CRITICAL: Must set decorFitsSystemWindows to false first
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(false)

                    activity.window.insetsController?.let { controller ->
                        controller.hide(
                                android.view.WindowInsets.Type.statusBars() or
                                        android.view.WindowInsets.Type.navigationBars()
                        )
                        controller.systemBarsBehavior =
                                android.view.WindowInsetsController
                                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        Log.d("FullscreenDebug", "System UI hidden (API 30+)")
                    }
                            ?: Log.w("FullscreenDebug", "WindowInsetsController is null!")
                } else {
                    // Older Android versions - Use deprecated flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_FULLSCREEN or
                                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    Log.d("FullscreenDebug", "System UI hidden (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error hiding system UI", e)
            }
        }
    }

    private fun showSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot show system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // Restore decorFitsSystemWindows
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(true)

                    activity.window.insetsController?.show(
                            android.view.WindowInsets.Type.statusBars() or
                                    android.view.WindowInsets.Type.navigationBars()
                    )
                    Log.d("FullscreenDebug", "System UI shown (API 30+)")
                } else {
                    // Older Android versions - Clear flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    Log.d("FullscreenDebug", "System UI shown (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error showing system UI", e)
            }
        }
    }

    fun maskScreen() {
        isScreenMasked = true
        maskOverlay.visibility = View.VISIBLE
        maskOverlay.bringToFront()
        // Hide both cursor views
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye_slash)
        updateRefreshRate()
    }

    fun unmaskScreen() {
        isScreenMasked = false
        maskOverlay.visibility = View.GONE
        // Let MainActivity handle cursor visibility restoration - cursors will be shown
        // if they were visible before masking through updateCursorPosition call
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye)
        updateRefreshRate()
    }

    fun isScreenMasked() = isScreenMasked

    fun dispatchMaskOverlayTouch(screenX: Float, screenY: Float) {
        val location = IntArray(2)
        maskOverlay.getLocationOnScreen(location)
        val scale = uiScale

        // Convert to local coordinates relative to mask overlay
        val localX = screenX - location[0]
        val localY = screenY - location[1]

        // Log.d("MediaControls", "dispatchMaskOverlayTouch at local ($localX, $localY), scale:
        // $scale")

        // Check unmask button hit (account for scale in button dimensions)
        val unmaskLocation = IntArray(2)
        btnMaskUnmask.getLocationOnScreen(unmaskLocation)
        val unmaskWidth = btnMaskUnmask.width * scale
        val unmaskHeight = btnMaskUnmask.height * scale
        if (screenX >= unmaskLocation[0] &&
                        screenX <= unmaskLocation[0] + unmaskWidth &&
                        screenY >= unmaskLocation[1] &&
                        screenY <= unmaskLocation[1] + unmaskHeight
        ) {
            // Log.d("MediaControls", "Unmask button pressed")
            unmaskScreen()
            return
        }

        // Check media control buttons
        if (maskMediaControlsContainer.visibility == View.VISIBLE) {
            val controlsLocation = IntArray(2)
            maskMediaControlsContainer.getLocationOnScreen(controlsLocation)

            // Iterate through children (the media buttons)
            for (i in 0 until maskMediaControlsContainer.childCount) {
                val button = maskMediaControlsContainer.getChildAt(i)
                if (button.visibility != View.VISIBLE) continue

                val btnLocation = IntArray(2)
                button.getLocationOnScreen(btnLocation)
                val btnWidth = button.width * scale
                val btnHeight = button.height * scale

                if (screenX >= btnLocation[0] &&
                                screenX <= btnLocation[0] + btnWidth &&
                                screenY >= btnLocation[1] &&
                                screenY <= btnLocation[1] + btnHeight
                ) {
                    // Log.d("MediaControls", "Media button $i pressed")
                    button.performClick()
                    return
                }
            }
        }

        // Log.d("MediaControls", "Touch on mask overlay but not on any button")
    }

    private fun drawBitmapToSurface() {
        synchronized(bitmapLock) {
            val currentBitmap = bitmap
            if (currentBitmap == null || currentBitmap.isRecycled) {
                return
            }

            var canvas: Canvas? = null
            try {
                canvas = rightEyeView.holder.lockCanvas()
                canvas?.let { it.drawBitmap(currentBitmap, 0f, 0f, null) }
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error drawing bitmap to surface", e)
            } finally {
                if (canvas != null) {
                    try {
                        rightEyeView.holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e("DualWebViewGroup", "Error unlocking canvas", e)
                    }
                }
            }
        }
    }

    fun getCurrentLinkText(): String {
        return urlEditText.text.toString()
    }

    fun toggleIsUrlEditing(isEditing: Boolean) {
        _isUrlEditing = isEditing
        // Log.d("LinkEditing", "DualWebViewGroup isUrlEditing toggled to: $isEditing")
    }

    fun setLinkText(text: String, newCursorPosition: Int = -1) {
        urlEditText.setText(text)

        // If no specific cursor position requested, maintain current position
        val cursorPos =
                if (newCursorPosition >= 0) {
                    // Ensure requested position doesn't exceed text length
                    minOf(newCursorPosition, text.length)
                } else {
                    // Keep current cursor position but ensure it's valid
                    minOf(urlEditText.selectionStart, text.length)
                }

        urlEditText.setSelection(cursorPos)
    }

    fun adjustViewportAndFields(adjustment: Float) {
        // Apply adjustment to all elements
        // translationY = adjustment // Don't move the entire group, just children
        webView.translationY = adjustment
        urlEditText.translationY = adjustment
        dialogContainer.translationY = adjustment

        if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
            // Ensure bookmarks view stays above keyboard
            leftBookmarksView.translationY = adjustment

            // Get the current edit field from bookmarks view
            val editField = leftBookmarksView.getCurrentEditField()
            editField?.translationY = adjustment
        }
    }

    fun getCurrentUrlEditField(): EditText? {
        return if (_isUrlEditing) urlEditText else null
    }

    fun animateViewportAdjustment() {
        webView.animate().setDuration(200).translationY(webView.translationY).start()
    }

    // Method to show link editing UI
    fun showLinkEditing() {
        if (!_isUrlEditing) {
            _isUrlEditing = true

            val currentUrl = webView.url ?: ""
            urlEditText.apply {
                text.clear()
                append(currentUrl)
                visibility = View.VISIBLE
                requestFocus()
                setSelection(text.length)
                bringToFront()
            }

            keyboardListener?.onShowKeyboard()
        }
    }

    fun isUrlEditing(): Boolean {
        // Log.d("LinkEditing", "isUrlEditing check, value: $isUrlEditing")
        return _isUrlEditing
    }

    fun isBookmarksExpanded(): Boolean {
        return leftBookmarksView.visibility == View.VISIBLE
    }

    private fun toggleChat() {
        if (chatView.visibility == View.VISIBLE) {
            chatView.visibility = View.GONE
        } else {
            chatView.visibility = View.VISIBLE
            chatView.bringToFront()
            maybePromptForGroqApiKey()
        }
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun maybePromptForGroqApiKey() {
        if (dialogContainer.visibility == View.VISIBLE) return
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("groq_api_key", null)?.trim()
        if (!currentKey.isNullOrBlank()) return

        showPromptDialog(
                "Enter Groq API Key",
                currentKey,
                { key ->
                    val trimmed = key.trim()
                    if (trimmed.isBlank()) {
                        showToast("API Key Required")
                        post { maybePromptForGroqApiKey() }
                        return@showPromptDialog
                    }
                    prefs.edit().putString("groq_api_key", trimmed).apply()
                    showToast("API Key Saved")
                    keyboardListener?.onHideKeyboard()
                },
                { showToast("API Key Required") }
        )
    }

    private fun toggleBookmarks() {
        leftBookmarksView.toggle()

        if (leftBookmarksView.visibility == View.VISIBLE) {
            leftBookmarksView.bringToFront()
            leftBookmarksView.elevation = 1000f

            // Force immediate refresh to ensure mirroring
            post {
                invalidate()
                startRefreshing()
            }
        }

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleBookmarkTap(): Boolean {
        if (leftBookmarksView.visibility != View.VISIBLE) {
            // Log.d("BookmarksDebug", "No tap handling - bookmarks not visible")
            return false
        }

        // Let BookmarksView handle the tap
        val handled = leftBookmarksView.handleTap()
        if (handled) {
            // Force refresh to update the mirrored view
            startRefreshing()
        }
        return handled
    }

    fun handleBookmarkDoubleTap(): Boolean {
        return if (leftBookmarksView.visibility == View.VISIBLE) {
            // Log.d("BookmarksDebug", "handleBookmarkDoubleTap() called.
            // leftVisibility=${leftBookmarksView.visibility}")
            val handled = leftBookmarksView.handleDoubleTap()
            if (handled) {
                leftBookmarksView.logStackTrace(
                        "BookmarksDebug",
                        "handleBookmarkDoubleTap(): double tap handled"
                )
                // Force refresh to update the mirrored view
                startRefreshing()
            }
            handled
        } else false
    }

    fun getBookmarksView(): BookmarksView {
        return leftBookmarksView
    }

    // Provide WebView access
    @SuppressLint("SetJavaScriptEnabled")
    fun getWebView(): WebView {
        return webView.apply {
            val settings = this.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false

            // Clean up legacy JS interface - we use URL scheme now
            // addJavascriptInterface(WebAppInterface(context, this), "Android")

            // Set User Agent

            // Set User Agent
            // settings.userAgentString = desktopUserAgent // Default to Desktop
        }
    }

    private fun setupUrlEditText(isRight: Boolean = false): EditText {
        return EditText(context).apply {
            layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.TOP
                            )
                            .apply {
                                leftMargin = toggleBarWidthPx // Single margin for left side
                            }
            setBackgroundColor(Color.parseColor("#202020"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 12, 32, 12)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = urlFieldMinHeight
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            highlightColor = Color.parseColor("#404040")

            // Set hardware acceleration for better cursor rendering
            setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    Paint().apply {
                        color = Color.WHITE // Set cursor color to white
                    }
            )

            // Set hardware acceleration for better cursor rendering
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Make both EditTexts share focus state
            setOnFocusChangeListener { _, hasFocus ->
                if (isRight && hasFocus) {
                    urlEditText.requestFocus()
                }
            }

            // Add text change listener to sync content
            addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                        ) {}
                        override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                        ) {}
                        override fun afterTextChanged(s: Editable?) {}
                    }
            )
        }
    }

    // Set up the bitmap for capturing content
    private fun setupBitmap(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        synchronized(bitmapLock) {
            try {
                bitmap?.let { oldBitmap ->
                    if (!oldBitmap.isRecycled) {
                        oldBitmap.recycle()
                    }
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error creating bitmap", e)
                bitmap = null
            }
        }
    }

    fun updateLeftEyePosition(xOffset: Float, yOffset: Float, rotationDeg: Float) {

        // Store the translations
        _translationX = yOffset
        _translationY = xOffset

        // If you also want to store rotation in a field:
        _rotationZ = rotationDeg

        leftEyeUIContainer.translationX = yOffset
        leftEyeUIContainer.translationY = xOffset
        leftEyeUIContainer.rotation = rotationDeg

        // Only apply same transformations to full screen overlay when it's actually visible
        // This prevents the video from being positioned incorrectly when fullscreen is activated
        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenOverlayContainer.translationX = yOffset
            fullScreenOverlayContainer.translationY = xOffset
            fullScreenOverlayContainer.rotation = rotationDeg
        } else {
            // Keep at zero when not visible to ensure clean state
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            fullScreenOverlayContainer.rotation = 0f
        }

        // Pass the fixed screen cursor position to hover detection
        // In anchored mode, the cursor is visually fixed at the center (320, 240)
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)
        val screenX = 320f + containerLocation[0]
        val screenY = 240f + containerLocation[1]

        updateButtonHoverStates(screenX, screenY)

        // Ensure visual cursor scale/visibility is refreshed in anchored mode
        listener?.onCursorPositionChanged(320f, 240f, true)

        // Only do expensive operations occasionally, not every frame
        // The Choreographer already ensures smooth vsync timing
        if (!isRefreshing) {
            post { startRefreshing() }
        }
    }

    // Capture and mirror content to left SurfaceView
    private fun captureLeftEyeContent() {
        if (!isRefreshing) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < MIN_CAPTURE_INTERVAL) {
            return
        }

        synchronized(bitmapLock) {
            try {
                val halfWidth = width / 2
                val currentBitmap = bitmap

                if (currentBitmap == null ||
                                currentBitmap.isRecycled ||
                                currentBitmap.width != halfWidth ||
                                currentBitmap.height != height
                ) {
                    setupBitmap(halfWidth, height)
                }

                // Get the current bitmap after potential setup
                val bitmapToUse = bitmap ?: return

                // Check scrollbar visibility periodically (once per second)
                // Skip if in fullscreen mode to save power
                val isFullScreen = fullScreenOverlayContainer.visibility == View.VISIBLE

                if (!isFullScreen && currentTime - lastScrollBarCheckTime > 1000) {
                    if (!shouldFreezeScrollBars()) {
                        updateScrollBarsVisibility()
                    }
                    lastScrollBarCheckTime = currentTime
                }

                // Force cursor refresh if editing - skip in fullscreen
                if (!isFullScreen && _isUrlEditing && urlEditText.isFocused) {
                    urlEditText.invalidate()
                }

                val captureRect = android.graphics.Rect(0, 0, halfWidth, height)
                val window = (context as Activity).window

                PixelCopy.request(
                        window,
                        captureRect,
                        bitmapToUse,
                        { copyResult ->
                            // Log PixelCopy result for debugging
                            if (copyResult != PixelCopy.SUCCESS) {
                                Log.w("MirrorDebug", "PixelCopy failed with result: $copyResult")
                            }

                            if (copyResult == PixelCopy.SUCCESS && isRefreshing) {
                                synchronized(bitmapLock) {
                                    if (!bitmapToUse.isRecycled && bitmap === bitmapToUse) {
                                        drawBitmapToSurface()
                                        lastCaptureTime = System.currentTimeMillis()
                                    } else {
                                        Log.w(
                                                "MirrorDebug",
                                                "Bitmap state issue - recycled: ${bitmapToUse.isRecycled}, same: ${bitmap === bitmapToUse}"
                                        )
                                    }
                                }
                            }
                        },
                        refreshHandler
                )
            } catch (e: Exception) {
                Log.e("MirrorDebug", "Error capturing content", e)
                stopRefreshing()
            }
        }
    }

    fun onKeyboardHidden() {
        // Reset views when keyboard is hidden
        post {
            requestLayout()
            invalidate()

            // Force bitmap recreation with new dimensions
            // setupBitmap(webView.width, height - 48)

            // Ensure mirroring is updated
            startRefreshing()
        }
    }

    fun syncKeyboardStates() {
        customKeyboard?.let { Kb ->

            // Force update of the keyboard
            Kb.post {
                Kb.invalidate()
                Kb.requestLayout()
                keyboardContainer.invalidate()
                keyboardContainer.requestLayout()
            }
        }
    }

    // Refresh handling
    private var refreshCount = 0
    private var lastRefreshLogTime = 0L

    private val refreshRunnable =
            object : Runnable {
                override fun run() {
                    refreshCount++

                    // Log every 2 seconds to avoid spam
                    val now = System.currentTimeMillis()
                    if (now - lastRefreshLogTime > 2000) {
                        // Log.d("MirrorDebug", "RefreshLoop running, count=$refreshCount,
                        // isRefreshing=$isRefreshing,
                        // webViewAttached=${webView.isAttachedToWindow},
                        // fsOverlayVisible=${fullScreenOverlayContainer.visibility ==
                        // View.VISIBLE}")
                        lastRefreshLogTime = now
                    }

                    if (isRefreshing && webView.isAttachedToWindow) {
                        captureLeftEyeContent()
                        refreshHandler.postDelayed(this, refreshInterval)
                    } else {
                        Log.w(
                                "MirrorDebug",
                                "RefreshLoop STOPPING! isRefreshing=$isRefreshing, webViewAttached=${webView.isAttachedToWindow}"
                        )
                    }
                }
            }

    fun startRefreshing() {
        synchronized(refreshLock) {
            if (!isRefreshing) {
                isRefreshing = true
                refreshHandler.removeCallbacks(refreshRunnable)
                refreshHandler.post(refreshRunnable)
            }
        }
    }

    fun stopRefreshing() {
        synchronized(refreshLock) {
            isRefreshing = false
            refreshHandler.removeCallbacks(refreshRunnable)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

        // Hardcoded eye resolution - 640x480 per eye
        val eyeWidth = 640
        val eyeHeight = 480
        val halfWidth = eyeWidth // Each eye is 640px wide

        val toggleBarWidth = toggleBarWidthPx
        val navBarHeight = navBarHeightPx

        // Ensure toggle bar is measured correctly
        leftToggleBar.measure(
                MeasureSpec.makeMeasureSpec(toggleBarWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(eyeHeight - navBarHeight, MeasureSpec.EXACTLY)
        )
        if (leftToggleBar.visibility != View.VISIBLE) {
            leftToggleBar.visibility = View.VISIBLE
        }

        // Ensure navigation bar is measured correctly
        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // Force a layout pass on the container if needed
        if (leftToggleBar.measuredWidth == 0) {
            leftEyeUIContainer.requestLayout()
        }

        val height = b - t
        // Use actual measured height of keyboard if visible, otherwise default
        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160
        // Keyboard width is same regardless of mode (matches original keyboard size)
        val keyboardWidth = halfWidth - toggleBarWidth

        // Position the WebView differently based on scroll mode
        // Shrink the WebView when keyboard is visible so content isn't blocked
        val isKeyboardVisible = keyboardContainer.visibility == View.VISIBLE

        if (isInScrollMode) {
            val keyboardLimit =
                    if (isKeyboardVisible) {
                        eyeHeight - keyboardHeight // Shrink to fit above keyboard
                    } else {
                        480
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webViewsContainer.measuredHeight

            webViewsContainer.layout(
                    0, // No left margin in scroll mode
                    0,
                    0 + webViewsContainer.measuredWidth, // Full width minus margins
                    minOf(keyboardLimit, measuredBottom)
            )
        } else {
            val navBarTop = eyeHeight - navBarHeight

            val keyboardLimit =
                    if (isKeyboardVisible) {
                        minOf(navBarTop, eyeHeight - keyboardHeight) // Shrink to fit above keyboard
                    } else {
                        navBarTop // Default bottom for 30px nav bar
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webViewsContainer.measuredHeight

            webViewsContainer.layout(
                    toggleBarWidth, // Account for toggle bar
                    0,
                    toggleBarWidth +
                            webViewsContainer.measuredWidth, // Standard width + toggle bar offset
                    minOf(keyboardLimit, measuredBottom)
            )
        }

        // Calculate available content height based on keyboard visibility
        val contentHeight =
                if (keyboardContainer.visibility == View.VISIBLE) {
                    eyeHeight - keyboardHeight
                } else {
                    eyeHeight - navBarHeight
                }

        // Layout the clip parent - hardcoded 640x480
        leftEyeClipParent.layout(
                0, // After toggle bar
                0,
                eyeWidth, // Fixed width for left eye
                eyeHeight
        )

        fullScreenOverlayContainer.layout(
                0, // Relative to leftEyeClipParent
                0,
                halfWidth, // 640px width (matches clip parent)
                eyeHeight
        )

        // Position SurfaceView exactly like WebView but offset horizontally for right eye
        rightEyeView.layout(eyeWidth, 0, eyeWidth * 2, eyeHeight)

        // Layout toggle bar - height is eyeHeight minus navBarHeight
        leftToggleBar.layout(0, 0, toggleBarWidth, eyeHeight - navBarHeight)
        //            Log.d("ToggleBarDebug", """
        //        Toggle Bar Layout:
        //        Visibility: ${leftToggleBar.visibility}
        //        Width: $toggleBarWidth
        //        Height: 596
        //        Background: ${leftToggleBar.background}
        //        Parent: ${leftToggleBar.parent?.javaClass?.simpleName}
        //    """.trimIndent())

        val keyboardY = eyeHeight - keyboardHeight
        keyboardContainer.layout(
                toggleBarWidth,
                keyboardY,
                toggleBarWidth + keyboardWidth,
                eyeHeight
        )

        // Position ProgressBar - at bottom in scroll mode, above nav bar otherwise
        val progressBarHeight = 4
        if (isInScrollMode) {
            // In scroll mode, position at very bottom, full width
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - progressBarHeight
                progressBar.layout(0, pbY, halfWidth, eyeHeight)
                progressBar.bringToFront()
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        } else {
            // Normal mode - position above navigation bar
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - navBarHeight - progressBarHeight
                progressBar.layout(toggleBarWidth, pbY, halfWidth, pbY + progressBarHeight)
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        }

        // Hide navigation bars
        leftNavigationBar.visibility = View.GONE

        if (keyboardContainer.visibility == View.VISIBLE) {
            // Position keyboards at the bottom
            // In scroll mode, center keyboard (no toggle bar offset)
            val kbLeft =
                    if (isInScrollMode) {
                        (halfWidth - keyboardWidth) / 2 // Center in left half
                    } else {
                        toggleBarWidth
                    }
            keyboardContainer.layout(kbLeft, keyboardY, kbLeft + keyboardWidth, eyeHeight)

            // Hide navigation bars
            leftNavigationBar.visibility = View.GONE

            // Position bookmarks menu if visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                val bookmarksHeight = leftBookmarksView.measuredHeight
                val isEditingAnywhere = _isUrlEditing || leftBookmarksView.isEditing()
                val bookmarksY =
                        if (isEditingAnywhere) {
                            40 // Below URL edit field area / top of screen
                        } else {
                            keyboardY - bookmarksHeight
                        }

                // Constrain bottom to keyboardY to avoid overlapping with keyboard
                val bookmarksBottom =
                        if (isEditingAnywhere) {
                            minOf(bookmarksY + bookmarksHeight, keyboardY)
                        } else {
                            bookmarksY + bookmarksHeight
                        }

                leftBookmarksView.layout(
                        toggleBarWidth,
                        bookmarksY,
                        toggleBarWidth + 480,
                        bookmarksBottom
                )

                leftBookmarksView.bringToFront()
            }

            // Handle edit fields for both URL and bookmark editing
            if (_isUrlEditing || isBookmarkEditing) {
                val editFieldHeight = maxOf(urlFieldMinHeight, urlEditText.measuredHeight)
                val editFieldLeft = keyboardContainer.left.takeIf { it > 0 } ?: toggleBarWidth
                val editFieldRight =
                        keyboardContainer.right.takeIf { it > editFieldLeft }
                                ?: (editFieldLeft + keyboardWidth)

                // Position left edit field only
                urlEditText.apply {
                    layout(editFieldLeft, 0, editFieldRight, editFieldHeight)
                    translationY = (keyboardY - editFieldHeight).toFloat()
                    visibility = View.VISIBLE
                    elevation = 1001f
                }
            }

            // Ensure keyboard containers are on top but below edit fields
            keyboardContainer.elevation = 1000f
        } else {
            // Log.d("EditFieldDebug", "Skipping edit field positioning - conditions not met")

            // Hide keyboard containers
            keyboardContainer.layout(
                    toggleBarWidth,
                    eyeHeight,
                    toggleBarWidth + keyboardWidth,
                    eyeHeight + keyboardHeight
            )

            // Position bookmarks when keyboard is not visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                leftBookmarksView.layout(
                        toggleBarWidth,
                        30,
                        toggleBarWidth + 480,
                        eyeHeight - navBarHeight
                )
            }

            // Show and position navigation bars - hardcoded to bottom of 480px eye
            leftNavigationBar.visibility = View.VISIBLE
            leftNavigationBar.layout(0, eyeHeight - navBarHeight, halfWidth, eyeHeight)
        }

        // Update bitmap capture when layout changes
        if (changed) {
            post {
                setupBitmap(webView.width, contentHeight)
                startRefreshing()
            }
        }

        // Hide system info bar in scroll mode, show otherwise
        if (isInScrollMode) {
            leftSystemInfoView.visibility = View.GONE
        } else {
            leftSystemInfoView.visibility = View.VISIBLE
            // Calculate system info bar position
            val infoBarHeight = 24
            val infoBarY = eyeHeight - navBarHeight - infoBarHeight // Position above nav bar

            // First measure the info views to get their width
            leftSystemInfoView.measure(
                    MeasureSpec.makeMeasureSpec(320, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(infoBarHeight, MeasureSpec.EXACTLY)
            )

            val infoBarWidth = leftSystemInfoView.measuredWidth
            val leftX =
                    (halfWidth - infoBarWidth) / 2 +
                            toggleBarWidth // Center in left half, account for toggle bar

            // Position the info bars
            leftSystemInfoView.layout(
                    leftX,
                    infoBarY,
                    leftX + infoBarWidth,
                    infoBarY + infoBarHeight
            )
        }

        // Position Dialog Container (Center it in the left view)
        if (dialogContainer.visibility != View.GONE) {
            val dialogWidth = 500

            // Measure the dialog container first if needed
            dialogContainer.measure(
                    MeasureSpec.makeMeasureSpec(dialogWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(eyeHeight, MeasureSpec.AT_MOST)
            )

            val measuredH = dialogContainer.measuredHeight

            val dialogLeft = toggleBarWidth + (keyboardWidth - dialogWidth) / 2

            // Calculate available vertical space, respecting the keyboard if it is visible
            val availableHeight =
                    if (keyboardContainer.visibility == View.VISIBLE) {
                        eyeHeight - keyboardHeight
                    } else {
                        eyeHeight
                    }
            // Center the dialog within the available space
            val dialogTop = (availableHeight - measuredH) / 2

            dialogContainer.layout(
                    dialogLeft,
                    dialogTop,
                    dialogLeft + dialogWidth,
                    dialogTop + measuredH
            )
            dialogContainer.elevation = 2000f
            dialogContainer.bringToFront()
        }

        // Layout maskOverlay to cover left eye only (will be mirrored to right eye)
        maskOverlay.layout(0, 0, halfWidth, height)

        // Layout the unhide button when in scroll mode
        if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) {
            val btnSize = 40
            val btnRight = halfWidth - 8 // 8px margin from right
            val btnBottom = height - 8 // 8px margin from bottom
            btnShowNavBars.layout(btnRight - btnSize, btnBottom - btnSize, btnRight, btnBottom)
            btnShowNavBars.bringToFront()
        }

        // Layout scroll bars for non-anchored mode
        // Eye button size is 40px with 8px margin from bottom/right, so reserve 48px for it
        val eyeButtonSpace =
                if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) 48 else 0

        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val hScrollHeight = 20
            val hScrollY =
                    if (isInScrollMode) eyeHeight - hScrollHeight
                    else eyeHeight - navBarHeight - hScrollHeight // At bottom in scroll mode

            val scrollLeft = if (isInScrollMode) 0 else toggleBarWidth
            var scrollWidth =
                    if (isInScrollMode) halfWidth - eyeButtonSpace else halfWidth - toggleBarWidth

            // Prevent overlap with vertical scrollbar if visible
            if (verticalScrollBar.visibility == View.VISIBLE) {
                scrollWidth -= 20 // Subtract width of vertical scrollbar
            }

            horizontalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(scrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(hScrollHeight, MeasureSpec.EXACTLY)
            )
            horizontalScrollBar.layout(
                    scrollLeft,
                    hScrollY,
                    scrollLeft + scrollWidth,
                    hScrollY + hScrollHeight
            )
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val vScrollWidth = 20
            val vScrollRight = halfWidth // Align to right edge
            val vScrollTop = 0 // Start from top

            // In scroll mode, stop above eye button. Normal mode, stop at nav bar.
            val vScrollBottom =
                    if (isInScrollMode) eyeHeight - eyeButtonSpace else eyeHeight - navBarHeight
            val vScrollHeight = vScrollBottom - vScrollTop

            verticalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(vScrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vScrollHeight, MeasureSpec.EXACTLY)
            )
            verticalScrollBar.layout(
                    vScrollRight - vScrollWidth,
                    vScrollTop,
                    vScrollRight,
                    vScrollTop + vScrollHeight
            )
        }

        // Layout the UI container to cover just the left half
        leftEyeUIContainer.layout(0, 0, halfWidth, height)

        if (::chatView.isInitialized &&
                        chatView.visibility == View.VISIBLE &&
                        keyboardContainer.visibility == View.VISIBLE
        ) {
            val chatMargin = 8.dp()
            val availableHeight = (eyeHeight - keyboardHeight - chatMargin).coerceAtLeast(0)
            val baseWidth =
                    chatView.layoutParams.width.takeIf { it > 0 }
                            ?: chatView.measuredWidth.takeIf { it > 0 } ?: 560
            val baseHeight =
                    chatView.layoutParams.height.takeIf { it > 0 }
                            ?: chatView.measuredHeight.takeIf { it > 0 } ?: 420
            val targetWidth = baseWidth.coerceAtMost(halfWidth)
            val targetHeight = baseHeight.coerceAtMost(availableHeight)

            if (targetHeight > 0) {
                chatView.measure(
                        MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
                )
                val left = (halfWidth - targetWidth) / 2
                val bottom = eyeHeight - keyboardHeight - chatMargin
                val top = bottom - chatView.measuredHeight
                chatView.layout(left, top, left + targetWidth, bottom)
            }
        }
    }

    fun cleanupResources() {
        stopRefreshing()
        synchronized(bitmapLock) {
            bitmap?.let { currentBitmap ->
                if (!currentBitmap.isRecycled) {
                    currentBitmap.recycle()
                }
            }
            bitmap = null
        }
        System.gc() // Request garbage collection
    }

    fun getCurrentEditText(): String {
        return urlEditText.text.toString()
    }

    fun hideLinkEditing() {
        _isUrlEditing = false
        isBookmarkEditing = false

        urlEditText.apply {
            clearFocus()
            visibility = View.GONE
            elevation = 0f
        }

        post {
            startRefreshing()
            requestLayout()
            invalidate()
        }
    }

    private fun EditText.setOnSelectionChangedListener(listener: (Int, Int) -> Unit) {
        try {
            val field = TextView::class.java.getDeclaredField("mEditor")
            field.isAccessible = true
            val editor = field.get(this)

            val listenerField = editor.javaClass.getDeclaredField("mSelectionChangedListener")
            listenerField.isAccessible = true
            listenerField.set(
                    editor,
                    object : Any() {
                        fun onSelectionChanged(selStart: Int, selEnd: Int) {
                            listener(selStart, selEnd)
                        }
                    }
            )
        } catch (e: Exception) {
            Log.e("DualWebViewGroup", "Error setting selection listener", e)
        }
    }

    fun showInfoBars() {
        leftSystemInfoView.visibility = View.VISIBLE
    }

    fun hideInfoBars() {
        leftSystemInfoView.visibility = View.GONE
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    // Add keyboard mirror handling
    fun setKeyboard(originalKeyboard: CustomKeyboardView) {
        // Log.d("KeyboardDebug", "setKeyboard called with keyboard:
        // ${originalKeyboard.hashCode()}")

        // Clear container
        keyboardContainer.removeAllViews()

        // Clear animations
        keyboardContainer.clearAnimation()
        webView.clearAnimation()
        rightEyeView.clearAnimation()

        // Reset translations
        keyboardContainer.translationY = 0f
        webView.translationY = 0f
        rightEyeView.translationY = 0f

        // Set keyboard
        customKeyboard = originalKeyboard
        customKeyboard?.setAnchoredMode(isAnchored)
        keyboardContainer.addView(
                originalKeyboard,
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { gravity = Gravity.BOTTOM }
        )

        // Explicitly set visibility based on keyboard's current state
        val visibility =
                if (originalKeyboard.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        keyboardContainer.visibility = visibility

        // Hide navigation bars when keyboard is visible
        if (visibility == View.VISIBLE) {
            leftNavigationBar.visibility = View.GONE
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Force redraw of toggle buttons
        leftToggleBar.findViewById<View>(R.id.btnModeToggle)?.invalidate()
    }

    private fun getCursorInContainerCoords(): Pair<Float, Float> {
        // Calculate the actual screen position of the cursor first
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)

        val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

        val visualX = 320f + (lastCursorX - 320f) * uiScale + transX
        val visualY = 240f + (lastCursorY - 240f) * uiScale + transY

        val screenX = visualX + containerLocation[0]
        val screenY = visualY + containerLocation[1]

        return computeAnchoredCoordinates(screenX, screenY)
    }

    private fun computeAnchoredKeyboardCoordinates(): Pair<Float, Float>? {
        val keyboard = keyboardContainer
        if (keyboard.width == 0 || keyboard.height == 0) {
            // Log.d("TouchDebug", "computeAnchoredKeyboardCoordinates: keyboard not laid out")
            return null
        }

        val (adjustedX, adjustedY) = getCursorInContainerCoords()

        val keyboardLocation = IntArray(2)
        keyboard.getLocationOnScreen(keyboardLocation)
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)
        val localXContainer = adjustedX - keyboard.x
        val localYContainer = adjustedY - keyboard.y

        val kbView = customKeyboard ?: return null

        val localX = localXContainer - kbView.x
        val localY = localYContainer - kbView.y


        return Pair(localX, localY)
    }

    private fun computeAnchoredCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        val parent = leftEyeUIContainer.parent as View
        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val relativeX = screenX - parentLocation[0]
        val relativeY = screenY - parentLocation[1]

        val points = floatArrayOf(relativeX, relativeY)

        val inverse = android.graphics.Matrix()
        leftEyeUIContainer.matrix.invert(inverse)
        inverse.mapPoints(points)

        return Pair(points[0], points[1])
    }

    private fun isTouchOnView(view: View, x: Float, y: Float): Boolean {
        return view.visibility == View.VISIBLE &&
                x >= view.left &&
                x <= view.right &&
                y >= view.top &&
                y <= view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Log.d("GestureDebug", "DualWebViewGroup onInterceptTouchEvent: ${ev.action}")

        // Let windows overview handle its own touch events
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            return false // Don't intercept, let children handle touches
        }

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            // Allow interactions with menus that are on top
            if (::leftBookmarksView.isInitialized && isTouchOnView(leftBookmarksView, ev.x, ev.y)) {
                return false
            }

            fullScreenTapDetector.onTouchEvent(ev)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            var isOverTarget = false
            val (cursorX, cursorY) = getCursorInContainerCoords()

            // Check Keyboard
            if (keyboardContainer.visibility == View.VISIBLE) {
                val localCoords = computeAnchoredKeyboardCoordinates()
                if (localCoords != null) {
                    val (localX, localY) = localCoords
                    if (localX >= 0 &&
                                    localX <= keyboardContainer.width &&
                                    localY >= 0 &&
                                    localY <= keyboardContainer.height
                    ) {
                        isOverTarget = true
                        anchoredTarget = 1
                    }
                }
            }

            // Check Bookmarks (if not already over keyboard)
            if (!isOverTarget &&
                            ::leftBookmarksView.isInitialized &&
                            leftBookmarksView.visibility == View.VISIBLE
            ) {
                if (cursorX >= leftBookmarksView.left &&
                                cursorX <= leftBookmarksView.right &&
                                cursorY >= leftBookmarksView.top &&
                                cursorY <= leftBookmarksView.bottom
                ) {
                    isOverTarget = true
                    anchoredTarget = 2
                    // Log.d("TouchDebug", "Intercepting anchored tap for bookmarks")
                }
            }

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    anchoredGestureActive = isOverTarget
                    if (anchoredGestureActive) {
                        anchoredTouchStartX = cursorX
                        anchoredTouchStartY = cursorY
                        lastAnchoredY = cursorY
                        isAnchoredDrag = false
                        // Log.d("TouchDebug", "Intercepting anchored ACTION_DOWN
                        // target=$anchoredTarget")
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) return true
                    if (isOverTarget) {
                        anchoredGestureActive = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (anchoredGestureActive || isOverTarget) {
                        // Log.d("TouchDebug", "Intercepting anchored ACTION_UP")
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
            return false
        }

        // Non-anchored keyboard handling
        if (keyboardContainer.visibility == View.VISIBLE && !isAnchored) {
            return true
        }

        // Non-anchored bookmarks handling
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            return true
        }

        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val halfWidth = widthSize / 2
        val navBarHeight = navBarHeightPx
        val toggleBarWidth = toggleBarWidthPx
        val keyboardWidth = halfWidth - toggleBarWidth

        // Measure keyboard container first to get its actual height
        keyboardContainer.measure(
                MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160

        val contentHeight =
                if (keyboardContainer.visibility == View.VISIBLE) {
                    heightSize - keyboardHeight
                } else {
                    heightSize - navBarHeight
                }

        // Measure WebView with different dimensions based on scroll mode
        // FIX: Respect the LayoutParams set by updateScrollBarsVisibility
        val lp = webViewsContainer.layoutParams

        if (isInScrollMode) {
            val targetWidth = if (lp != null && lp.width > 0) lp.width else 640
            val targetHeight = if (lp != null && lp.height > 0) lp.height else 480

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        } else {
            // Normal Mode
            // Use layout params if available (set by updateScrollBarsVisibility)
            // Default fallback: 640 - toggle bar = width, content height
            val targetWidth = if (lp != null && lp.width > 0) lp.width else (640 - toggleBarWidth)

            // For height in normal mode, we used MATCH_PARENT in updateScrollBarsVisibility
            // usually,
            // but sometimes explicit. If MATCH_PARENT (-1), we use the calculated contentHeight.
            val targetHeight =
                    if (lp != null && lp.height > 0) lp.height
                    else if (contentHeight > 0) contentHeight else 440

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        }

        // Rest of the measuring code remains the same
        rightEyeView.measure(
                MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        )

        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // keyboardContainer is already measured above, but we can measure it again with EXACTLY if
        // we want to enforce constraints,
        // but UNSPECIFIED allowed it to size itself. Let's stick to the measurement we did.

        fullScreenOverlayContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        maskOverlay.measure(
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure leftEyeUIContainer and its children
        leftEyeUIContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure windowsOverviewContainer if visible
        windowsOverviewContainer?.let { woc ->
            if (woc.visibility == View.VISIBLE) {
                val containerWidth = 640 - toggleBarWidthPx
                val containerHeight = heightSize - navBarHeightPx
                woc.measure(
                        MeasureSpec.makeMeasureSpec(containerWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(containerHeight, MeasureSpec.EXACTLY)
                )
            }
        }

        // Measure leftEyeClipParent
        leftEyeClipParent.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(widthSize, heightSize)
    }

    // at class top
    private var downWhen = 0L
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kbVisible = (keyboardContainer.visibility == View.VISIBLE)

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenTapDetector.onTouchEvent(event)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            // Track velocity for anchored interactions (bookmarks scroll, etc.)
            if (velocityTracker == null) {
                velocityTracker = android.view.VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (anchoredGestureActive) return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) {
                        val (cursorX, cursorY) = getCursorInContainerCoords()

                        // Check for drag threshold
                        if (!isAnchoredDrag) {
                            val dx = kotlin.math.abs(cursorX - anchoredTouchStartX)
                            val dy = kotlin.math.abs(cursorY - anchoredTouchStartY)
                            if (dx > ANCHORED_TOUCH_SLOP || dy > ANCHORED_TOUCH_SLOP) {
                                isAnchoredDrag = true
                            }
                        }

                        if (isAnchoredDrag && anchoredTarget == 2) { // Bookmarks
                            val deltaY = lastAnchoredY - cursorY
                            if (::leftBookmarksView.isInitialized &&
                                            leftBookmarksView.visibility == View.VISIBLE
                            ) {
                                leftBookmarksView.handleAnchoredSwipe(deltaY)
                            }
                        }

                        lastAnchoredY = cursorY
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val wasTracking = anchoredGestureActive
                    anchoredGestureActive = false

                    if (wasTracking) {
                        if (!isAnchoredDrag) {
                            val (cursorX, cursorY) = getCursorInContainerCoords()

                            // Dispatch tap based on target determined at ACTION_DOWN
                            when (anchoredTarget) {
                                1 -> { // Keyboard
                                    // Managed by MainActivity dispatchKeyboardTap
                                }
                                2 -> { // Bookmarks
                                    if (::leftBookmarksView.isInitialized &&
                                                    leftBookmarksView.visibility == View.VISIBLE
                                    ) {
                                        // Log.d("TouchDebug", "Dispatching anchored tap to
                                        // bookmarks")
                                        leftBookmarksView.handleAnchoredTap(
                                                cursorX - leftBookmarksView.left,
                                                cursorY - leftBookmarksView.top
                                        )
                                    }
                                }
                            }
                        } else if (anchoredTarget == 2) {
                            // Anchored Fling for Bookmarks
                            velocityTracker?.computeCurrentVelocity(1000)
                            val velocityY = velocityTracker?.yVelocity ?: 0f
                            // Pass raw velocityY.
                            handleAnchoredFling(velocityY)
                        }
                    }

                    anchoredTarget = 0
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (wasTracking) return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downWhen = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dur = event.eventTime - downWhen
                val travelX = kotlin.math.abs(event.x - downX)
                val travelY = kotlin.math.abs(event.y - downY)
                val wasTap = dur < 300 && travelX < 8 && travelY < 8

                if (wasTap && !isAnchored) {
                    // Check for potential content changes
                    postDelayed({ updateScrollBarsVisibility() }, 500)
                }

                // Handle non-anchored tap for keyboard
                if (kbVisible && !isAnchored && wasTap) {
                    // Focus-driven tap: send the highlighted key
                    customKeyboard?.performFocusedTap()
                    return true
                }

                // Handle non-anchored tap for bookmarks
                if (::leftBookmarksView.isInitialized &&
                                leftBookmarksView.visibility == View.VISIBLE &&
                                !isAnchored &&
                                wasTap
                ) {
                    leftBookmarksView.performFocusedTap()
                    return true
                }
            }
        }

        // Let the keyboard keep handling movement (your current behavior)
        if (kbVisible && !isAnchored) {
            return customKeyboard?.dispatchTouchEvent(event) == true
        }

        // Let the bookmarks view handle movement in non-anchored mode
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            leftBookmarksView.handleDrag(event.x, event.action)
            return true
        }

        return super.onTouchEvent(event)
    }

    fun getKeyboardLocation(location: IntArray) {
        keyboardContainer.getLocationOnScreen(location)
    }

    fun getLogicalKeyboardLocation(location: IntArray) {
        location[0] = keyboardContainer.left
        location[1] = keyboardContainer.top
    }

    fun isPointInBookmarks(screenX: Float, screenY: Float): Boolean {
        if (!::leftBookmarksView.isInitialized || leftBookmarksView.visibility != View.VISIBLE)
                return false

        val bookmarksLocation = IntArray(2)
        leftBookmarksView.getLocationOnScreen(bookmarksLocation)

        return screenX >= bookmarksLocation[0] &&
                screenX <= bookmarksLocation[0] + leftBookmarksView.width &&
                screenY >= bookmarksLocation[1] &&
                screenY <= bookmarksLocation[1] + leftBookmarksView.height
    }

    fun isChatVisible(): Boolean {
        return ::chatView.isInitialized && chatView.visibility == View.VISIBLE
    }

    fun sendTextToChatInput(text: String) {
        if (!isChatVisible()) return
        chatView.sendTextToFocusedInput(text)
    }

    fun sendBackspaceToChatInput() {
        if (!isChatVisible()) return
        chatView.sendBackspaceToFocusedInput()
    }

    fun sendEnterToChatInput() {
        if (!isChatVisible()) return
        chatView.sendEnterToFocusedInput()
    }

    fun isPointInChat(screenX: Float, screenY: Float): Boolean {
        if (!isChatVisible()) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= chatView.left &&
                localX <= chatView.right &&
                localY >= chatView.top &&
                localY <= chatView.bottom
    }

    fun dispatchChatTouchEvent(screenX: Float, screenY: Float) {
        if (!isChatVisible()) return

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        val finalX = localX - chatView.left
        val finalY = localY - chatView.top
        chatView.handleAnchoredTap(finalX, finalY)
    }

    fun isPointInKeyboard(screenX: Float, screenY: Float): Boolean {
        if (keyboardContainer.visibility != View.VISIBLE) return false
        val kbView = customKeyboard ?: return false
        if (kbView.visibility != View.VISIBLE) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= keyboardContainer.left &&
                localX <= keyboardContainer.right &&
                localY >= keyboardContainer.top &&
                localY <= keyboardContainer.bottom
    }

    fun getKeyboardSize(): Pair<Int, Int> {
        return Pair(keyboardContainer.width, keyboardContainer.height)
    }

    // Called from MainActivity when the cursor is over the keyboard
    // Called from MainActivity to dispatch a tap to the custom keyboard
    fun dispatchKeyboardTap(screenX: Float, screenY: Float) {
        val kbView = customKeyboard ?: return
        if (kbView.visibility != View.VISIBLE) return

        val groupLocation = IntArray(2)
        getLocationOnScreen(groupLocation)

        // Translate screen coordinates to be relative to the UI container's screen origin
        // Note: keyboardContainer is a child of leftEyeUIContainer
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()

            // Interaction is already scaled in MainActivity for non-anchored,
            // but in anchored mode screen coordinates are absolute.
            // However, the UI inside the container is logical.
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        // Subtract keyboard's logical position within the container
        val finalX = localX - keyboardContainer.left
        val finalY = localY - keyboardContainer.top

        // Log.d("KeyboardDebug", "Keyboard tap: screen($screenX, $screenY) -> local($finalX,
        // $finalY)")
        kbView.handleAnchoredTap(finalX, finalY)
    }

    fun isDesktopMode(): Boolean {
        return isDesktopMode
    }

    fun setMobileUserAgent(ua: String) {
        mobileUserAgent = ua
    }

    fun getDesktopUserAgent(): String {
        return desktopUserAgent
    }

    fun updateBrowsingMode(isDesktop: Boolean) {
        // Log.d("ModeToggle", "Updating browsing mode to: ${if (isDesktop) "desktop" else
        // "mobile"}")

        isDesktopMode = isDesktop

        // Step 1: Update WebView settings (user agent)
        // If on Netflix, we preserve the current UA (which should be the system default) to prevent
        // DRM errors.
        val isNetflix = webView.url?.contains("netflix.com") == true

        if (!isNetflix) {
            webView.settings.apply {
                userAgentString =
                        if (isDesktop) {
                            desktopUserAgent
                        } else {
                            mobileUserAgent
                        }
                loadWithOverviewMode = true
                useWideViewPort = true
            }
        }

        // Step 2: Update viewport using JavaScript without forcing a complete reload
        val viewportContent =
                if (isDesktop) "width=1280, initial-scale=0.8"
                else "width=600, initial-scale=1.0, maximum-scale=1.0"

        webView.post {
            webView.evaluateJavascript(
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
            """,
                    null
            )

            // Step 3: Soft reload the page by re-navigating to the current URL
            val currentUrl = webView.url
            if (currentUrl != null && currentUrl != "about:blank") {
                // Use loadUrl to "soft reload" and keep browsing history
                webView.loadUrl("javascript:window.location.href = window.location.href")
            }
        }

        // Update toggle button icons
        webView.post {
            val leftButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
            leftButton?.text =
                    context.getString(
                            if (isDesktop) R.string.fa_desktop else R.string.fa_mobile_screen
                    )
        }
    }

    private fun loadARDashboard() {
        webView.loadUrl(Constants.DEFAULT_URL)
    }

    // Method to disable text handles
    @SuppressLint("DiscouragedPrivateApi")
    private fun disableTextHandles(editText: EditText) {
        // Don’t allow long-press to start selection
        editText.isLongClickable = false
        editText.setOnLongClickListener { true }

        // Don’t allow selection mode (copy/paste toolbar)
        editText.setTextIsSelectable(false)

        // Block the selection action mode
        editText.customSelectionActionModeCallback =
                object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onPrepareActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onActionItemClicked(
                            mode: android.view.ActionMode,
                            item: android.view.MenuItem
                    ) = false
                    override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                }

        // Block the insertion/caret handle action mode (API 23+)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            editText.customInsertionActionModeCallback =
                    object : android.view.ActionMode.Callback {
                        override fun onCreateActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onPrepareActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onActionItemClicked(
                                mode: android.view.ActionMode,
                                item: android.view.MenuItem
                        ) = false
                        override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                    }
        }

        // Optional: consume double-tap/long-press gestures that can trigger selection on some OEM
        // skins
        editText.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.eventTime - ev.downTime > 0) {
                // Let simple taps through; block long-press-ish starts if needed
                false
            } else {
                false
            }
        }
    }

    // In DualWebViewGroup.kt
    fun showEditField(initialText: String) {
        urlEditText.apply {
            text.clear()
            append(initialText)
            visibility = View.VISIBLE
            requestFocus()
            setSelection(text.length)
            bringToFront()
            // Add logging to verify state
        }
        // Make sure we're in edit mode
        isBookmarkEditing = true
        keyboardListener?.onShowKeyboard()

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun showButtonClickFeedback(button: View) {
        button.isPressed = true
        // Log.d("buttonFeedbackDebug", "button feedback shown")
        Handler(Looper.getMainLooper())
                .postDelayed({ button.isPressed = false }, buttonFeedbackDuration)
    }

    private fun handleLeftMenuAction(buttonId: Int) {
        if (buttonId != R.id.btnAnchor) {
            keyboardListener?.onHideKeyboard()
        }

        val button = leftToggleBar.findViewById<View>(buttonId)

        when (buttonId) {
            R.id.btnModeToggle -> {
                button?.let { showButtonClickFeedback(it) }
                isDesktopMode = !isDesktopMode

                // Save preference
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("isDesktopMode", isDesktopMode)
                        .apply()

                updateBrowsingMode(isDesktopMode)
            }
            R.id.btnYouTube -> {
                button?.let { showButtonClickFeedback(it) }
                loadARDashboard()
            }
            R.id.btnBookmarks -> {
                button?.let { showButtonClickFeedback(it) }
                toggleBookmarks()
            }
            R.id.btnZoomOut -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("out")
            }
            R.id.btnZoomIn -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("in")
            }
            R.id.btnMask -> {
                button?.let { showButtonClickFeedback(it) }
                maskToggleListener?.onMaskTogglePressed()
            }
            R.id.btnAnchor -> {
                button?.let { showButtonClickFeedback(it) }
                anchorToggleListener?.onAnchorTogglePressed()
            }
        }
    }

    fun hideBookmarkEditing() {
        isBookmarkEditing = false
        urlEditText.apply {
            visibility = View.GONE
            text.clear()
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun isBookmarkEditing(): Boolean {
        return isBookmarkEditing
    }

    // Add this method to handle cursor hovering
    private fun updateButtonHoverStates(screenX: Float, screenY: Float) {
        // Clear all states initially
        clearAllHoverStates()

        if (::chatView.isInitialized && chatView.visibility == View.VISIBLE) {
            val uiLocation = IntArray(2)
            leftEyeUIContainer.getLocationOnScreen(uiLocation)

            val translatedX = screenX - uiLocation[0]
            val translatedY = screenY - uiLocation[1]

            val localX: Float
            val localY: Float

            if (isAnchored) {
                val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()
                localX = (translatedX * cos + translatedY * sin) / uiScale
                localY = (-translatedX * sin + translatedY * cos) / uiScale
            } else {
                localX = translatedX / uiScale
                localY = translatedY / uiScale
            }

            val chatLocalX = localX - chatView.left
            val chatLocalY = localY - chatView.top

            if (chatView.updateHoverLocal(chatLocalX, chatLocalY)) {
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check bottom navigation bar buttons ONLY if nav bar is visible
        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.forEach { (name, navButton) ->
                if (isOver(navButton.left, screenX, screenY)) {
                    navButton.isHovered = true
                    navButton.left.isHovered = true
                    navButton.right.isHovered = true
                    customKeyboard?.clearHover() // Clear keyboard hover
                    return // Found the hovered button, stop checking
                }
            }
        }

        // Check left toggle bar buttons
        val toggleBarButtons =
                listOf(
                        Triple(R.id.btnModeToggle, "ModeToggle") { isHoveringModeToggle = true },
                        Triple(R.id.btnYouTube, "Dashboard") { isHoveringDashboardToggle = true },
                        Triple(R.id.btnBookmarks, "Bookmarks") { isHoveringBookmarksMenu = true },
                        Triple(R.id.btnZoomOut, "ZoomOut") { isHoveringZoomOut = true },
                        Triple(R.id.btnZoomIn, "ZoomIn") { isHoveringZoomIn = true },
                        Triple(R.id.btnMask, "Mask") { isHoveringMaskToggle = true },
                        Triple(R.id.btnAnchor, "Anchor") { isHoveringAnchorToggle = true }
                )

        for ((buttonId, name, setHoverFlag) in toggleBarButtons) {
            val button = leftToggleBar.findViewById<View>(buttonId)
            if (isOver(button, screenX, screenY)) {
                button?.isHovered = true
                setHoverFlag()
                clearNavigationButtonStates()
                // Log.d("HoverDebug", "Hovering over toggle button: $name")
                customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                return // Found the hovered button, stop checking
            }
        }

        // Check Windows button separately (programmatically created, no resource ID)
        windowsButton?.let { btn ->
            if (isOver(btn, screenX, screenY)) {
                btn.isHovered = true
                isHoveringWindowsToggle = true
                clearNavigationButtonStates()
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check settings window elements if visible
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    val view = menu.findViewById<View>(id)
                    if (isOver(view, screenX, screenY)) {
                        view?.isHovered = true
                        // Log.d("HoverDebug", "Hovering over settings element: $id")
                        customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                        return // Found the hovered element, stop checking
                    }
                }
            }
        }

        // Check active dialog buttons if visible
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                // Dialog structure: Title(0), Message(1), optional Input(2), ButtonContainer(last)
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        val button = container.getChildAt(i)
                        if (isOver(button, screenX, screenY)) {
                            button.isHovered = true
                            // Log.d("HoverDebug", "Hovering over dialog button: $i")
                            customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                            return
                        }
                    }
                }
            }
        }

        // Check windows overview if visible
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            val woc = windowsOverviewContainer ?: return

            // Use anchored coordinates if needed
            if (isAnchored) {
                val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

                // Perform hit testing relative to leftEyeUIContainer
                // woc is a child of leftEyeUIContainer
                val wocLeft = woc.left + woc.translationX
                val wocTop = woc.top + woc.translationY

                if (localX >= wocLeft &&
                                localX <= wocLeft + woc.width &&
                                localY >= wocTop &&
                                localY <= wocTop + woc.height
                ) {

                    val container = woc.getChildAt(0) as? LinearLayout ?: return
                    // Container is inside ScrollView woc
                    // local in woc
                    val xInWoc = localX - wocLeft + woc.scrollX
                    val yInWoc = localY - wocTop + woc.scrollY

                    val containerLeft = container.left + container.translationX
                    val containerTop = container.top + container.translationY

                    val xInContainer = xInWoc - containerLeft
                    val yInContainer = yInWoc - containerTop

                    // Helper for checking children
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (xInContainer >= child.left &&
                                        xInContainer <= child.right &&
                                        yInContainer >= child.top &&
                                        yInContainer <= child.bottom
                        ) {

                            if (i == 0) { // Add Button
                                child.isHovered = true
                                hoveredWindowsOverviewItem = child
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }

                            // Rows
                            if (child is ViewGroup) {
                                val xInChild = xInContainer - child.left
                                val yInChild = yInContainer - child.top

                                for (j in 0 until child.childCount) {
                                    val item = child.getChildAt(j)
                                    if (xInChild >= item.left &&
                                                    xInChild <= item.right &&
                                                    yInChild >= item.top &&
                                                    yInChild <= item.bottom
                                    ) {

                                        // Check for delete button first (FontIconView child)
                                        if (item is ViewGroup) {
                                            val xInItem = xInChild - item.left
                                            val yInItem = yInChild - item.top
                                            for (k in 0 until item.childCount) {
                                                val itemChild = item.getChildAt(k)
                                                if (itemChild is FontIconView &&
                                                                xInItem >= itemChild.left &&
                                                                xInItem <= itemChild.right &&
                                                                yInItem >= itemChild.top &&
                                                                yInItem <= itemChild.bottom
                                                ) {
                                                    itemChild.isHovered = true
                                                    hoveredWindowsOverviewItem = itemChild
                                                    customKeyboard?.updateHover(-1f, -1f)
                                                    return
                                                }
                                            }
                                        }

                                        // Set hover on the whole item
                                        item.isHovered = true
                                        hoveredWindowsOverviewItem = item
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-anchored mode: Use the isOver function with getGlobalVisibleRect
                val container = woc.getChildAt(0) as? LinearLayout ?: return

                // Check all children (add button + rows of window items)
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)

                    // First child (i == 0) is the Add Button
                    if (i == 0 && isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        hoveredWindowsOverviewItem = child
                        customKeyboard?.updateHover(-1f, -1f)
                        return
                    }

                    // Other children are rows containing window items
                    if (child is ViewGroup) {
                        for (j in 0 until child.childCount) {
                            val windowItem = child.getChildAt(j)

                            // Check for delete button first (it's a FontIconView child of the
                            // window item)
                            if (windowItem is ViewGroup) {
                                for (k in 0 until windowItem.childCount) {
                                    val itemChild = windowItem.getChildAt(k)
                                    // Delete button is a FontIconView with the X icon
                                    if (itemChild is FontIconView &&
                                                    isOver(itemChild, screenX, screenY)
                                    ) {
                                        itemChild.isHovered = true
                                        hoveredWindowsOverviewItem = itemChild
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }

                            // Then check the whole window item
                            if (isOver(windowItem, screenX, screenY)) {
                                windowItem.isHovered = true
                                hoveredWindowsOverviewItem = windowItem
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }
                        }
                    }
                }
            }
        }

        // Check bookmarks view if visible
        if (isBookmarksExpanded()) {
            val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

            val finalX = localX - leftBookmarksView.left
            val finalY = localY - leftBookmarksView.top

            if (leftBookmarksView.updateHover(finalX, finalY)) {
                customKeyboard?.updateHoverScreen(-1f, -1f, 1f) // Clear keyboard hover
                return
            }
        }

        // Check scrollbars if visible (UI scale < 0.99f or forced visible)
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            horizontalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + horizontalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + horizontalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until horizontalScrollBar.childCount) {
                    val child = horizontalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == horizontalScrollBar.getChildAt(1)) {
                            if (isOver(hScrollThumb, screenX, screenY)) {
                                hScrollThumb.isHovered = true
                                hScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + verticalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + verticalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until verticalScrollBar.childCount) {
                    val child = verticalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == verticalScrollBar.getChildAt(1)) {
                            if (isOver(vScrollThumb, screenX, screenY)) {
                                vScrollThumb.isHovered = true
                                vScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check keyboard elements if visible
        if (keyboardContainer.visibility == View.VISIBLE) {
            val kbView = customKeyboard
            if (kbView != null && kbView.visibility == View.VISIBLE) {
                val uiLocation = IntArray(2)
                leftEyeUIContainer.getLocationOnScreen(uiLocation)

                // Use screen coordinates for keyboard hit testing to avoid drift
                // Pass raw screenX/screenY and let CustomKeyboardView check against actual screen
                // positions
                kbView.updateHoverScreen(screenX, screenY, uiScale)

                // We don't return here because updateHoverScreen will internally check if a key was
                // hit.
                // However, we should check if a key WAS hit to know if we should "consume" the
                // hover event.
                // For now, if the keyboard is visible, we let it process.
                return // Stop checking after keyboard processing
            }
        }
    }

    // Helper function to clear all hover states
    private fun clearAllHoverStates() {
        // Clear toggle button states
        isHoveringModeToggle = false
        isHoveringDashboardToggle = false
        isHoveringBookmarksMenu = false
        isHoveringZoomIn = false
        isHoveringZoomOut = false

        isHoveringMaskToggle = false
        isHoveringAnchorToggle = false
        isHoveringWindowsToggle = false

        // Clear windows overview hover
        hoveredWindowsOverviewItem?.isHovered = false
        hoveredWindowsOverviewItem = null

        // Clear visual hover states
        listOf(
                        R.id.btnModeToggle,
                        R.id.btnYouTube,
                        R.id.btnBookmarks,
                        R.id.btnZoomIn,
                        R.id.btnZoomOut,
                        R.id.btnMask,
                        R.id.btnAnchor
                )
                .forEach { id -> leftToggleBar.findViewById<View>(id)?.isHovered = false }

        // Clear Windows button hover state (programmatically created)
        windowsButton?.isHovered = false

        // Clear settings hover states
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    menu.findViewById<View>(id)?.isHovered = false
                }
            }
        }

        // Clear dialog button states
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        container.getChildAt(i).isHovered = false
                    }
                }
            }
        }

        // Clear navigation button states
        clearNavigationButtonStates()

        if (::chatView.isInitialized) {
            chatView.clearHover()
        }

        // Clear keyboard hover
        customKeyboard?.updateHoverScreen(-1f, -1f, 1f)

        // Clear scroll bar hover states
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until horizontalScrollBar.childCount) {
                horizontalScrollBar.getChildAt(i).isHovered = false
                horizontalScrollBar.getChildAt(i).isActivated = false
            }
            hScrollThumb.isHovered = false
            hScrollThumb.isActivated = false
        }
        if (verticalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until verticalScrollBar.childCount) {
                verticalScrollBar.getChildAt(i).isHovered = false
                verticalScrollBar.getChildAt(i).isActivated = false
            }
            vScrollThumb.isHovered = false
            vScrollThumb.isActivated = false
        }
    }

    // Helper method to check if a point is within any visible scrollbar
    fun isPointInScrollbar(screenX: Float, screenY: Float): Boolean {
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            horizontalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + horizontalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + horizontalScrollBar.height
            ) {
                return true
            }
        }
        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + verticalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + verticalScrollBar.height
            ) {
                return true
            }
        }
        return false
    }

    // Dispatch touch/click to the appropriate scrollbar element
    fun dispatchScrollbarTouch(screenX: Float, screenY: Float) {
        fun dispatchToContainer(container: ViewGroup) {
            val location = IntArray(2)
            container.getLocationOnScreen(location)
            // Check which child is hit
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val childLocation = IntArray(2)
                child.getLocationOnScreen(childLocation)

                if (screenX >= childLocation[0] &&
                                screenX <= childLocation[0] + child.width &&
                                screenY >= childLocation[1] &&
                                screenY <= childLocation[1] + child.height
                ) {

                    if (child.hasOnClickListeners()) {
                        child.performClick()
                    } else {
                        // For track/thumb, we need to simulate touch events
                        // The track listener reacts to ACTION_UP
                        val localX = screenX - childLocation[0]
                        val localY = screenY - childLocation[1]

                        val downEvent =
                                MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_DOWN,
                                        localX,
                                        localY,
                                        0
                                )
                        child.dispatchTouchEvent(downEvent)
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
                        child.dispatchTouchEvent(upEvent)
                        upEvent.recycle()
                    }
                    return
                }
            }
        }

        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            horizontalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + horizontalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + horizontalScrollBar.height
            ) {
                dispatchToContainer(horizontalScrollBar)
                return
            }
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + verticalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + verticalScrollBar.height
            ) {
                dispatchToContainer(verticalScrollBar)
                return
            }
        }
    }

    fun isNavBarVisible(): Boolean {
        // Check both visibility AND scroll mode - in scroll mode, bars are hidden even during fade
        // animation
        return !isInScrollMode && leftNavigationBar.visibility == View.VISIBLE
    }

    fun isToggleBarVisible(): Boolean {
        return leftToggleBar.visibility == View.VISIBLE
    }

    fun isPointInToggleBar(screenX: Float, screenY: Float): Boolean {
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        return isPointInView(localX, localY, leftToggleBar)
    }

    fun isPointInNavBar(screenX: Float, screenY: Float): Boolean {
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        return isPointInView(localX, localY, leftNavigationBar)
    }

    private fun isPointInView(containerX: Float, containerY: Float, view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        return containerX >= view.left &&
                containerX <= view.right &&
                containerY >= view.top &&
                containerY <= view.bottom
    }

    private fun isPointInChild(
            containerX: Float,
            containerY: Float,
            parent: View,
            child: View?
    ): Boolean {
        if (child == null || parent.visibility != View.VISIBLE || child.visibility != View.VISIBLE)
                return false
        val localX = containerX - parent.left
        val localY = containerY - parent.top
        return localX >= child.left &&
                localX <= child.right &&
                localY >= child.top &&
                localY <= child.bottom
    }

    fun isPointInRestoreButton(x: Float, y: Float): Boolean {
        if (btnShowNavBars.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        btnShowNavBars.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (btnShowNavBars.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (btnShowNavBars.height * uiScale)
    }

    fun performRestoreButtonClick() {
        if (btnShowNavBars.visibility == View.VISIBLE) {
            btnShowNavBars.performClick()
        }
    }

    fun isWindowsOverviewVisible(): Boolean {
        return windowsOverviewContainer?.visibility == View.VISIBLE
    }

    fun isPointInWindowsOverview(x: Float, y: Float): Boolean {
        val woc = windowsOverviewContainer ?: return false
        if (woc.visibility != View.VISIBLE) return false

        val loc = IntArray(2)
        woc.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + woc.width && y >= loc[1] && y <= loc[1] + woc.height
    }

    fun performWindowsOverviewClick() {
        val item = hoveredWindowsOverviewItem ?: return
        if (item.isHovered) {
            showButtonClickFeedback(item)
            item.performClick()
        }
    }

    private fun isOver(button: View?, screenX: Float, screenY: Float): Boolean {
        if (button == null || button.visibility != View.VISIBLE) return false

        // Use getGlobalVisibleRect for accurate screen bounds detection
        val rect = android.graphics.Rect()
        if (!button.getGlobalVisibleRect(rect)) return false

        return screenX >= rect.left &&
                screenX <= rect.right &&
                screenY >= rect.top &&
                screenY <= rect.bottom
    }

    fun handleNavigationClick(screenX: Float, screenY: Float) {
        if (isInScrollMode) return

        if (isSettingsVisible && settingsMenu != null) {
            val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
            if (isPointInView(localX, localY, settingsMenu)) {
                dispatchSettingsTouchEvent(screenX, screenY)
                return
            }
        }

        if (leftToggleBar.visibility == View.VISIBLE) {
            val toggleBarButtons =
                    listOf(
                            R.id.btnModeToggle,
                            R.id.btnYouTube,
                            R.id.btnBookmarks,
                            R.id.btnZoomOut,
                            R.id.btnZoomIn,
                            R.id.btnMask,
                            R.id.btnAnchor
                    )

            for (buttonId in toggleBarButtons) {
                val button = leftToggleBar.findViewById<View>(buttonId)
                if (isOver(button, screenX, screenY)) {
                    handleLeftMenuAction(buttonId)
                    return
                }
            }

            windowsButton?.let { btn ->
                if (isOver(btn, screenX, screenY)) {
                    showButtonClickFeedback(btn)
                    toggleWindowMode()
                    return
                }
            }
        }

        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.entries.firstOrNull { isOver(it.value.left, screenX, screenY) }?.let {
                    (key, button) ->
                keyboardListener?.onHideKeyboard()
                showButtonClickFeedback(button.left)
                showButtonClickFeedback(button.right)
                if (key == "hide") {
                    setScrollMode(true)
                } else if (key == "chat") {
                    toggleChat()
                } else {
                    navigationListener?.let { listener ->
                        when (key) {
                            "back" -> listener.onNavigationBackPressed()
                            "forward" -> listener.onNavigationForwardPressed()
                            "home" -> listener.onHomePressed()
                            "link" -> listener.onHyperlinkPressed()
                            "settings" -> listener.onSettingsPressed()
                            "refresh" -> listener.onRefreshPressed()
                            "quit" -> listener.onQuitPressed()
                        }
                    }
                }
            }
        }
    }

    fun resetPositions() {
        // Reset translations
        _translationX = 0f
        _translationY = 0f
        _rotationZ = 0f

        // Reset translations on views
        leftEyeUIContainer.translationX = 0f
        leftEyeUIContainer.translationY = 0f
        leftEyeUIContainer.rotation = 0f

        // Reset translations on views
        leftEyeClipParent.translationX = 0f
        leftEyeClipParent.translationY = 0f
        leftEyeClipParent.rotation = 0f

        // Also reset fullscreen overlay
        fullScreenOverlayContainer.translationX = 0f
        fullScreenOverlayContainer.translationY = 0f
        fullScreenOverlayContainer.rotation = 0f

        postDelayed(
                {
                    startRefreshing()
                    requestLayout()
                    invalidate()
                },
                100
        )
    }

    private fun handleZoomButtonClick(direction: String) {
        val zoomFactor = if (direction == "in") 1.1f else 0.9f
        currentWebZoom *= zoomFactor

        // Save preference
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putFloat("webZoomLevel", currentWebZoom)
                .apply()

        webView.evaluateJavascript(
                """
        (function() {
            document.body.style.zoom = "$currentWebZoom";
        })();
    """,
                null
        )

        postDelayed(
                {
                    updateScrollBarsVisibility()
                    lastScrollBarCheckTime = System.currentTimeMillis()
                },
                100
        )
    }

    fun refreshBothBookmarks() {
        // Refresh left bookmarks view
        leftBookmarksView.refreshBookmarks()
        leftBookmarksView.visibility = View.VISIBLE
        leftBookmarksView.bringToFront()
        leftBookmarksView.measure(
                MeasureSpec.makeMeasureSpec(420, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        leftBookmarksView.layout(
                leftBookmarksView.left,
                leftBookmarksView.top,
                leftBookmarksView.left + 480,
                leftBookmarksView.top + leftBookmarksView.measuredHeight
        )

        // Force a layout update
        leftBookmarksView.post {
            leftBookmarksView.requestLayout()
            leftBookmarksView.invalidate()
            // Ensure the mirroring is updated
            startRefreshing()
        }
    }

    // In DualWebViewGroup.kt, add these methods:
    fun startAnchoring() {
        isAnchored = true
        webView.visibility = View.VISIBLE
        rightEyeView.visibility = View.VISIBLE
        startRefreshing()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(true)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(true)
        }

        // Use unbarred anchor icon when anchored
        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text =
                context.getString(R.string.fa_anchor)
    }

    fun stopAnchoring() {
        isAnchored = false
        resetPositions()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(false)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(false)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text =
                context.getString(R.string.fa_anchor_circle_xmark)
        webView.visibility = View.VISIBLE
        rightEyeView.visibility = View.VISIBLE

        updateUiTranslation()

        post {
            startRefreshing()
            invalidate()
        }
    }

    fun setBookmarksView(bookmarksView: BookmarksView) {
        this.leftBookmarksView =
                bookmarksView.apply {
                    val params =
                            MarginLayoutParams(420, LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = toggleBarWidthPx // After toggle bar
                                topMargin = 10 // Move higher up
                            }
                    layoutParams = params
                    elevation = 1000f
                    visibility = View.GONE
                }

        // Remove existing view if present
        (leftBookmarksView.parent as? ViewGroup)?.removeView(leftBookmarksView)

        // Add view to hierarchy
        leftEyeUIContainer.addView(leftBookmarksView)
        leftBookmarksView.bringToFront()

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleAnchoredFling(velocity: Float) {
        if (isBookmarksExpanded()) {
            // No-op for bookmarks in anchored mode (pagination used)
        } else {
            // Forward to general handleFling which handles WebView scroll
            handleFling(velocity)
        }
    }

    fun handleFling(velocityX: Float) {
        // Log.d("Fling Debug", "Fling handled by DualWebViewGroup")

        // First check if bookmarks are visible (Non-Anchored Mode legacy behavior)
        if (leftBookmarksView.visibility == View.VISIBLE && !isAnchored) {
            // Log.d("DualWebViewGroup", "Delegating fling to bookmarks: velocity=$velocityX")

            // Determine direction based on velocity and delegate to both views
            val isForward = velocityX > 0

            // Update both left and right bookmark views to maintain synchronization
            leftBookmarksView.handleFling(isForward)

            // Force layout update to ensure visual sync between views
            post {
                requestLayout()
                invalidate()
            }
            return
        }

        // If bookmarks aren't visible, handle normal scrolling behavior
        // Slow down the velocity for smoother scrolling
        val slowedVelocity = velocityX * 0.15f

        // Handle vertical scrolling
        webView.evaluateJavascript(
                """
            (function() {
                window.scrollBy({
                    top: ${(-slowedVelocity).toInt()},
                    behavior: 'smooth'
                });
            })();
        """,
                null
        )

        // Provide a native scroll backup only if JS execution fails or is slow?
        // Actually, since we want to avoid double-scroll bouncing, relying on JS scrollBy is safer
        // with 'smooth' behavior.
        // However, if we remove this, we rely solely on JS.
        // Let's remove the unconditional native backup to prevent fighting/overshoot.
    }

    private fun initializeToggleButtons() {
        Log.d(
                "ViewDebug",
                """
    Toggle bar parent: ${leftToggleBar.parent?.javaClass?.simpleName}
    Toggle bar children: ${(leftToggleBar as? ViewGroup)?.childCount ?: "Not a ViewGroup"}
    UI Container children count: ${leftEyeUIContainer.childCount}
    UI Container children:
    ${(0 until leftEyeUIContainer.childCount).joinToString("n") { index ->
            val child = leftEyeUIContainer.getChildAt(index)
            "Child $index: ${child.javaClass.simpleName} (${child.hashCode()})"+
                    "n    Location: (${child.x}, ${child.y})"+
                    "n    Size: ${child.width}x${child.height}"+
                    "n    Translation: (${child.translationX}, ${child.translationY})"
        }}
""".trimIndent()
        )

        // Get references to all buttons
        val leftModeToggleButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
        val leftDashboardButton = leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube)
        val leftBookmarksButton = leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks)
        val leftZoomInButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomIn)
        val leftZoomOutButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomOut)
        val leftMaskButton = leftToggleBar.findViewById<FontIconView>(R.id.btnMask)
        val leftAnchorButton = leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)

        // Create Windows button programmatically since it's not in XML
        val leftWindowsButton =
                FontIconView(context).apply {
                    id = View.generateViewId() // Generate an ID for the view
                    tag = "btnWindows" // Tag for identification
                    configureToggleButton(R.string.fa_window_restore)
                }
        windowsButton = leftWindowsButton // Store reference for hover/click handling

        // Insert it into toggle bar - we need to add it to the layout
        if (leftToggleBar is ViewGroup) {
            // Find where to insert - maybe after dashboard button?
            // Actually, toggle_bar.xml is a LinearLayout (based on usage).
            // We can just addView. But we need to insert it in the correct order visually.
            // XML has: Mode, YouTube, Bookmarks, ZoomOut, ZoomIn, Mask, Anchor.
            // Let's put Windows button after Bookmarks.
            val index = (leftToggleBar as ViewGroup).indexOfChild(leftBookmarksButton) + 1
            if (index > 0) {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton, index)
            } else {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton)
            }
        }

        // Calculate positioning constants
        val iconPadding = 4.dp()
        val orderedButtons =
                listOf(
                        leftModeToggleButton,
                        leftDashboardButton,
                        leftBookmarksButton,
                        leftWindowsButton,
                        leftZoomOutButton,
                        leftZoomInButton,
                        leftMaskButton,
                        leftAnchorButton
                )

        orderedButtons.forEach { button ->
            try {
                button?.apply {
                    layoutParams =
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    visibility = View.VISIBLE
                    background =
                            ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                    // Icon already set via XML text attribute
                    if (id == R.id.btnAnchor) {
                        text =
                                context.getString(
                                        if (isAnchored) R.string.fa_anchor
                                        else R.string.fa_anchor_circle_xmark
                                )
                    }
                    setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                    elevation = 4f
                    alpha = 1f
                    isEnabled = true
                    setOnTouchListener { v, event ->
                        val location = IntArray(2)
                        v.getLocationOnScreen(location)
                        val parentLocation = IntArray(2)
                        leftToggleBar.getLocationOnScreen(parentLocation)


                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("ToggleButton", "Error configuring button", e)
            }
        }

        mapOf(
                        leftModeToggleButton to R.id.btnModeToggle,
                        leftDashboardButton to R.id.btnYouTube,
                        leftBookmarksButton to R.id.btnBookmarks,
                        leftZoomOutButton to R.id.btnZoomOut,
                        leftZoomInButton to R.id.btnZoomIn,
                        leftMaskButton to R.id.btnMask,
                        leftAnchorButton to R.id.btnAnchor
                )
                .forEach { (button, id) -> button?.setOnClickListener { handleLeftMenuAction(id) } }

        leftWindowsButton.setOnClickListener {
            showButtonClickFeedback(leftWindowsButton)
            toggleWindowMode()
        }
    }

    fun isSettingsVisible(): Boolean {
        return isSettingsVisible
    }

    fun hideSettings() {
        if (isSettingsVisible) {
            isSettingsVisible = false
            settingsMenu?.visibility = View.GONE
            settingsScrim?.visibility = View.GONE
        }
    }

    // Reset all overlay UI state - call on app startup
    fun resetUiState() {
        isSettingsVisible = false
        settingsMenu?.visibility = View.GONE
        settingsScrim?.visibility = View.GONE
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.visibility = View.GONE
        }
    }

    fun showSettings() {
        // Log.d("SettingsDebug", "showSettings() called, isSettingsVisible: $isSettingsVisible")

        if (settingsMenu == null) {
            settingsMenu =
                    LayoutInflater.from(context)
                            .inflate(R.layout.settings_layout, null, false)
                            .apply {
                                isClickable = false
                                isFocusable = false
                                elevation = 1001f // Even higher elevation than scrim
                            }

            // Add click handler for close button
            settingsMenu?.findViewById<View>(R.id.btnCloseSettings)?.setOnClickListener {
                // Log.d("SettingsDebug", "Close button clicked")
                isSettingsVisible = false
                settingsMenu?.visibility = View.GONE
                settingsScrim?.visibility = View.GONE
                startRefreshing()
            }

            // Add click handler for help button
            settingsMenu?.findViewById<ImageButton>(R.id.btnHelp)?.setOnClickListener {
                // Log.d("SettingsDebug", "Help button clicked")
                showHelpDialog()
            }

            val layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply { gravity = Gravity.CENTER }

            leftEyeUIContainer.addView(settingsMenu, layoutParams)
            settingsMenu?.elevation = 1001f

            // Log.d("SettingsDebug", "Menu added with height: ${settingsMenu?.measuredHeight}")
        }

        // Only initialize seekbars when we are about to SHOW settings (not when closing)
        if (!isSettingsVisible) {
            settingsMenu?.let { menu ->
                // Initialize volume seekbar
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
                volumeSeekBar?.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeSeekBar?.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // Initialize brightness seekbar
                val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
                brightnessSeekBar?.max = 100
                val currentBrightness =
                        (context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f
                brightnessSeekBar?.progress = (currentBrightness * 100).toInt()

                // Initialize smoothness seekbar from saved preference
                val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
                val savedSmoothness =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("anchorSmoothness", 40)
                smoothnessSeekBar?.progress = savedSmoothness

                // Initialize screen size seekbar (just update the UI, don't apply scale)
                val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
                val savedScaleProgress =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("uiScaleProgress", 100)
                screenSizeSeekBar?.progress = savedScaleProgress

                // Calculate scale for position slider visibility check only
                val currentScale = 0.25f + (savedScaleProgress / 100f) * 0.75f

                // Initialize position sliders
                val showPosSliders = !isAnchored && currentScale < 0.99f
                val visibility = if (showPosSliders) View.VISIBLE else View.GONE

                menu.findViewById<View>(R.id.settingsPositionLayout)?.visibility = visibility

                menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransXProgress", 50)
                }

                menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransYProgress", 50)
                }

                // Initialize font size seekbar (50% = 50, 100% = 100, 200% = 200, slider is 0-150
                // mapping to 50-200%)
                val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
                val savedFontSize =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("webFontSize", 50) // Default 50 = 100%
                fontSizeSeekBar?.progress = savedFontSize

                // Initialize color buttons with visual background indicators
                // Initialize color wheel with saved color
                menu.findViewById<ColorWheelView>(R.id.colorWheelView)?.apply {
                    val savedTextColor =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getString("webTextColor", null)
                    try {
                        setColor(Color.parseColor(savedTextColor))
                    } catch (e: Exception) {
                        setColor(Color.WHITE)
                    }
                }

                // Apply saved font settings
                val savedTextColor =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getString("webTextColor", null)
                applyWebFontSettings(savedFontSize, savedTextColor)

                // Initialize cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                // Default 50 corresponds to 50%
                val savedSensitivity =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("cursorSensitivity", 50)
                sensitivitySeekBar?.progress = savedSensitivity
            }
        }

        // Toggle visibility state
        isSettingsVisible = !isSettingsVisible

        settingsMenu?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE
        settingsScrim?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE

        if (isSettingsVisible) {
            settingsScrim?.bringToFront()
            settingsMenu?.bringToFront()

            // Keep the force immediate layout code
            settingsMenu?.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            settingsMenu?.layout(
                    settingsMenu?.left ?: 0,
                    settingsMenu?.top ?: 0,
                    (settingsMenu?.left ?: 0) + (settingsMenu?.measuredWidth ?: 0),
                    (settingsMenu?.top ?: 0) + (settingsMenu?.measuredHeight ?: 0)
            )
        }

        startRefreshing()
        post {
            requestLayout()
            invalidate()
        }
    }

    fun getSettingsMenuLocation(location: IntArray) {
        settingsMenu?.getLocationOnScreen(location)
    }

    fun getSettingsMenuSize(): Pair<Int, Int> {
        return Pair(settingsMenu?.width ?: 0, settingsMenu?.height ?: 0)
    }

    fun dispatchSettingsTouchEvent(x: Float, y: Float) {
        settingsMenu?.let { menu ->
            // Get locations of all interactive elements
            val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
            val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
            val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
            val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
            val horizontalPosSeekBar = menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)
            val verticalPosSeekBar = menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)
            val closeButton = menu.findViewById<View>(R.id.btnCloseSettings)
            val helpButton = menu.findViewById<ImageButton>(R.id.btnHelp)
            val resetButton = menu.findViewById<Button>(R.id.btnResetPosition)
            val resetScreenSizeButton = menu.findViewById<Button>(R.id.btnResetScreenSize)
            val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
            val colorWheelView = menu.findViewById<ColorWheelView>(R.id.colorWheelView)
            val resetTextColorButton = menu.findViewById<Button>(R.id.btnResetTextColor)
            val groqKeyButton = menu.findViewById<Button>(R.id.btnGroqApiKey)

            fun getRect(view: View?): Rect? {
                if (view == null || view.visibility != View.VISIBLE) return null
                val rect = Rect()
                return if (view.getGlobalVisibleRect(rect)) rect else null
            }

            fun contains(rect: Rect?, slopPx: Int): Boolean {
                if (rect == null) return false
                return x >= rect.left - slopPx &&
                        x <= rect.right + slopPx &&
                        y >= rect.top - slopPx &&
                        y <= rect.bottom + slopPx
            }

            val menuSlop = (2f * uiScale).roundToInt()
            val sliderSlop = (1f * uiScale).roundToInt()
            val buttonSlop = (3f * uiScale).roundToInt()

            if (contains(getRect(menu), menuSlop)) {
                // Check if click is on volume seekbar
                val volumeRect = getRect(volumeSeekBar)
                if (volumeSeekBar != null && contains(volumeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - volumeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, volumeSeekBar.width.toFloat()) /
                                    volumeSeekBar.width
                    val newProgress = (percentage * volumeSeekBar.max).toInt()

                    // Update volume
                    volumeSeekBar.progress = newProgress
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
                        setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newProgress,
                                AudioManager.FLAG_SHOW_UI
                        )
                    }

                    // **Play system sound for feedback**
                    playSystemSound(context)

                    // Visual feedback
                    volumeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ volumeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on brightness seekbar
                val brightnessRect = getRect(brightnessSeekBar)
                if (brightnessSeekBar != null && contains(brightnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - brightnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, brightnessSeekBar.width.toFloat()) /
                                    brightnessSeekBar.width
                    val newProgress = (percentage * brightnessSeekBar.max).toInt()

                    // Update brightness
                    brightnessSeekBar.progress = newProgress
                    (context as? Activity)?.window?.attributes =
                            (context as Activity).window.attributes.apply {
                                screenBrightness = newProgress / 100f
                            }

                    // Visual feedback
                    brightnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ brightnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on smoothness seekbar
                val smoothnessRect = getRect(smoothnessSeekBar)
                if (smoothnessSeekBar != null && contains(smoothnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - smoothnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, smoothnessSeekBar.width.toFloat()) /
                                    smoothnessSeekBar.width
                    val newProgress = (percentage * smoothnessSeekBar.max).toInt()

                    // Update smoothness
                    smoothnessSeekBar.progress = newProgress

                    // Save preference and notify MainActivity
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("anchorSmoothness", newProgress)
                            .apply()

                    // Call MainActivity to update smoothness
                    (context as? MainActivity)?.updateAnchorSmoothness(newProgress)

                    // Visual feedback
                    smoothnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ smoothnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                val sensitivityRect = getRect(sensitivitySeekBar)

                if (sensitivitySeekBar != null && contains(sensitivityRect, sliderSlop)) {
                    // Calculate relative position on seekbar
                    val relativeX = (x - sensitivityRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, sensitivitySeekBar.width.toFloat()) /
                                    sensitivitySeekBar.width
                    val newProgress = (percentage * sensitivitySeekBar.max).toInt()

                    // Update sensitivity and save preference
                    sensitivitySeekBar.progress = newProgress
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", newProgress)
                            .apply()

                    // Notify MainActivity
                    (context as? MainActivity)?.updateCursorSensitivity(newProgress)

                    // Visual feedback
                    sensitivitySeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ sensitivitySeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset sensitivity button
                val resetSensitivityButton =
                        menu.findViewById<Button>(R.id.btnResetCursorSensitivity)
                val resetSensitivityRect = getRect(resetSensitivityButton)

                if (resetSensitivityButton != null && contains(resetSensitivityRect, buttonSlop)) {
                    // Reset to 50%
                    val sensitivitySeekBar =
                            menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                    sensitivitySeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", 50)
                            .apply()

                    (context as? MainActivity)?.updateCursorSensitivity(50)

                    // Visual feedback
                    resetSensitivityButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetSensitivityButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on screen size seekbar
                val screenSizeRect = getRect(screenSizeSeekBar)
                if (screenSizeSeekBar != null && contains(screenSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - screenSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, screenSizeSeekBar.width.toFloat()) /
                                    screenSizeSeekBar.width
                    var newProgress = (percentage * screenSizeSeekBar.max).toInt()

                    // Snap to 100% when close (>= 95%)
                    if (newProgress >= 95) {
                        newProgress = 100
                    }

                    // Update screen size
                    screenSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", newProgress)
                            .apply()

                    // Apply scale: 35% (0.35) to 100% (1.0)
                    val scale = 0.35f + (newProgress / 100f) * 0.65f
                    updateUiScale(scale)

                    // Update visibility of position sliders
                    val showPosSliders = !isAnchored && scale < 0.99f
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    val newVisibility = if (showPosSliders) View.VISIBLE else View.GONE

                    if (posLayout?.visibility != newVisibility) {
                        posLayout?.visibility = newVisibility

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Recalculate translation based on new scale
                    updateUiTranslation()

                    // Visual feedback
                    screenSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ screenSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on Groq API Key button
                val groqKeyRect = getRect(groqKeyButton)
                if (groqKeyButton != null && contains(groqKeyRect, buttonSlop)) {

                    // Visual feedback
                    groqKeyButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        groqKeyButton.isPressed = false
                                        // Show dialog
                                        (context as? MainActivity)?.showGroqKeyDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on reset screen size button
                val resetScreenSizeRect = getRect(resetScreenSizeButton)
                if (resetScreenSizeButton != null && contains(resetScreenSizeRect, buttonSlop)) {

                    // Reset screen size to 100%
                    screenSizeSeekBar?.progress = 100

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", 100)
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    // Apply full scale
                    updateUiScale(1.0f)

                    // Hide position sliders and remeasure
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    if (posLayout?.visibility != View.GONE) {
                        posLayout?.visibility = View.GONE

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Reset position to center
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50
                    updateUiTranslation()

                    // Visual feedback
                    resetScreenSizeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetScreenSizeButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on horizontal pos seekbar
                val horizontalPosRect = getRect(horizontalPosSeekBar)
                if (horizontalPosSeekBar != null &&
                                horizontalPosSeekBar.visibility == View.VISIBLE &&
                                contains(horizontalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - horizontalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, horizontalPosSeekBar.width.toFloat()) /
                                    horizontalPosSeekBar.width
                    val newProgress = (percentage * horizontalPosSeekBar.max).toInt()

                    horizontalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    horizontalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ horizontalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on vertical pos seekbar
                val verticalPosRect = getRect(verticalPosSeekBar)
                if (verticalPosSeekBar != null &&
                                verticalPosSeekBar.visibility == View.VISIBLE &&
                                contains(verticalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - verticalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, verticalPosSeekBar.width.toFloat()) /
                                    verticalPosSeekBar.width
                    val newProgress = (percentage * verticalPosSeekBar.max).toInt()

                    verticalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransYProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    verticalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ verticalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset button
                val resetRect = getRect(resetButton)
                if (resetButton != null &&
                                resetButton.visibility == View.VISIBLE &&
                                contains(resetRect, buttonSlop)
                ) {

                    // Reset position progress to 50 (center)
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    updateUiTranslation()

                    // Visual feedback
                    resetButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on help button
                val helpRect = getRect(helpButton)
                if (helpButton != null && contains(helpRect, buttonSlop)) {

                    // Visual feedback
                    helpButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        helpButton.isPressed = false
                                        // Show help dialog
                                        showHelpDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on Reset Zoom button
                val resetZoomButton = menu.findViewById<Button>(R.id.btnResetFontSize)
                val resetZoomRect = getRect(resetZoomButton)

                if (resetZoomButton != null && contains(resetZoomRect, buttonSlop)) {

                    // Reset font size to 100% (progress 50)
                    fontSizeSeekBar?.progress = 50

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", 50)
                            .apply()

                    // Apply to WebView
                    val savedTextColor =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getString("webTextColor", null)
                    applyWebFontSettings(50, savedTextColor)

                    // Visual feedback
                    resetZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on Reset Webpage Zoom button
                val resetWebpageZoomButton = menu.findViewById<Button>(R.id.btnResetWebpageZoom)
                val resetWebpageZoomRect = getRect(resetWebpageZoomButton)

                if (resetWebpageZoomButton != null && contains(resetWebpageZoomRect, buttonSlop)) {

                    // Reset webpage zoom to 1.0
                    currentWebZoom = 1.0f

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("webZoomLevel", currentWebZoom)
                            .apply()

                    webView.evaluateJavascript(
                            """
                        (function() {
                            document.body.style.zoom = "$currentWebZoom";
                        })();
                    """,
                            null
                    )

                    postDelayed(
                            {
                                updateScrollBarsVisibility()
                                lastScrollBarCheckTime = System.currentTimeMillis()
                            },
                            100
                    )

                    // Visual feedback
                    resetWebpageZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetWebpageZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on font size seekbar
                val fontSizeRect = getRect(fontSizeSeekBar)
                if (fontSizeSeekBar != null && contains(fontSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - fontSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, fontSizeSeekBar.width.toFloat()) /
                                    fontSizeSeekBar.width
                    val newProgress = (percentage * fontSizeSeekBar.max).toInt()

                    // Update font size
                    fontSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", newProgress)
                            .apply()

                    // Apply to WebView
                    val savedTextColor =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getString("webTextColor", null)
                    applyWebFontSettings(newProgress, savedTextColor)

                    // Visual feedback
                    fontSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ fontSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on color wheel
                val colorWheelRect = getRect(colorWheelView)
                if (colorWheelView != null && contains(colorWheelRect, sliderSlop)) {

                    // Calculate relative position
                    val relativeX = (x - colorWheelRect!!.left) / uiScale
                    val relativeY = (y - colorWheelRect.top) / uiScale

                    val selectedColor =
                            colorWheelView.calculateColorFromCoordinates(relativeX, relativeY)

                    // Update visual indicator
                    colorWheelView.setColor(selectedColor)

                    // Apply color
                    val hexColor = String.format("#%06X", (0xFFFFFF and selectedColor))
                    applyTextColor(hexColor)

                    // Visual feedback
                    colorWheelView.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ colorWheelView.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset text color button
                val resetTextColorRect = getRect(resetTextColorButton)
                if (resetTextColorButton != null && contains(resetTextColorRect, buttonSlop)) {

                    // Reset color to white
                    colorWheelView?.setColor(Color.WHITE)
                    // Reset color to white visually
                    colorWheelView?.setColor(Color.WHITE)
                    applyTextColor(null)

                    // Visual feedback
                    resetTextColorButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetTextColorButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on close button
                val closeRect = getRect(closeButton)
                if (closeButton != null && contains(closeRect, buttonSlop)) {

                    // Visual feedback
                    closeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        closeButton.isPressed = false
                                        // Close settings
                                        isSettingsVisible = false
                                        settingsMenu?.visibility = View.GONE
                                        settingsScrim?.visibility = View.GONE
                                        startRefreshing()
                                    },
                                    100
                            )
                    return
                }

            } else {
                return
            }
        }
    }

    fun playSystemSound(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK) // Play a standard click sound
    }

    /**
     * Apply font size and text color settings to the WebView via JavaScript injection.
     * @param fontSizeProgress Slider progress (0-150) which maps to 50%-200% font size
     * @param textColor Optional hex color string (e.g., "#FFFFFF")
     */
    private fun applyWebFontSettings(fontSizeProgress: Int, textColor: String?) {
        // Map progress 0-150 to font size 50%-200%
        val fontSizePercent = 50 + fontSizeProgress

        val colorCss =
                if (textColor != null) {
                    "body, body *, p, span, div, h1, h2, h3, h4, h5, h6, a, li, td, th { color: $textColor !important; }"
                } else {
                    ""
                }

        val js =
                """
            (function() {
                var styleId = 'taplink-font-settings';
                var existingStyle = document.getElementById(styleId);
                if (existingStyle) {
                    existingStyle.remove();
                }
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent = 'html { font-size: ${fontSizePercent}% !important; } $colorCss';
                document.head.appendChild(style);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // Update system info bar color
        if (textColor != null) {
            try {
                leftSystemInfoView.setTextColor(Color.parseColor(textColor))
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error updating system info color", e)
            }
        }
    }

    /**
     * Apply text color to webpage AND custom UI, then save preference.
     * @param colorHex Hex color string (e.g., "#FFFFFF")
     */
    private fun applyTextColor(colorHex: String?) {
        // Save preference
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("webTextColor", colorHex)
                .apply()

        // Update Custom UI (Settings & Keyboard)
        updateCustomUiColor(colorHex)

        // Get current font size and apply both settings to WebView
        val fontSizeProgress =
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getInt("webFontSize", 50)
        applyWebFontSettings(fontSizeProgress, colorHex)
    }

    /** Update the color of Custom UI elements (Settings Menu and Keyboard) */
    private fun updateCustomUiColor(colorHex: String?) {
        val color =
                if (colorHex != null) {
                    try {
                        Color.parseColor(colorHex)
                    } catch (e: Exception) {
                        Color.WHITE
                    }
                } else {
                    Color.WHITE
                }

        // 1. Update Keyboard
        customKeyboard?.setCustomTextColor(color)

        // 2. Update Settings Menu (Recursively find TextViews/Buttons)
        settingsMenu?.let { menu -> updateViewColorsRecursively(menu, color) }

        // 3. Update Navigation Bar
        if (::leftNavigationBar.isInitialized) {
            updateViewColorsRecursively(leftNavigationBar, color)
        }

        // 4. Update Toggle Bar
        if (::leftToggleBar.isInitialized) {
            updateViewColorsRecursively(leftToggleBar, color)
        }
    }

    private fun updateViewColorsRecursively(view: View, color: Int) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateViewColorsRecursively(view.getChildAt(i), color)
            }
        } else if (view is TextView) {
            // Apply to TextViews and Buttons (Button is subclass of TextView)
            // But verify it's not one of our special icon views if they shouldn't change
            // (FontIconView IS a TextView, so it will get colored too, which is likely desired)
            view.setTextColor(color)
        }
    }

    /** Re-apply saved font settings to the WebView. Called when a new page loads. */
    fun reapplyWebFontSettings() {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val fontSizeProgress = prefs.getInt("webFontSize", 50)
        val textColor = prefs.getString("webTextColor", null)

        applyWebFontSettings(fontSizeProgress, textColor)

        // Also ensure UI is synced (though this is mostly for initial load)
        updateCustomUiColor(textColor)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startRefreshing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRefreshing()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startRefreshing()
        } else {
            stopRefreshing()
        }
    }

    fun isInScrollMode(): Boolean {
        return isInScrollMode
    }

    fun setScrollMode(enabled: Boolean) {
        // Log.d("ScrollMode", "setScrollMode called with enabled=$enabled, current
        // isInScrollMode=$isInScrollMode")

        if (isInScrollMode == enabled) return
        isInScrollMode = enabled

        if (enabled) {
            // Immediately disable touch interception before animating
            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            leftSystemInfoView.visibility = View.GONE

            // Then animate menus away
            leftToggleBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftToggleBar.visibility = View.GONE }
                    .start()

            leftNavigationBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftNavigationBar.visibility = View.GONE }
                    .start()

            // Show force-show button
            btnShowNavBars.visibility = View.VISIBLE
            btnShowNavBars.bringToFront()
            btnShowNavBars.alpha = 0f
            btnShowNavBars.animate().alpha(1.0f).setDuration(200).start()
            btnShowNavBars.requestLayout()
        } else {
            // Re-enable touch interception and show system info bar
            leftToggleBar.isClickable = true
            leftNavigationBar.isClickable = true
            leftSystemInfoView.visibility = View.VISIBLE

            // Then show menus with animation
            leftToggleBar.visibility = View.VISIBLE
            leftToggleBar.alpha = 0f
            leftToggleBar.animate().alpha(1f).setDuration(200).start()

            leftNavigationBar.visibility = View.VISIBLE
            leftNavigationBar.alpha = 0f
            leftNavigationBar.animate().alpha(1f).setDuration(200).start()

            // Hide force-show button
            btnShowNavBars
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { btnShowNavBars.visibility = View.GONE }
                    .start()
        }

        // Update scrollbars and layout
        updateScrollBarsVisibility()

        // Force layout update
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    // Custom Dialog Logic
    fun showAlertDialog(message: String, onConfirm: () -> Unit) {
        showDialog("Alert", message, false, null, { _ -> onConfirm() }, null)
    }

    fun showConfirmDialog(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        showDialog("Confirm", message, false, null, { _ -> onConfirm() }, onCancel)
    }

    fun showPromptDialog(
            message: String,
            defaultValue: String?,
            onConfirm: (String) -> Unit,
            onCancel: () -> Unit
    ) {
        showDialog(
                "Prompt",
                message,
                true,
                defaultValue,
                { text -> onConfirm(text ?: "") },
                onCancel
        )
    }

    fun showHelpDialog(page: Int = 1) {
        val (title, message, hasNext, hasPrev) =
                when (page) {
                    1 ->
                            Quadruple(
                                    "Features: Touch & Menu",
                                    """
                TOUCH GESTURES:
                • Single Tap: Click links, buttons, and focus fields.
                • Double Tap: Go back to the previous page.
                
                TRIPLE TAP (Anchored Mode):
                • Re-centers the screen.
                """.trimIndent(),
                                    true,
                                    false
                            )
                    2 ->
                            Quadruple(
                                    "Features: Screen Modes",
                                    """
                ANCHORED MODE (Anchor Icon):
                • Screen stays fixed in space relative to the world.
                • Smoothness: Controls how rigidly the screen follows tracking.
                
                NON-ANCHORED MODE (Crossed Anchor):
                • Screen is "locked" to your head movement.
                • Screen Position: Shift the display H/V when UI Scale < 100%.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    3 ->
                            Quadruple(
                                    "Features: Display & Tools",
                                    """
                SCROLL MODE (Full Screen Icon):
                • Hides UI for an immersive browsing experience.
                • Restore UI: Tap the transparent "Show" button.
                
                UTILITIES:
                • Volume & Brightness Sliders.
                • UI Scale: Adjust the global interface size.
                • Web Zoom (+/-): Content zoom level.

                VOICE / STT:
                • Uses device speech-to-text when supported.
                • Start STT, speak clearly, and text is inserted into the active field.
                • Use scrcpy keyboard to paste your API key into the prompt field.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    4 ->
                            Quadruple(
                                    "Features: Blank Screen Mode",
                                    """
                BLANK SCREEN MODE (Eye Toggle):
                • Blacks out display while media continues playing but allows media controls.
                • Perfect for listening to audio/podcasts.
                • Note: Disables anchored mode while active.
                
                MEDIA CONTROLS (shown when media is playing):
                • Play/Pause: Toggle media playback.
                • Skip Back/Forward: Jump 10 seconds.
                • Unmask (Eye Icon): Exit blank screen mode.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    5 ->
                            Quadruple(
                                    "TapLink AI",
                                    """
                TAPLINK AI (Chat Icon):
                • Open/close with the Chat button on the bottom bar.
                • Requires a Groq API Key (Settings -> Enter Groq API Key).
                • Ask questions or use Summarize to recap the current webpage.
                • Summarize works only when a normal webpage is open.
                """.trimIndent(),
                                    false,
                                    true
                            )
                    else -> return
                }

        val footerButtons = mutableListOf<View>()

        if (hasPrev) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Back"
                        textSize = 14f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page - 1) }
                    }
            )
        }

        if (hasNext) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Next"
                        textSize = 14f
                        setTextColor(Color.parseColor("#4488FF"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page + 1) }
                    }
            )
        }

        showDialog(
                title = title,
                message = message,
                hasInput = false,
                confirmLabel = "Close",
                dismissOnAnyClick = true,
                additionalButtons = footerButtons
        )
    }

    private data class Quadruple<A, B, C, D>(
            val first: A,
            val second: B,
            val third: C,
            val fourth: D
    )

    private fun showDialog(
            title: String,
            message: String,
            hasInput: Boolean,
            defaultValue: String? = null,
            onConfirm: ((String?) -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
            confirmLabel: String? = "OK",
            dismissOnAnyClick: Boolean = false,
            additionalButtons: List<View> = emptyList()
    ) {
        dialogContainer.removeAllViews()

        // Hide keyboard container initially to avoid overlapping, though we might show it again if
        // input is focused
        keyboardContainer.visibility = View.GONE

        val padding = 16.dp()
        val dialogView =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            500, // Fixed width for consistent look
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                    setPadding(padding, padding, padding, padding)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#202020"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 16f
                            }
                    elevation = 100f
                    isClickable = true
                    isFocusable = true
                }

        // Title
        val titleView =
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 16.dp() }
                }
        dialogView.addView(titleView)

        // Message
        val messageView =
                TextView(context).apply {
                    text = message
                    textSize = 16f
                    setTextColor(Color.parseColor("#DDDDDD"))
                    maxLines = 15
                    isVerticalScrollBarEnabled = true
                    movementMethod = ScrollingMovementMethod.getInstance()
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 24.dp() }
                }
        dialogView.addView(messageView)

        var inputField: EditText? = null
        if (hasInput) {
            inputField =
                    EditText(context).apply {
                        setText(defaultValue ?: "")
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(16, 16, 16, 16)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#303030"))
                                    cornerRadius = 8f
                                }
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply { bottomMargin = 24.dp() }

                        // Important: Show custom keyboard on focus
                        setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) {
                                keyboardListener?.onShowKeyboard()
                            }
                        }

                        // Allow our custom keyboard to input text here
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setSingleLine()
                    }
            dialogView.addView(inputField)
        }

        // Buttons
        val buttonContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                }

        if (onCancel != null) {
            val cancelButton =
                    Button(context).apply {
                        text = "Cancel"
                        textSize = 16f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onCancel()
                            hideDialog()
                        }
                    }
            buttonContainer.addView(cancelButton)
        }

        additionalButtons.forEach { button ->
            if (button is Button) {
                button.background =
                        ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            }
            buttonContainer.addView(button)
        }

        if (confirmLabel != null) {
            val confirmButton =
                    Button(context).apply {
                        text = confirmLabel
                        textSize = 16f
                        setTextColor(Color.parseColor("#4488FF"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onConfirm?.invoke(inputField?.text?.toString())
                            hideDialog()
                        }
                    }
            buttonContainer.addView(confirmButton)
        }

        dialogView.addView(buttonContainer)
        dialogContainer.addView(dialogView)
        dialogContainer.visibility = View.VISIBLE
        dialogContainer.bringToFront()
        if (dismissOnAnyClick) {
            dialogContainer.setOnClickListener { hideDialog() }
            // DON'T set listener on dialogView, so clicks inside don't dismiss
        }

        // Ensure rendering updates
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun hideDialog() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Determine whether to show keyboard container again
        if (customKeyboard?.visibility == View.VISIBLE) {
            keyboardContainer.visibility = View.VISIBLE
        }

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    private var toastHandler: Handler? = Handler(Looper.getMainLooper())
    private var toastRunnable: Runnable? = null

    /**
     * Shows a toast message that renders in both eyes.
     * @param message The message to display
     * @param durationMs How long to show the toast (default 2000ms)
     */
    fun showToast(message: String, durationMs: Long = 2000L) {
        // Log.d("Toast", "showToast called with message: $message")
        // Ensure we're on the UI thread
        post {
            // Log.d("Toast", "Inside post block, creating toast view")
            // Cancel any existing toast
            toastRunnable?.let { toastHandler?.removeCallbacks(it) }
            dialogContainer.removeAllViews()

            val padding = 16.dp()
            val toastView =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                            bottomMargin = 64.dp()
                                        }
                        setPadding(padding * 2, padding, padding * 2, padding)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#E0303030")) // Semi-transparent dark
                                    cornerRadius = 24f
                                }
                        elevation = 100f
                    }

            val messageView =
                    TextView(context).apply {
                        text = message
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }
            toastView.addView(messageView)

            // Use a transparent scrim for toast (unlike dialogs which block interaction)
            dialogContainer.setBackgroundColor(Color.TRANSPARENT)
            dialogContainer.addView(toastView)
            dialogContainer.visibility = View.VISIBLE
            dialogContainer.bringToFront()
            dialogContainer.isClickable = false // Allow clicks to pass through

            // Log.d("Toast", "Toast view added, dialogContainer visible:
            // ${dialogContainer.visibility == View.VISIBLE}, child count:
            // ${dialogContainer.childCount}")

            // Ensure rendering updates
            requestLayout()
            invalidate()
            startRefreshing()

            // Auto-dismiss after duration
            toastRunnable = Runnable { hideToast() }
            toastHandler?.postDelayed(toastRunnable!!, durationMs)
        }
    }

    private fun hideToast() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Restore dialog container background for future dialogs
        dialogContainer.setBackgroundColor(Color.parseColor("#CC000000"))
        dialogContainer.isClickable = true

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    // Helper method to get the current dialog input if any
    fun getDialogInput(): EditText? {
        if (dialogContainer.visibility != View.VISIBLE) return null
        val dialogView = dialogContainer.getChildAt(0) as? ViewGroup ?: return null
        // Scan for EditText
        for (i in 0 until dialogView.childCount) {
            val child = dialogView.getChildAt(i)
            if (child is EditText) return child
        }
        return null
    }

    fun isDialogAction(x: Float, y: Float): Boolean {
        if (dialogContainer.visibility != View.VISIBLE || !dialogContainer.isClickable) return false
        val loc = IntArray(2)
        dialogContainer.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (dialogContainer.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (dialogContainer.height * uiScale)
    }

    private fun setupMaskOverlayUI() {

        // Unmask button (Bottom Right)
        btnMaskUnmask =
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_visibility_on)
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(8, 8, 8, 8)
                    setOnTouchListener { view, event ->
                        when (event.action) {
                            MotionEvent.ACTION_UP -> {
                                view.performClick()
                                unmaskScreen()
                            }
                        }
                        true // Consume the event to prevent propagation
                    }
                }
        val unmaskParams =
                FrameLayout.LayoutParams(40, 40).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = 8
                    bottomMargin = 8
                }
        maskOverlay.addView(btnMaskUnmask, unmaskParams)

        // Media Controls Container (Bottom Center)
        maskMediaControlsContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.TRANSPARENT)
                    visibility = View.GONE // Hidden by default until media detected
                }
        val controlsParams =
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 40).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 8
                }
        maskOverlay.addView(maskMediaControlsContainer, controlsParams)

        // Controls - Order: Prev Track, 10s Back, Play, Pause, 10s Forward, Next Track
        btnMaskPrevTrack =
                createMediaButton(R.string.fa_backward_step) {
                    // Try to click previous track button (works on YouTube, Spotify, etc.)
                    getMediaControlWebView()
                            .evaluateJavascript(
                                    """
                (function() {
                    // Try common previous track selectors
                    var prevBtn = document.querySelector('.ytp-prev-button') || 
                                  document.querySelector('[aria-label*="previous" i]') ||
                                  document.querySelector('[title*="previous" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-back"]');
                    if (prevBtn) { prevBtn.click(); return; }
                    // Fallback: Skip to beginning
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = 0;
                })();
            """.trimIndent(),
                                    null
                            )
                }
        btnMaskPrev =
                createMediaButton(R.string.fa_backward) {
                    getMediaControlWebView()
                            .evaluateJavascript(
                                    "document.querySelector('video, audio').currentTime -= 10;",
                                    null
                            )
                }
        btnMaskPlay =
                createMediaButton(R.string.fa_play) {
                    getMediaControlWebView()
                            .evaluateJavascript(
                                    "document.querySelector('video, audio').play();",
                                    null
                            )
                    // Immediately update button visibility for responsive UI
                    btnMaskPlay.visibility = View.GONE
                    btnMaskPause.visibility = View.VISIBLE
                }
        btnMaskPause =
                createMediaButton(R.string.fa_pause) {
                    getMediaControlWebView()
                            .evaluateJavascript(
                                    "document.querySelector('video, audio').pause();",
                                    null
                            )
                    // Immediately update button visibility for responsive UI
                    btnMaskPause.visibility = View.GONE
                    btnMaskPlay.visibility = View.VISIBLE
                }
        btnMaskNext =
                createMediaButton(R.string.fa_forward) {
                    getMediaControlWebView()
                            .evaluateJavascript(
                                    "document.querySelector('video, audio').currentTime += 10;",
                                    null
                            )
                }
        btnMaskNextTrack =
                createMediaButton(R.string.fa_forward_step) {
                    // Try to click next track button (works on YouTube, Spotify, etc.)
                    getMediaControlWebView()
                            .evaluateJavascript(
                                    """
                (function() {
                    // Try common next track selectors
                    var nextBtn = document.querySelector('.ytp-next-button') || 
                                  document.querySelector('[aria-label*="next" i]') ||
                                  document.querySelector('[title*="next" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-forward"]');
                    if (nextBtn) { nextBtn.click(); return; }
                    // Fallback: Skip to end (triggers autoplay to next)
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = media.duration;
                })();
            """.trimIndent(),
                                    null
                            )
                }

        btnMaskPause.visibility = View.GONE // Initially show Play

        maskMediaControlsContainer.addView(btnMaskPrevTrack)
        maskMediaControlsContainer.addView(btnMaskPrev)
        maskMediaControlsContainer.addView(btnMaskPlay)
        maskMediaControlsContainer.addView(btnMaskPause)
        maskMediaControlsContainer.addView(btnMaskNext)
        maskMediaControlsContainer.addView(btnMaskNextTrack)
    }

    private fun createMediaButton(iconRes: Int, onClick: () -> Unit): FontIconView {
        return FontIconView(context).apply {
            setText(iconRes)
            setBackgroundResource(R.drawable.nav_button_background)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            layoutParams =
                    LinearLayout.LayoutParams(40, 40).apply {
                        leftMargin = 4
                        rightMargin = 4
                    }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun updateMediaState(isPlaying: Boolean) {
        // Log.d("MediaControls", "updateMediaState called: isPlaying=$isPlaying,
        // isScreenMasked=$isScreenMasked")
        isMediaPlaying = isPlaying
        if (isPlaying) {
            lastMediaPlayingAt = SystemClock.uptimeMillis()
        }
        post {
            if (isPlaying) {
                // Log.d("MediaControls", "Setting to playing state")
                btnMaskPlay.visibility = View.GONE
                btnMaskPause.visibility = View.VISIBLE
                maskMediaControlsContainer.visibility = View.VISIBLE
                // Log.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}, parent:
                // ${maskMediaControlsContainer.parent}")
            } else {
                // Log.d("MediaControls", "Setting to paused state")
                btnMaskPlay.visibility = View.VISIBLE
                btnMaskPause.visibility = View.GONE
                // Keep controls visible if we know media exists
                maskMediaControlsContainer.visibility = View.VISIBLE
                // Log.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}")
            }
        }
    }

    fun isMediaPlaying(): Boolean {
        return isMediaPlaying
    }

    fun hideMediaControls() {
        post { maskMediaControlsContainer.visibility = View.GONE }
    }

    private fun handleMediaStateChanged(sourceWebView: WebView, isPlaying: Boolean) {
        val windowId = windows.firstOrNull { it.webView == sourceWebView }?.id
        if (windowId == null) {
            updateMediaState(isPlaying)
            return
        }

        mediaStateByWindowId[windowId] = isPlaying
        if (isPlaying) {
            mediaLastPlayedAtByWindowId[windowId] = SystemClock.uptimeMillis()
        }

        val anyPlaying = mediaStateByWindowId.values.any { it }
        updateMediaState(anyPlaying)
    }

    private fun getMediaControlWebView(): WebView {
        val playingIds = mediaStateByWindowId.filterValues { it }.keys
        val targetId =
                if (playingIds.isNotEmpty()) {
                    playingIds.maxByOrNull { mediaLastPlayedAtByWindowId[it] ?: 0L }
                } else {
                    mediaLastPlayedAtByWindowId.keys.maxByOrNull {
                        mediaLastPlayedAtByWindowId[it] ?: 0L
                    }
                }

        return windows.firstOrNull { it.id == targetId }?.webView ?: webView
    }

    fun injectLocation(latitude: Double, longitude: Double) {
        val script =
                """
            (function() {
                // Store the position globally so it persists
                window.__injectedPosition = {
                    coords: {
                        latitude: $latitude,
                        longitude: $longitude,
                        accuracy: 5.0,
                        altitude: null,
                        altitudeAccuracy: null,
                        heading: null,
                        speed: null
                    },
                    timestamp: new Date().getTime()
                };
                
                // 1. Mock Permissions API to always return 'granted'
                if (navigator.permissions) {
                    var originalQuery = navigator.permissions.query.bind(navigator.permissions);
                    navigator.permissions.query = function(parameters) {
                        if (parameters.name === 'geolocation') {
                            return Promise.resolve({ state: 'granted', onchange: null });
                        }
                        return originalQuery(parameters);
                    };
                }
                
                // 2. Override Geolocation API using defineProperty for robustness
                var mockGeolocation = {
                    getCurrentPosition: function(success, error, options) {
                        setTimeout(function() {
                            success(window.__injectedPosition);
                        }, 10);
                    },
                    watchPosition: function(success, error, options) {
                        var watchId = Math.floor(Math.random() * 10000);
                        setTimeout(function() {
                            success(window.__injectedPosition);
                        }, 10);
                        return watchId;
                    },
                    clearWatch: function(id) {
                        // Do nothing
                    }
                };
                
                try {
                    Object.defineProperty(navigator, 'geolocation', {
                        value: mockGeolocation,
                        writable: false,
                        configurable: true
                    });
                } catch (e) {
                    // Fallback if defineProperty fails
                    navigator.geolocation.getCurrentPosition = mockGeolocation.getCurrentPosition;
                    navigator.geolocation.watchPosition = mockGeolocation.watchPosition;
                    navigator.geolocation.clearWatch = mockGeolocation.clearWatch;
                }
                
                console.log("[TapLink] Location injected: " + $latitude + ", " + $longitude);
            })();
        """.trimIndent()

        post { webView.evaluateJavascript(script, null) }
    }

    fun injectMediaListeners(targetWebView: WebView) {
        val script =
                """
            (function() {
                if (window.__mediaListenersInjected) return;
                window.__mediaListenersInjected = true;

                function attachListeners(media) {
                    if (media.__listenersAttached) return;
                    media.__listenersAttached = true;
                    
                    media.addEventListener('play', function() {
                        console.log('MediaInterface: play detected');
                        window.MediaInterface.onMediaStateChanged(true);
                    });
                    
                    media.addEventListener('pause', function() {
                        // Check if any other media is playing before saying paused
                        const allMedia = document.querySelectorAll('video, audio');
                        let anyPlaying = false;
                        for(let i=0; i<allMedia.length; i++) {
                            if(!allMedia[i].paused && !allMedia[i].ended && allMedia[i].readyState > 2) {
                                anyPlaying = true;
                                break;
                            }
                        }
                        console.log('MediaInterface: pause detected. Any playing? ' + anyPlaying);
                        window.MediaInterface.onMediaStateChanged(anyPlaying);
                    });
                    
                    media.addEventListener('ended', function() {
                         const allMedia = document.querySelectorAll('video, audio');
                        let anyPlaying = false;
                        for(let i=0; i<allMedia.length; i++) {
                            if(!allMedia[i].paused && !allMedia[i].ended && allMedia[i].readyState > 2) {
                                anyPlaying = true;
                                break;
                            }
                        }
                        console.log('MediaInterface: ended detected. Any playing? ' + anyPlaying);
                        window.MediaInterface.onMediaStateChanged(anyPlaying);
                    });
                }

                // Attach to existing media
                const existingMedia = document.querySelectorAll('video, audio');
                existingMedia.forEach(attachListeners);

                // Observe for new media
                const observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.nodeName === 'VIDEO' || node.nodeName === 'AUDIO') {
                                attachListeners(node);
                            } else if (node.querySelectorAll) {
                                node.querySelectorAll('video, audio').forEach(attachListeners);
                            }
                        });
                    });
                });
                
                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()

        targetWebView.evaluateJavascript(script, null)
    }

    private class MediaInterface(
            private val parent: DualWebViewGroup,
            private val sourceWebView: WebView
    ) {
        @android.webkit.JavascriptInterface
        fun onMediaStateChanged(isPlaying: Boolean) {
            // Run on UI thread to update UI
            parent.post { parent.handleMediaStateChanged(sourceWebView, isPlaying) }
        }
    }
}
