package com.TapLinkX3.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt


@SuppressLint("ClickableViewAccessibility")
class DualWebViewGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    // Custom WebView to expose protected scroll methods
    private inner class InternalWebView(context: Context) : WebView(context) {
        fun getHorizontalScrollRange() = super.computeHorizontalScrollRange()
        fun getHorizontalScrollExtent() = super.computeHorizontalScrollExtent()
        fun getHorizontalScrollOffset() = super.computeHorizontalScrollOffset()
        fun getVerticalScrollRange() = super.computeVerticalScrollRange()
        fun getVerticalScrollExtent() = super.computeVerticalScrollExtent()
        fun getVerticalScrollOffset() = super.computeVerticalScrollOffset()
    }

    private val webView = InternalWebView(context)
    private val rightEyeView: SurfaceView = SurfaceView(context)
    val keyboardContainer: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }
    
    val dialogContainer: FrameLayout = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(640, 480)  // Full left eye size
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
    private var lastCaptureTime = 0L
    private var lastScrollBarCheckTime = 0L
    private val MIN_CAPTURE_INTERVAL = 16L  // Cap at ~60fps
    private var lastCursorUpdateTime = 0L
    private val CURSOR_UPDATE_INTERVAL = 16L  // 60fps cap for cursor updates

    private var leftSystemInfoView: SystemInfoView

    lateinit var leftNavigationBar: View
    private val verticalBarSize = 480 - 30
    private val nButtons    = 7
    private val buttonHeight = verticalBarSize / nButtons
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
    var progressBar: android.widget.ProgressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 4)
        progressDrawable.setTint(Color.BLUE)
        max = 100
        visibility = View.GONE
        elevation = 200f // Ensure it's above other views
    }
    private var btnShowNavBars: ImageButton = ImageButton(context).apply {
        layoutParams = FrameLayout.LayoutParams(40, 40).apply {
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
        setOnClickListener {
            setScrollMode(false)
        }
    }

    @Volatile private var isRefreshing = false
    private val refreshLock = Any()

    private var isDesktopMode = false
    private var currentWebZoom = 1.0f
    private var isHoveringModeToggle = false
    private var isHoveringDashboardToggle = false
    private var isHoveringBookmarksMenu = false

    private lateinit var leftBookmarksView: BookmarksView

    var navigationListener: NavigationListener? = null
    var linkEditingListener: LinkEditingListener? = null

    private var isBookmarkEditing = false

    private var mobileUserAgent: String = webView.settings.userAgentString
    private var desktopUserAgent: String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    private val verticalScrollFraction = 0.25f // Scroll vertically by 25% of the viewport per tap

    private var isHoveringZoomIn = false
    private var isHoveringZoomOut = false

    private var fullScreenTapDetector: GestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Always accept the initial down event so we can track the full gesture
            return fullScreenOverlayContainer.visibility == View.VISIBLE
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
                (context as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
                return true
            }
            return false
        }
    })

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
                requestLayout()  // Force layout update to position progress bar correctly
            } else {
                progressBar.progress = 100
                // Delay hiding to ensure user sees 100%
                hideProgressBarRunnable = Runnable {
                    progressBar.visibility = View.GONE
                }
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


    private var navButtons: Map<String, NavButton>

    var listener: DualWebViewGroupListener? = null
    var maskToggleListener: MaskToggleListener? = null

    val leftEyeUIContainer = FrameLayout(context).apply {
        clipChildren = true
        clipToOutline = true

        setBackgroundColor(Color.TRANSPARENT)  // Make sure background is transparent
    }

    private val fullScreenOverlayContainer = FrameLayout(context).apply {
        clipChildren = true
        clipToOutline = true  // Ensure clipping to bounds
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
        isFocusable = true
    }
    
    // UI scale factor (0.5 to 1.0) - controlled by screen size slider
    var uiScale = 1.0f

    private val fullScreenHiddenViews: List<View> by lazy {
        listOf(
            webView,
            leftToggleBar,
            leftNavigationBar,
            keyboardContainer,
            leftSystemInfoView,
            urlEditText
        )
    }

    private val previousFullScreenVisibility = mutableMapOf<View, Int>()

    val leftEyeClipParent = FrameLayout(context).apply {
        // Force it to be exactly 640px wide and match height (or some fixed height).
        // Using MATCH_PARENT for height is common if you want the full vertical space.
        layoutParams = FrameLayout.LayoutParams(
            640,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Ensure that children are clipped to our bounds
        clipToPadding = true
        clipChildren = true
        setBackgroundColor(Color.BLACK)  // Set background to ensure proper rendering
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
            webView.scrollBy(scrollAmount, 0)
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
            webView.scrollBy(0, scrollAmount)
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

    private fun updateScrollBarThumbs(xProgress: Int, yProgress: Int) {
        if (isWebViewScrollEnabled()) {
            // Update Horizontal Thumb based on WebView scroll
            val hTrackWidth = (horizontalScrollBar.getChildAt(1) as? FrameLayout)?.width ?: 0
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                // Calculate ratio: scrollX / (contentWidth - viewportWidth)
                // Since we can't easily get full content width without computeHorizontalScrollRange (protected),
                // we'll rely on an approximation or need to subclass WebView. 
                // For now, let's try using the standard range approximation if possible, or just skip if we can't get it.
                // Actually, we can use computeHorizontalScrollRange via reflection or just use scrollX/ArbitraryLargeNumber if needed,
                // but simpler is to use `webView.scrollX` relative to estimated width.
                // Let's defer exact horizontal proportion calculation or use a safe fallback.
                
                // Using standard view methods available on WebView (which is a View)
                val range = webView.getHorizontalScrollRange()
                val extent = webView.getHorizontalScrollExtent()
                val offset = webView.getHorizontalScrollOffset()
                
                if (range > extent) {
                    val ratio = offset.toFloat() / (range - extent).toFloat()
                    val hMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    
                    (hScrollThumb.layoutParams as? FrameLayout.LayoutParams)?.let {
                        it.leftMargin = hMargin
                        hScrollThumb.layoutParams = it
                    }
                }
            }

            // Update Vertical Thumb based on WebView scroll
            val vTrackHeight = (verticalScrollBar.getChildAt(1) as? FrameLayout)?.height ?: 0
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight
                
                val range = webView.getVerticalScrollRange()
                val extent = webView.getVerticalScrollExtent()
                val offset = webView.getVerticalScrollOffset()

                if (range > extent) {
                    val ratio = offset.toFloat() / (range - extent).toFloat()
                    val vMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    
                    (vScrollThumb.layoutParams as? FrameLayout.LayoutParams)?.let {
                        it.topMargin = vMargin
                        vScrollThumb.layoutParams = it
                    }
                }
            }
        } else {
            // Existing logic for non-anchored (viewport pan)
            // Update horizontal thumb position
            val hTrackWidth = (horizontalScrollBar.getChildAt(1) as? FrameLayout)?.width ?: 0
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                val hMargin = (xProgress / 100f * maxMargin).toInt()
                (hScrollThumb.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.leftMargin = hMargin
                    hScrollThumb.layoutParams = it
                }
            }

            // Update vertical thumb position
            val vTrackHeight = (verticalScrollBar.getChildAt(1) as? FrameLayout)?.height ?: 0
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight
                val vMargin = (yProgress / 100f * maxMargin).toInt()
                (vScrollThumb.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.topMargin = vMargin
                    vScrollThumb.layoutParams = it
                }
            }
        }
    }

    fun updateScrollBarsVisibility() {
        // Log.d("ScrollDebug", "updateScrollBarsVisibility called. isAnchored=$isAnchored, isInScrollMode=$isInScrollMode, uiScale=$uiScale")

        // Determine mode-specific base constraints
        val isScrollModeActive = isInScrollMode
        
        // Base dimensions
        // Base dimensions
        val containerWidth = 640
        // Corrected base margins: Nav bar is 30px, Toggle bar is 40px
        val baseLeftMargin = if (isScrollModeActive) 0 else 40
        val baseBottomMargin = if (isScrollModeActive) 0 else 30 
        
        // If anchored, scrollbars are always hidden
        if (isAnchored) {
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE
            
            (webView.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                var targetWidth = 0
                var targetHeight = 0
                if (isScrollModeActive) {
                    targetWidth = containerWidth
                    targetHeight = 480
                } else {
                    targetWidth = containerWidth - baseLeftMargin // 640 - 40 = 600
                    targetHeight = FrameLayout.LayoutParams.MATCH_PARENT
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
                    webView.layoutParams = p
                    webView.requestLayout()
                    webView.invalidate()
                }
            }
            return
        }

        // Always check WebView scrollability since we disabled viewport panning
        val showHorz = webView.canScrollHorizontally(-1) || webView.canScrollHorizontally(1)
        val showVert = webView.canScrollVertically(-1) || webView.canScrollVertically(1)
        
        horizontalScrollBar.visibility = if (showHorz) View.VISIBLE else View.GONE
        verticalScrollBar.visibility = if (showVert) View.VISIBLE else View.GONE
        
        // Apply layout adjustments
        (webView.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            val rightMarginShift = if (showVert) 20 else 0
            val bottomMarginShift = if (showHorz) 20 else 0

            var targetWidth = 0
            var targetHeight = 0
            var targetLeftMargin = 0
            var targetBottomMargin = 0
            var targetRightMargin = 0

            if (isScrollModeActive) {
                 // Scroll Mode: 640 total width
                 targetWidth = 640 - rightMarginShift
                 targetHeight = 480 - bottomMarginShift
                 targetLeftMargin = 0
                 targetRightMargin = rightMarginShift 
                 targetBottomMargin = bottomMarginShift
            } else {
                 // Normal Mode:
                 // Width: 640 total - 40 toggle - margin
                 targetWidth = (640 - baseLeftMargin) - rightMarginShift
                 
                 // Height: 480 total - 40 nav - margin
                 // We must be explicit here so onMeasure picks it up
                 targetHeight = (480 - baseBottomMargin) - bottomMarginShift
                 
                 targetLeftMargin = baseLeftMargin // 40
                 targetRightMargin = rightMarginShift
                 targetBottomMargin = baseBottomMargin + bottomMarginShift // 40 + shift
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
                
                // Log.d("ScrollDebug", "Applying Layout: Mode=${if(isScrollModeActive)"Scroll" else "Normal"}, [${p.width} x ${p.height}], Margins: L=${p.leftMargin}, R=${p.rightMargin}, B=${p.bottomMargin}")
                webView.layoutParams = p
                webView.requestLayout()
                webView.invalidate()
            }
        }
        
        // Remove unconditional requestLayout/invalidate here
        // webView.requestLayout()
        // webView.invalidate()

        if (horizontalScrollBar.visibility == View.VISIBLE || verticalScrollBar.visibility == View.VISIBLE) {
             updateScrollBarThumbs(0, 0)
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
                // Visual cursor is scaled around (320, 240) and then translated (only in non-anchored mode)
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
    private var maskOverlay: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT) // Left eye width only
        elevation = 1000f  // Put it above everything except cursors
        isClickable = true
        isFocusable = true
        
        // Consume all touch events to prevent propagation to navbar/webview behind
        // Child buttons (unmask, media controls) will still work because they handle events first
        setOnTouchListener { _, _ -> true }
    }

    // Mask mode UI elements
    private lateinit var maskMediaControlsContainer: LinearLayout
    private lateinit var btnMaskPrevTrack: FontIconView  // Skip to previous song
    private lateinit var btnMaskPrev: FontIconView        // 10s back
    private lateinit var btnMaskPlay: FontIconView
    private lateinit var btnMaskPause: FontIconView
    private lateinit var btnMaskNext: FontIconView        // 10s forward
    private lateinit var btnMaskNextTrack: FontIconView  // Skip to next song
    private lateinit var btnMaskUnmask: ImageButton



    var anchorToggleListener: AnchorToggleListener? = null

    // Add properties to track translations
    private var _translationX = 0f
    private var _translationY = 0f
    private var _rotationZ    = 0f

    private var isInScrollMode = false
    private var settingsScrim: View? = null

    // Scroll bar containers for non-anchored mode
    private var horizontalScrollBar: LinearLayout
    private var verticalScrollBar: LinearLayout
    private var hScrollThumb: View
    private var vScrollThumb: View


    init {



        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        isDesktopMode = prefs.getBoolean("isDesktopMode", false)
        currentWebZoom = prefs.getFloat("webZoomLevel", 1.0f)
        updateBrowsingMode(isDesktopMode)

        // Set the background of the entire DualWebViewGroup to black
        setBackgroundColor(Color.BLACK)

        // Ensure the left eye (Activity Window) uses the same pixel format as the right eye (SurfaceView)
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

        // Configure WebView for left eye
        webView.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false

            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Prevent white flash during load


            //setBackgroundColor(Color.TRANSPARENT)  // Changed to TRANSPARENT to allow mirroring
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setOnTouchListener { _, _ ->
                keyboardContainer.visibility == View.VISIBLE
            }
            setOnLongClickListener { true }

            // Add scroll listener to update thumbs
            setOnScrollChangeListener { _, _, _, _, _ ->
                if (isWebViewScrollEnabled()) {
                    updateScrollBarThumbs(0, 0)
                    // REMOVED: updateScrollBarsVisibility() - moved to onPageFinished manually
                    // updateScrollBarsVisibility() 
                }
            }

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    updateLoadingProgress(newProgress)
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    showAlertDialog(message ?: "") { result?.confirm() }
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    showConfirmDialog(message ?: "", { result?.confirm() }, { result?.cancel() })
                    return true
                }

                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                    showPromptDialog(message ?: "", defaultValue, { input -> result?.confirm(input) }, { result?.cancel() })
                    return true
                }

                override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    showConfirmDialog(message ?: "Are you sure you want to leave this page?", { result?.confirm() }, { result?.cancel() })
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    
                    // Re-apply font settings
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedFontSize = prefs.getInt("webFontSize", 50)
                    val savedTextColor = prefs.getString("webTextColor", null)
                    applyWebFontSettings(savedFontSize, savedTextColor)
                    
                    // Re-apply zoom
                     webView.evaluateJavascript("""
                        (function() {
                            document.body.style.zoom = "$currentWebZoom";
                        })();
                    """, null)
                    
                    updateScrollBarsVisibility()
                }
            }
        }


        // Configure SurfaceView for right eye mirroring
        rightEyeView.apply {
            isClickable = false
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    setupBitmap(width, height)
                    startRefreshing()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    setupBitmap(width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    synchronized(bitmapLock) {
                        val currentBitmap = bitmap
                        bitmap = null  // Set to null first
                        currentBitmap?.let { bmp ->
                            if (!bmp.isRecycled) {
                                bmp.recycle()
                            }
                        }
                    }
                    stopRefreshing()
                }
            })
        }






        // Initialize keyboard containers
        keyboardContainer.apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // Log.d("KeyboardDebug", "leftKeyboardContainer clicked")
            }
            setOnTouchListener { _, event ->
                // Log.d("KeyboardDebug", "leftKeyboardContainer received touch event: ${event.action}")
                true
            }
        }
        

        // Initialize navigation bars
        leftNavigationBar = LayoutInflater.from(context).inflate(R.layout.navigation_bar, this, false).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                40
            )
            setBackgroundColor(Color.parseColor("#202020"))
            visibility = View.VISIBLE
            setPadding(16, 0, 16, 0)
        }

        // Initialize navigation buttons
        navButtons = mapOf(
            "back" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnBack),
                right = leftNavigationBar.findViewById(R.id.btnBack)
            ),
            "forward" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnForward),
                right = leftNavigationBar.findViewById(R.id.btnForward)
            ),
            "home" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnHome),
                right = leftNavigationBar.findViewById(R.id.btnHome)
            ),
            "link" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnLink),
                right = leftNavigationBar.findViewById(R.id.btnLink)
            ),
            "settings" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnSettings),
                right = leftNavigationBar.findViewById(R.id.btnSettings)
            ),
            "refresh" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnRefresh),
                right = leftNavigationBar.findViewById(R.id.btnRefresh)
            ),
            "hide" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnHide),
                right = leftNavigationBar.findViewById(R.id.btnHide)
            ),
            "quit" to NavButton(
                left = leftNavigationBar.findViewById(R.id.btnQuit),
                right = leftNavigationBar.findViewById(R.id.btnQuit)
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
        leftToggleBar = LayoutInflater.from(context).inflate(R.layout.toggle_bar, this, false).apply {
            layoutParams = LayoutParams(40, 592)
            setBackgroundColor(Color.parseColor("#202020"))
            visibility = View.VISIBLE
            clipToOutline = true  // Add this
            clipChildren = true   // Add this
            isClickable = true    // Add this
            isFocusable = true    // Add this
        }



        // Log.d("ViewDebug", "Toggle bar initialized with hash: ${leftToggleBar.hashCode()}")



        setupMaskOverlayUI()

        // Set background styles - use gradient drawables for modern look
        setBackgroundColor(Color.BLACK)
        leftNavigationBar.background = ContextCompat.getDrawable(context, R.drawable.nav_bar_background)
        leftToggleBar.background = ContextCompat.getDrawable(context, R.drawable.toggle_bar_background)



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
        urlEditText  =  setupUrlEditText(true)



        //addView(urlEditText)

        // Bring urlEditTextLeft to front
        urlEditText.bringToFront()

        // Disable text handles for both EditTexts
        disableTextHandles(urlEditText)


//


        // Initialize the edit fields
        leftEditField = EditText(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#303030"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setPadding(16, 12, 16, 12)

            // Style the edit field
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#303030"))
                setStroke(2, Color.parseColor("#404040"))
                cornerRadius = 8f
            }
        }

        rightEditField = EditText(context).apply {
            // Same styling as leftEditField
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#303030"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#303030"))
                setStroke(2, Color.parseColor("#404040"))
                cornerRadius = 8f
            }
        }




        // Add edit fields to view hierarchy
        addView(leftEditField)
        addView(rightEditField)



        //addView(keyboardContainer)


        leftSystemInfoView = SystemInfoView(context).apply {
            layoutParams = LayoutParams(
                200,  // Fixed initial width, will be adjusted after measure
                24
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            elevation = 900f
            visibility = View.VISIBLE // Explicitly set visibility
        }

        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                /* Log.d("TouchDebug", """
                Toggle Bar Layout:
                Width: ${leftToggleBar.width}
                Height: ${leftToggleBar.height}
                Left: ${leftToggleBar.left}
                Top: ${leftToggleBar.top}
                Translation: (${leftToggleBar.translationX}, ${leftToggleBar.translationY})
            """.trimIndent()) */
                viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })



        // Make sure they're above other elements
        leftSystemInfoView.bringToFront()




        post {
            // Ensure bookmarks views are always on top when added to view hierarchy
            if (::leftBookmarksView.isInitialized) {
                leftBookmarksView.bringToFront()
            }
        }

        // Set up the container hierarchy
        leftEyeClipParent.addView(leftEyeUIContainer)
        leftEyeClipParent.addView(fullScreenOverlayContainer) // Add to clip parent for proper clipping

        // Add views to UI container
        leftEyeUIContainer.apply {
            // Add views in the correct z-order
            // Add webView with correct position
            addView(webView, FrameLayout.LayoutParams(640 - 40, LayoutParams.MATCH_PARENT).apply {
                leftMargin = 40  // Position after toggle bar
                bottomMargin = 40 // Account for nav bar
            })
            addView(leftToggleBar)
            // Log.d("ViewDebug", "Toggle bar added to UI container with hash: ${leftToggleBar.hashCode()}")

            addView(leftNavigationBar.apply{
                elevation = 101f
            })
            addView(btnShowNavBars) // Add show nav bars button
            addView(progressBar) // Add progress bar
            addView(keyboardContainer)
            addView(dialogContainer)
            addView(leftSystemInfoView)
            addView(urlEditText)
            addView(maskOverlay) // Add mask overlay for proper mirroring to both eyes
            postDelayed({


                initializeToggleButtons()
                requestLayout()
                invalidate()
            },100)

            post{
                leftSystemInfoView.measure(
                    MeasureSpec.makeMeasureSpec(640, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(24, MeasureSpec.EXACTLY)
                )
                leftSystemInfoView.requestLayout()
                leftSystemInfoView.invalidate()
            }

            // Make sure container is visible and properly layered
            visibility = View.VISIBLE
            elevation = 100f  // Keep it above webview


        }



        // After other view initializations


        // Add the clip parent to the main view
        addView(leftEyeClipParent)
        addView(rightEyeView)  // Keep right eye view separate
        // maskOverlay now added to leftEyeUIContainer above for proper mirroring

        // Create horizontal scroll bar
        horizontalScrollBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK) // Black background
            visibility = View.GONE
            elevation = 150f
            isClickable = true // Prevent click propagation
            isFocusable = true

            // Left arrow button
            // Left arrow button
            val btnLeft = FontIconView(context).apply {
                layoutParams = LinearLayout.LayoutParams(20, 20)
                setText(R.string.fa_arrow_left)
                setBackgroundResource(R.drawable.scroll_button_background)
                gravity = Gravity.CENTER
                textSize = 10f
                setPadding(0, 0, 0, 0)
            }
            addView(btnLeft)

            // Track container with thumb
            val trackContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 20, 1f)
                setBackgroundColor(Color.parseColor("#303030"))
            }
            hScrollThumb = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(60, 16).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = 0
                }
                setBackgroundResource(R.drawable.scroll_button_background)
            }
            trackContainer.addView(hScrollThumb)
            addView(trackContainer)

            // Right arrow button
            // Right arrow button
            val btnRight = FontIconView(context).apply {
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
                if (event.action == MotionEvent.ACTION_UP) {
                    val fullWidth = v.width
                    val thumbWidth = hScrollThumb.width
                    val clickX = event.x
                    // Center the thumb on the click
                    val clickLeft = clickX - thumbWidth / 2
                    val trackableWidth = fullWidth - thumbWidth
                    val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)

                    if (isWebViewScrollEnabled()) {
                        val range = webView.getHorizontalScrollRange()
                        val extent = webView.getHorizontalScrollExtent()
                        if (range > extent) {
                            val targetX = percent * (range - extent)
                            webView.scrollTo(targetX.toInt(), webView.scrollY)
                        }
                    } else {
                        val newProgress = (percent * 100).toInt()
                        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putInt("uiTransXProgress", newProgress).apply()
                        updateUiTranslation()
                    }
                }
                true
            }
        }

        // Create vertical scroll bar
        verticalScrollBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK) // Black background
            visibility = View.GONE
            elevation = 150f
            isClickable = true // Prevent click propagation
            isFocusable = true

            // Up arrow button
            // Up arrow button
            val btnUp = FontIconView(context).apply {
                layoutParams = LinearLayout.LayoutParams(20, 20)
                setText(R.string.fa_arrow_up)
                setBackgroundResource(R.drawable.scroll_button_background)
                gravity = Gravity.CENTER
                textSize = 10f
                setPadding(0, 0, 0, 0)
            }
            addView(btnUp)

            // Track container with thumb
            val trackContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(20, 0, 1f)
                setBackgroundColor(Color.parseColor("#303030"))
            }
            vScrollThumb = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(16, 60).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = 0
                }
                setBackgroundResource(R.drawable.scroll_button_background)
            }
            trackContainer.addView(vScrollThumb)
            addView(trackContainer)

            // Down arrow button
            // Down arrow button
            val btnDown = FontIconView(context).apply {
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
                if (event.action == MotionEvent.ACTION_UP) {
                    val fullHeight = v.height
                    val thumbHeight = vScrollThumb.height
                    val clickY = event.y
                    // Center the thumb on the click
                    val clickTop = clickY - thumbHeight / 2
                    val trackableHeight = fullHeight - thumbHeight
                    val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                    if (isWebViewScrollEnabled()) {
                        val range = webView.getVerticalScrollRange()
                        val extent = webView.getVerticalScrollExtent()
                        if (range > extent) {
                            val targetY = percent * (range - extent)
                            webView.scrollTo(webView.scrollX, targetY.toInt())
                        }
                    } else {
                        val newProgress = (percent * 100).toInt()
                        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putInt("uiTransYProgress", newProgress).apply()
                        updateUiTranslation()
                    }
                }
                true
            }
        }

        // Add scroll bars to UI container
        leftEyeUIContainer.addView(horizontalScrollBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 30).apply {
            gravity = Gravity.BOTTOM
            leftMargin = 0
            bottomMargin = 30 // Sit on top of the 30px nav bar
        })
        leftEyeUIContainer.addView(verticalScrollBar, FrameLayout.LayoutParams(30, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
            bottomMargin = 30 // End at the nav bar
        })


        // Load and apply saved UI scale after view hierarchy is ready
        post {
            val savedScaleProgress = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
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
        
        /* Log.d("FullscreenDebug", """
            showFullScreenOverlay called:
              Entry count: $fullscreenEntryCount
              View class: ${view.javaClass.simpleName}
              View hashCode: $viewHashCode
              Same as last view: $isSameView
              View attached: ${view.isAttachedToWindow}
              View parent: ${view.parent?.javaClass?.simpleName ?: "null"}
              Container child count before: ${fullScreenOverlayContainer.childCount}
        """.trimIndent()) */
        
        // Remove from current parent if any
        if (view.parent is ViewGroup) {
            // Log.d("FullscreenDebug", "  Removing view from parent: ${(view.parent as ViewGroup).javaClass.simpleName}")
            (view.parent as ViewGroup).removeView(view)
        }

        // Clear any existing children
        if (fullScreenOverlayContainer.childCount > 0) {
            // Log.d("FullscreenDebug", "  Clearing ${fullScreenOverlayContainer.childCount} existing children from container")
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
        
        // Log.d("FullscreenDebug", "  View added. Container child count: ${fullScreenOverlayContainer.childCount}")

        previousFullScreenVisibility.clear()
        Log.d("FullscreenDebug", "Hiding ${fullScreenHiddenViews.size} UI elements")
        fullScreenHiddenViews.forEach { target ->
            val name = when(target) {
                webView -> "webView"
                leftToggleBar -> "leftToggleBar"
                leftNavigationBar -> "leftNavigationBar"
                keyboardContainer -> "keyboardContainer"
                leftSystemInfoView -> "leftSystemInfoView"
                urlEditText -> "urlEditText"
                else -> "unknown"
            }
            // Log.d("FullscreenDebug", "  Hiding $name (was ${if (target.visibility == View.VISIBLE) "VISIBLE" else "GONE/INVISIBLE"})")
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
        /* Log.d("FullscreenDebug", """
            hideFullScreenOverlay called:
              Container child count: ${fullScreenOverlayContainer.childCount}
              Container visibility: ${if (fullScreenOverlayContainer.visibility == View.VISIBLE) "VISIBLE" else "GONE/INVISIBLE"}
        """.trimIndent()) */
        
        // Get reference to the view being removed for logging
        val removedView = if (fullScreenOverlayContainer.childCount > 0) {
            fullScreenOverlayContainer.getChildAt(0)
        } else null
        
        if (removedView != null) {
            // Log.d("FullscreenDebug", "  Removing view: ${removedView.javaClass.simpleName}, hashCode: ${removedView.hashCode()}")
        }
        
        fullScreenOverlayContainer.removeAllViews()
        
        // Use INVISIBLE instead of GONE to keep the container surface attached
        // This may help prevent surface corruption on second fullscreen entry
        fullScreenOverlayContainer.visibility = View.INVISIBLE
        fullScreenOverlayContainer.elevation = 0f
        
        previousFullScreenVisibility.forEach { (target, visibility) ->
            val name = when(target) {
                webView -> "webView"
                leftToggleBar -> "leftToggleBar"
                leftNavigationBar -> "leftNavigationBar"
                keyboardContainer -> "keyboardContainer"
                leftSystemInfoView -> "leftSystemInfoView"
                urlEditText -> "urlEditText"
                else -> "unknown"
            }
            // Log.d("FullscreenDebug", "  Restoring $name to ${if (visibility == View.VISIBLE) "VISIBLE" else "GONE/INVISIBLE"}")
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
        refreshInterval = if (isAnchored && !isFullscreen) 16L else 42L
        // Log.d("PowerSaving", "Refresh rate updated: ${if (refreshInterval == 16L) "60fps" else if (refreshInterval == 33L) "30fps" else "24fps"} (Anchored=$isAnchored, Fullscreen=$isFullscreen)")
    }

    private fun hideSystemUI() {
        val activity = context as? Activity ?: run {
            Log.w("FullscreenDebug", "Cannot hide system UI - context is not an Activity")
            return
        }
        
        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // CRITICAL: Must set decorFitsSystemWindows to false first
                    @Suppress("DEPRECATION")
                    activity.window.setDecorFitsSystemWindows(false)
                    
                    activity.window.insetsController?.let { controller ->
                        controller.hide(
                            android.view.WindowInsets.Type.statusBars() 
                            or android.view.WindowInsets.Type.navigationBars()
                        )
                        controller.systemBarsBehavior = 
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        Log.d("FullscreenDebug", "System UI hidden (API 30+)")
                    } ?: Log.w("FullscreenDebug", "WindowInsetsController is null!")
                } else {
                    // Older Android versions - Use deprecated flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                    Log.d("FullscreenDebug", "System UI hidden (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error hiding system UI", e)
            }
        }
    }

    private fun showSystemUI() {
        val activity = context as? Activity ?: run {
            Log.w("FullscreenDebug", "Cannot show system UI - context is not an Activity")
            return
        }
        
        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // Restore decorFitsSystemWindows
                    @Suppress("DEPRECATION")
                    activity.window.setDecorFitsSystemWindows(true)
                    
                    activity.window.insetsController?.show(
                        android.view.WindowInsets.Type.statusBars() 
                        or android.view.WindowInsets.Type.navigationBars()
                    )
                    Log.d("FullscreenDebug", "System UI shown (API 30+)")
                } else {
                    // Older Android versions - Clear flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
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
    }

    fun unmaskScreen() {
        isScreenMasked = false
        maskOverlay.visibility = View.GONE
        // Let MainActivity handle cursor visibility restoration - cursors will be shown
        // if they were visible before masking through updateCursorPosition call
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye)
    }

    fun isScreenMasked() = isScreenMasked

    fun dispatchMaskOverlayTouch(screenX: Float, screenY: Float) {
        val location = IntArray(2)
        maskOverlay.getLocationOnScreen(location)
        val scale = uiScale
        
        // Convert to local coordinates relative to mask overlay
        val localX = screenX - location[0]
        val localY = screenY - location[1]
        
        // Log.d("MediaControls", "dispatchMaskOverlayTouch at local ($localX, $localY), scale: $scale")
        
        // Check unmask button hit (account for scale in button dimensions)
        val unmaskLocation = IntArray(2)
        btnMaskUnmask.getLocationOnScreen(unmaskLocation)
        val unmaskWidth = btnMaskUnmask.width * scale
        val unmaskHeight = btnMaskUnmask.height * scale
        if (screenX >= unmaskLocation[0] && screenX <= unmaskLocation[0] + unmaskWidth &&
            screenY >= unmaskLocation[1] && screenY <= unmaskLocation[1] + unmaskHeight) {
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
                
                if (screenX >= btnLocation[0] && screenX <= btnLocation[0] + btnWidth &&
                    screenY >= btnLocation[1] && screenY <= btnLocation[1] + btnHeight) {
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
                canvas?.let {
                    it.drawBitmap(currentBitmap, 0f, 0f, null)
                }
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
        //Log.d("LinkEditing", "DualWebViewGroup isUrlEditing toggled to: $isEditing")
    }


    fun setLinkText(text: String, newCursorPosition: Int = -1) {
        urlEditText.setText(text)

        // If no specific cursor position requested, maintain current position
        val cursorPos = if (newCursorPosition >= 0) {
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
        webView.animate()
            .setDuration(200)
            .translationY(webView.translationY)
            .start()
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
        //Log.d("LinkEditing", "isUrlEditing check, value: $isUrlEditing")
        return _isUrlEditing
    }

    fun isBookmarksExpanded(): Boolean {
        return leftBookmarksView.visibility == View.VISIBLE
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
            // Log.d("BookmarksDebug", "handleBookmarkDoubleTap() called. leftVisibility=${leftBookmarksView.visibility}")
            val handled = leftBookmarksView.handleDoubleTap()
            if (handled) {
                leftBookmarksView.logStackTrace("BookmarksDebug", "handleBookmarkDoubleTap(): double tap handled")
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
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
            }
        }
    }


    private fun setupUrlEditText(isRight: Boolean = false): EditText {
        return EditText(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                leftMargin = 40  // Single margin for left side
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
            setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply {
                color = Color.WHITE  // Set cursor color to white
            })

            // Set hardware acceleration for better cursor rendering
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Make both EditTexts share focus state
            setOnFocusChangeListener { _, hasFocus ->
                if (isRight && hasFocus) {
                    urlEditText.requestFocus()
                }
            }

            // Add text change listener to sync content
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {}

            })
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
        leftEyeUIContainer.rotation     = rotationDeg

        // Only apply same transformations to full screen overlay when it's actually visible
        // This prevents the video from being positioned incorrectly when fullscreen is activated
        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenOverlayContainer.translationX = yOffset
            fullScreenOverlayContainer.translationY = xOffset
            fullScreenOverlayContainer.rotation     = rotationDeg
        } else {
            // Keep at zero when not visible to ensure clean state
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            fullScreenOverlayContainer.rotation     = 0f
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
            post {
                startRefreshing()
            }
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

                if (currentBitmap == null || currentBitmap.isRecycled ||
                    currentBitmap.width != halfWidth || currentBitmap.height != height) {
                    setupBitmap(halfWidth, height)
                }

                // Get the current bitmap after potential setup
                val bitmapToUse = bitmap ?: return

                // Check scrollbar visibility periodically (once per second)
                // Skip if in fullscreen mode to save power
                val isFullScreen = fullScreenOverlayContainer.visibility == View.VISIBLE
                
                if (!isFullScreen && currentTime - lastScrollBarCheckTime > 1000) {
                    updateScrollBarsVisibility()
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
                                    Log.w("MirrorDebug", "Bitmap state issue - recycled: ${bitmapToUse.isRecycled}, same: ${bitmap === bitmapToUse}")
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
            //setupBitmap(webView.width, height - 48)

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
    
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshCount++
            
            // Log every 2 seconds to avoid spam
            val now = System.currentTimeMillis()
            if (now - lastRefreshLogTime > 2000) {
                // Log.d("MirrorDebug", "RefreshLoop running, count=$refreshCount, isRefreshing=$isRefreshing, webViewAttached=${webView.isAttachedToWindow}, fsOverlayVisible=${fullScreenOverlayContainer.visibility == View.VISIBLE}")
                lastRefreshLogTime = now
            }
            
            if (isRefreshing && webView.isAttachedToWindow) {
                captureLeftEyeContent()
                refreshHandler.postDelayed(this, refreshInterval)
            } else {
                Log.w("MirrorDebug", "RefreshLoop STOPPING! isRefreshing=$isRefreshing, webViewAttached=${webView.isAttachedToWindow}")
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
        val halfWidth = eyeWidth  // Each eye is 640px wide
        
        val toggleBarWidth = 40
        val navBarHeight = 30

        // Ensure toggle bar is measured correctly (40x440)
        leftToggleBar.measure(
            MeasureSpec.makeMeasureSpec(toggleBarWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(eyeHeight - navBarHeight, MeasureSpec.EXACTLY)
        )
        if (leftToggleBar.visibility != View.VISIBLE) {
            leftToggleBar.visibility = View.VISIBLE
        }

        // Ensure navigation bar is measured correctly (640x40)
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
        val keyboardHeight = if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160
        // Keyboard width is same regardless of mode (matches original keyboard size)
        val keyboardWidth = halfWidth - toggleBarWidth

        // Position the WebView differently based on scroll mode
        // Shrink the WebView when keyboard is visible so content isn't blocked
        val isKeyboardVisible = keyboardContainer.visibility == View.VISIBLE
        
        if (isInScrollMode) {
            val keyboardLimit = if (isKeyboardVisible) {
                eyeHeight - keyboardHeight  // Shrink to fit above keyboard
            } else {
                480
            }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webView.measuredHeight
            
            webView.layout(
                0,  // No left margin in scroll mode
                0,
                0 + webView.measuredWidth,  // Full width minus margins
                minOf(keyboardLimit, measuredBottom)
            )
        } else {
            val navBarTop = eyeHeight - navBarHeight // 480 - 30 = 450
            
            val keyboardLimit = if (isKeyboardVisible) {
                minOf(navBarTop, eyeHeight - keyboardHeight)  // Shrink to fit above keyboard
            } else {
                navBarTop // Default bottom for 30px nav bar
            }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webView.measuredHeight
            
            webView.layout(
                40,  // Account for toggle bar
                0,
                40 + webView.measuredWidth,  // Standard width + toggle bar offset
                minOf(keyboardLimit, measuredBottom)
            )
        }

        // Calculate available content height based on keyboard visibility
        val contentHeight = if (keyboardContainer.visibility == View.VISIBLE) {
            eyeHeight - keyboardHeight
        } else {
            eyeHeight - navBarHeight
        }

        // Layout the clip parent - hardcoded 640x480
        leftEyeClipParent.layout(
            0,  // After toggle bar
            0,
            eyeWidth,  // Fixed width for left eye
            eyeHeight
        )

        fullScreenOverlayContainer.layout(
            0,  // Relative to leftEyeClipParent
            0,
            halfWidth,  // 640px width (matches clip parent)
            eyeHeight
        )


        // Position SurfaceView exactly like WebView but offset horizontally for right eye
        rightEyeView.layout(
            eyeWidth,
            0,
            eyeWidth * 2,
            eyeHeight
        )



        // Layout toggle bar - height is eyeHeight minus navBarHeight (480 - 40 = 440)
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
        keyboardContainer.layout(toggleBarWidth, keyboardY, toggleBarWidth + keyboardWidth, eyeHeight)

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
            val kbLeft = if (isInScrollMode) {
                (halfWidth - keyboardWidth) / 2  // Center in left half
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
                val bookmarksY = if (isEditingAnywhere) {
                    40  // Below URL edit field area / top of screen
                } else {
                    keyboardY - bookmarksHeight
                }
                
                // Constrain bottom to keyboardY to avoid overlapping with keyboard
                val bookmarksBottom = if (isEditingAnywhere) {
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
                val editFieldRight = keyboardContainer.right.takeIf { it > editFieldLeft }
                    ?: (editFieldLeft + keyboardWidth)

                // Position left edit field only
                urlEditText.apply {
                    layout(
                        editFieldLeft,
                        0,
                        editFieldRight,
                        editFieldHeight
                    )
                    translationY = (keyboardY - editFieldHeight).toFloat()
                    visibility = View.VISIBLE
                    elevation = 1001f
                    bringToFront()
                }

            }

            // Ensure keyboard containers are on top but below edit fields
            keyboardContainer.elevation = 1000f
            keyboardContainer.bringToFront()
        } else {
            //Log.d("EditFieldDebug", "Skipping edit field positioning - conditions not met")

            // Hide keyboard containers
            keyboardContainer.layout(toggleBarWidth, eyeHeight, toggleBarWidth + keyboardWidth, eyeHeight + keyboardHeight)

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
            val infoBarY = eyeHeight - navBarHeight - infoBarHeight  // Position above nav bar

            // First measure the info views to get their width
            leftSystemInfoView.measure(
                MeasureSpec.makeMeasureSpec(320, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(infoBarHeight, MeasureSpec.EXACTLY)
            )

            val infoBarWidth = leftSystemInfoView.measuredWidth
            val leftX = (halfWidth - infoBarWidth) / 2 + toggleBarWidth  // Center in left half, account for toggle bar

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
            val availableHeight = if (keyboardContainer.visibility == View.VISIBLE) {
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
            val btnRight = halfWidth - 8  // 8px margin from right
            val btnBottom = height - 8    // 8px margin from bottom
            btnShowNavBars.layout(
                btnRight - btnSize,
                btnBottom - btnSize,
                btnRight,
                btnBottom
            )
            btnShowNavBars.bringToFront()
        }

        // Layout scroll bars for non-anchored mode
        // Eye button size is 40px with 8px margin from bottom/right, so reserve 48px for it
        val eyeButtonSpace = if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) 48 else 0
        
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val hScrollHeight = 20
            val hScrollY = if (isInScrollMode) eyeHeight - hScrollHeight else eyeHeight - navBarHeight - hScrollHeight  // At bottom in scroll mode

            val scrollLeft = if (isInScrollMode) 0 else toggleBarWidth
            var scrollWidth = if (isInScrollMode) halfWidth - eyeButtonSpace else halfWidth - toggleBarWidth

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
            val vScrollRight = halfWidth  // Align to right edge
            val vScrollTop = 0  // Start from top
            
            // In scroll mode, stop above eye button. Normal mode, stop at nav bar.
            val vScrollBottom = if (isInScrollMode) eyeHeight - eyeButtonSpace else eyeHeight - navBarHeight
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
        System.gc()  // Request garbage collection
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
            listenerField.set(editor, object : Any() {
                fun onSelectionChanged(selStart: Int, selEnd: Int) {
                    listener(selStart, selEnd)
                }
            })
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
        // Log.d("KeyboardDebug", "setKeyboard called with keyboard: ${originalKeyboard.hashCode()}")

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
        keyboardContainer.addView(originalKeyboard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
        })

        // Explicitly set visibility based on keyboard's current state
        val visibility = if (originalKeyboard.visibility == View.VISIBLE) View.VISIBLE else View.GONE
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

        /* Log.d(
            "TouchDebug",
            "Anchored cursor mapped to keyboard local=($localX, $localY) kbSize=(${kbView.width}, ${kbView.height})"
        ) */

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
                x >= view.left && x <= view.right &&
                y >= view.top && y <= view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Log.d("GestureDebug", "DualWebViewGroup onInterceptTouchEvent: ${ev.action}")

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            // Allow interactions with menus that are on top
            if (::leftBookmarksView.isInitialized && isTouchOnView(leftBookmarksView, ev.x, ev.y)) {
                return false
            }

            fullScreenTapDetector.onTouchEvent(ev)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to WebView
        if (isAnchored && !isInScrollMode) {
            var isOverTarget = false
            val (cursorX, cursorY) = getCursorInContainerCoords()

            // Check Keyboard
            if (keyboardContainer.visibility == View.VISIBLE) {
                val localCoords = computeAnchoredKeyboardCoordinates()
                if (localCoords != null) {
                    val (localX, localY) = localCoords
                    if (localX >= 0 && localX <= keyboardContainer.width &&
                        localY >= 0 && localY <= keyboardContainer.height) {
                        isOverTarget = true
                        anchoredTarget = 1
                    }
                }
            }

            // Check Bookmarks (if not already over keyboard)
            if (!isOverTarget && ::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                if (cursorX >= leftBookmarksView.left && cursorX <= leftBookmarksView.right &&
                    cursorY >= leftBookmarksView.top && cursorY <= leftBookmarksView.bottom) {
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
                        // Log.d("TouchDebug", "Intercepting anchored ACTION_DOWN target=$anchoredTarget")
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
            !isAnchored) {
            return true
        }

        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val halfWidth = widthSize / 2
        val navBarHeight = 30
        val toggleBarWidth = 40
        val keyboardWidth = halfWidth - toggleBarWidth

        // Measure keyboard container first to get its actual height
        keyboardContainer.measure(
            MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val keyboardHeight = if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160

        val contentHeight = if (keyboardContainer.visibility == View.VISIBLE) {
            heightSize - keyboardHeight
        } else {
            heightSize - navBarHeight
        }

        // Measure WebView with different dimensions based on scroll mode
        // FIX: Respect the LayoutParams set by updateScrollBarsVisibility
        val lp = webView.layoutParams
        
        if (isInScrollMode) {
            val targetWidth = if (lp != null && lp.width > 0) lp.width else 640
            val targetHeight = if (lp != null && lp.height > 0) lp.height else 480
            
            webView.measure(
                MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        } else {
             // Normal Mode
             // Use layout params if available (set by updateScrollBarsVisibility)
             // Default fallback: 640 - 40 = 600 width, 440 height
             val targetWidth = if (lp != null && lp.width > 0) lp.width else 600
             
             // For height in normal mode, we used MATCH_PARENT in updateScrollBarsVisibility usually, 
             // but sometimes explicit. If MATCH_PARENT (-1), we use the calculated contentHeight.
             val targetHeight = if (lp != null && lp.height > 0) lp.height else if (contentHeight > 0) contentHeight else 440

             webView.measure(
                MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        }

        // Rest of the measuring code remains the same
        rightEyeView.measure(
            MeasureSpec.makeMeasureSpec(halfWidth - 40, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        )

        leftNavigationBar.measure(
            MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // keyboardContainer is already measured above, but we can measure it again with EXACTLY if we want to enforce constraints,
        // but UNSPECIFIED allowed it to size itself. Let's stick to the measurement we did.

        fullScreenOverlayContainer.measure(
            MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        maskOverlay.measure(
            MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
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

        // Skip anchored gesture handling when in scroll mode - touches should go directly to WebView
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
                             if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
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
                                    if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                                        // Log.d("TouchDebug", "Dispatching anchored tap to bookmarks")
                                        leftBookmarksView.handleAnchoredTap(cursorX - leftBookmarksView.left, cursorY - leftBookmarksView.top)
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
                /* android.util.Log.d("TouchDebug",
                    "DWG UP: wasTap=$wasTap dur=${dur}ms travel=(${travelX},${travelY}) kbVisible=$kbVisible isAnchored=$isAnchored") */
                
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
                    !isAnchored && wasTap) {
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
            !isAnchored) {
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
        if (!::leftBookmarksView.isInitialized || leftBookmarksView.visibility != View.VISIBLE) return false
        
        val bookmarksLocation = IntArray(2)
        leftBookmarksView.getLocationOnScreen(bookmarksLocation)
        
        return screenX >= bookmarksLocation[0] && screenX <= bookmarksLocation[0] + leftBookmarksView.width &&
               screenY >= bookmarksLocation[1] && screenY <= bookmarksLocation[1] + leftBookmarksView.height
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

        return localX >= keyboardContainer.left && localX <= keyboardContainer.right &&
               localY >= keyboardContainer.top && localY <= keyboardContainer.bottom
    }

    fun getKeyboardSize(): Pair<Int, Int> {
        return Pair(
            keyboardContainer.width,
            keyboardContainer.height
        )
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

        // Log.d("KeyboardDebug", "Keyboard tap: screen($screenX, $screenY) -> local($finalX, $finalY)")
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
        // Log.d("ModeToggle", "Updating browsing mode to: ${if (isDesktop) "desktop" else "mobile"}")

        isDesktopMode = isDesktop

        // Step 1: Update WebView settings (user agent)
        // If on Netflix, we preserve the current UA (which should be the system default) to prevent DRM errors.
        val isNetflix = webView.url?.contains("netflix.com") == true

        if (!isNetflix) {
            webView.settings.apply {
                userAgentString = if (isDesktop) {
                    desktopUserAgent
                } else {
                    mobileUserAgent
                }
                @Suppress("DEPRECATION")
                defaultZoom = WebSettings.ZoomDensity.MEDIUM
                loadWithOverviewMode = true
                useWideViewPort = true
            }
        }

        // Step 2: Update viewport using JavaScript without forcing a complete reload
        val viewportContent = if (isDesktop) "width=1280, initial-scale=0.8" else "width=600, initial-scale=1.0, maximum-scale=1.0"
        
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
            """, null
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
            leftButton?.text = context.getString(if (isDesktop) R.string.fa_desktop else R.string.fa_mobile_screen)
        }
    }

    private fun loadARDashboard(){
        webView.loadUrl("file:///android_asset/AR_Dashboard_Landscape_Sidebar.html")
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
        editText.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }

        // Block the insertion/caret handle action mode (API 23+)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            editText.customInsertionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
                override fun onDestroyActionMode(mode: android.view.ActionMode) {}
            }
        }

        // Optional: consume double-tap/long-press gestures that can trigger selection on some OEM skins
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
            /* Log.d("DualWebViewGroup", """
            Edit field state:
            Text: $text
            Visibility: $visibility
            Parent: ${parent?.javaClass?.simpleName}
            Position: ($x, $y)
            Width: $width, Height: $height
        """.trimIndent()) */
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
        //Log.d("buttonFeedbackDebug", "button feedback shown")
        Handler(Looper.getMainLooper()).postDelayed({
            button.isPressed = false
        }, buttonFeedbackDuration)
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
        
        // Helper function to check if cursor is over a button or view
        fun isOver(button: View?): Boolean {
            if (button == null || button.visibility != View.VISIBLE) return false
            
            // Use getGlobalVisibleRect for accurate screen bounds detection
            val rect = android.graphics.Rect()
            if (!button.getGlobalVisibleRect(rect)) return false
            
            val isOver = screenX >= rect.left && screenX <= rect.right &&
                   screenY >= rect.top && screenY <= rect.bottom
            
            return isOver
        }
        
        // Check bottom navigation bar buttons ONLY if nav bar is visible
        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.forEach { (name, navButton) ->
                if (isOver(navButton.left)) {
                    navButton.isHovered = true
                    navButton.left.isHovered = true
                    navButton.right.isHovered = true
                    customKeyboard?.clearHover() // Clear keyboard hover
                    return  // Found the hovered button, stop checking
                }
            }
        }
        
        // Check left toggle bar buttons
        val toggleBarButtons = listOf(
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
            if (isOver(button)) {
                button?.isHovered = true
                setHoverFlag()
                clearNavigationButtonStates()
                // Log.d("HoverDebug", "Hovering over toggle button: $name")
                customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                return  // Found the hovered button, stop checking
            }
        }
        
        // Check settings window elements if visible
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements = listOf(
                    R.id.volumeSeekBar,
                    R.id.brightnessSeekBar,
                    R.id.smoothnessSeekBar,
                    R.id.screenSizeSeekBar,
                    R.id.btnResetScreenSize,
                    R.id.fontSizeSeekBar,
                    R.id.fontSizeSeekBar,
                    R.id.colorWheelView,
                    R.id.horizontalPosSeekBar,
                    R.id.horizontalPosSeekBar,
                    R.id.verticalPosSeekBar,
                    R.id.btnResetPosition,
                    R.id.btnHelp,
                    R.id.btnCloseSettings,
                    R.id.btnGroqApiKey
                )
                for (id in settingsElements) {
                    val view = menu.findViewById<View>(id)
                    if (isOver(view)) {
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
                        if (isOver(button)) {
                            button.isHovered = true
                            // Log.d("HoverDebug", "Hovering over dialog button: $i")
                            customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                            return
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
            if (screenX >= location[0] && screenX <= location[0] + horizontalScrollBar.width &&
                screenY >= location[1] && screenY <= location[1] + horizontalScrollBar.height) {
                 
                 // Check children (arrows and track)
                 for (i in 0 until horizontalScrollBar.childCount) {
                     val child = horizontalScrollBar.getChildAt(i)
                     if (isOver(child)) {
                         child.isHovered = true
                         child.isActivated = true
                         
                         // If we are over the track container, check the thumb specifically
                         if (child == horizontalScrollBar.getChildAt(1)) {
                             if (isOver(hScrollThumb)) {
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
             if (screenX >= location[0] && screenX <= location[0] + verticalScrollBar.width &&
                screenY >= location[1] && screenY <= location[1] + verticalScrollBar.height) {
                 
                 // Check children (arrows and track)
                 for (i in 0 until verticalScrollBar.childCount) {
                     val child = verticalScrollBar.getChildAt(i)
                     if (isOver(child)) {
                         child.isHovered = true
                         child.isActivated = true
                         
                         // If we are over the track container, check the thumb specifically
                         if (child == verticalScrollBar.getChildAt(1)) {
                             if (isOver(vScrollThumb)) {
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
                // Pass raw screenX/screenY and let CustomKeyboardView check against actual screen positions
                kbView.updateHoverScreen(screenX, screenY, uiScale)
                
                // We don't return here because updateHoverScreen will internally check if a key was hit.
                // However, we should check if a key WAS hit to know if we should "consume" the hover event.
                // For now, if the keyboard is visible, we let it process.
                return // Stop checking after keyboard processing
            }
        }
    }

    // Helper function to clear all hover states
    private fun clearAllHoverStates() {
        // Clear toggle button states
        isHoveringModeToggle    = false
        isHoveringDashboardToggle = false
        isHoveringBookmarksMenu = false
        isHoveringZoomIn        = false
        isHoveringZoomOut       = false

        isHoveringMaskToggle    = false
        isHoveringAnchorToggle  = false

        // Clear visual hover states
        listOf(
            R.id.btnModeToggle,
            R.id.btnYouTube,
            R.id.btnBookmarks,
            R.id.btnZoomIn,
            R.id.btnZoomOut,

            R.id.btnMask,
            R.id.btnAnchor

        ).forEach { id ->
            leftToggleBar.findViewById<View>(id)?.isHovered = false
        }
        
        // Clear settings hover states
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements = listOf(
                    R.id.volumeSeekBar,
                    R.id.brightnessSeekBar,
                    R.id.smoothnessSeekBar,
                    R.id.screenSizeSeekBar,
                    R.id.btnResetScreenSize,
                    R.id.fontSizeSeekBar,
                    R.id.fontSizeSeekBar,
                    R.id.colorWheelView,
                    R.id.horizontalPosSeekBar,
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
            if (screenX >= location[0] && screenX <= location[0] + horizontalScrollBar.width &&
                screenY >= location[1] && screenY <= location[1] + horizontalScrollBar.height) {
                return true
            }
        }
        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] && screenX <= location[0] + verticalScrollBar.width &&
                screenY >= location[1] && screenY <= location[1] + verticalScrollBar.height) {
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
                
                if (screenX >= childLocation[0] && screenX <= childLocation[0] + child.width &&
                    screenY >= childLocation[1] && screenY <= childLocation[1] + child.height) {
                    
                    if (child.hasOnClickListeners()) {
                         child.performClick()
                    } else {
                        // For track/thumb, we need to simulate touch events
                        // The track listener reacts to ACTION_UP
                        val localX = screenX - childLocation[0]
                        val localY = screenY - childLocation[1]
                        
                        val downEvent = MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_DOWN,
                            localX, localY, 0
                        )
                        child.dispatchTouchEvent(downEvent)
                        downEvent.recycle()
                        
                        val upEvent = MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            localX, localY, 0
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
            if (screenX >= location[0] && screenX <= location[0] + horizontalScrollBar.width &&
                screenY >= location[1] && screenY <= location[1] + horizontalScrollBar.height) {
                dispatchToContainer(horizontalScrollBar)
                return
            }
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] && screenX <= location[0] + verticalScrollBar.width &&
                screenY >= location[1] && screenY <= location[1] + verticalScrollBar.height) {
                dispatchToContainer(verticalScrollBar)
                return
            }
        }
    }


    fun isNavBarVisible(): Boolean {
        // Check both visibility AND scroll mode - in scroll mode, bars are hidden even during fade animation
        return !isInScrollMode && leftNavigationBar.visibility == View.VISIBLE
    }

    fun isPointInRestoreButton(x: Float, y: Float): Boolean {
        if (btnShowNavBars.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        btnShowNavBars.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + (btnShowNavBars.width * uiScale) &&
                y >= loc[1] && y <= loc[1] + (btnShowNavBars.height * uiScale)
    }

    fun performRestoreButtonClick() {
        if (btnShowNavBars.visibility == View.VISIBLE) {
            btnShowNavBars.performClick()
        }
    }

    fun handleNavigationClick(x: Float, y: Float) {
        // Ensure nav bar is visible and not in scroll mode before handling click
        if (isInScrollMode || leftNavigationBar.visibility != View.VISIBLE) return


        val height = height
        val halfWidth = width / 2
        val localX = x % halfWidth
        val toggleBarWidth = 40

        /* Log.d("TouchDebug", """
        handleNavigationClick:
        Coordinates: ($x, $y)
        Toggle bar width: $toggleBarWidth
        Is in toggle bar area: ${x < toggleBarWidth}
        Button height: $buttonHeight
        Current button row: ${(y / buttonHeight).toInt()}
    """.trimIndent()) */

        // First check if settings menu is visible and if click is within its bounds
        if (isSettingsVisible && settingsMenu != null) {
            val menuLocation = IntArray(2)
            settingsMenu?.getLocationOnScreen(menuLocation)
            val menuWidth = settingsMenu?.width ?: 0
            val menuHeight = settingsMenu?.height ?: 0
            // Log.d("SettingsDebug", """x: $x, y: $y, menuLocation: ${menuLocation[0]}, ${menuLocation[1]},menuWidth: $menuWidth, menuHeight: $menuHeight""")
            if (x <= menuWidth &&
                y <= menuHeight) {
                // Log.d("SettingsDebug", "Dispatching the event to settings")

                // Click is within settings menu bounds - dispatch touch event to settings menu
                dispatchSettingsTouchEvent(lastCursorX, lastCursorY)

                return  // Important: return early to prevent click from reaching toggle bar
            }

        }


        // Handle toggle bar clicks
        // Handle toggle bar clicks using hover state
        val toggleBarButtons = listOf(
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
            if (button != null && button.isHovered) {
                handleLeftMenuAction(buttonId)
                return
            }
        }

        if (y >= height - 40) {
            keyboardListener?.onHideKeyboard()
            // Log.d("AnchoredTouchDebug","handling navigation click")
                    navButtons.entries.find { it.value.isHovered }?.let { (key, button) ->
                        showButtonClickFeedback(button.left)
                        showButtonClickFeedback(button.right)
                        if (key == "hide") {
                            setScrollMode(true)
                        } else {
                            navigationListener?.let { listener ->
                                when (key) {
                                    "back"     -> listener.onNavigationBackPressed()
                                    "forward"  -> listener.onNavigationForwardPressed()
                                    "home"     -> listener.onHomePressed()
                                    "link"     -> listener.onHyperlinkPressed()
                                    "settings" -> listener.onSettingsPressed()
                                    "refresh"  -> listener.onRefreshPressed()
                                    "quit"     -> listener.onQuitPressed()
                                }
                            }
                        }
            }
        }


        if (localX < 40) {

            when {
                isHoveringZoomOut -> handleZoomButtonClick("out")
                isHoveringZoomIn -> handleZoomButtonClick("in")
            }
        }


    }





    fun resetPositions() {
        // Reset translations
        _translationX = 0f
        _translationY = 0f
        _rotationZ    = 0f

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

        postDelayed({
            startRefreshing()
            requestLayout()
            invalidate()
        },100)

    }

    private fun handleZoomButtonClick(direction: String) {
        val zoomFactor = if (direction == "in") 1.1f else 0.9f
        currentWebZoom *= zoomFactor
        
        // Save preference
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("webZoomLevel", currentWebZoom)
            .apply()

        webView.evaluateJavascript("""
        (function() {
            document.body.style.zoom = "$currentWebZoom";
        })();
    """, null)
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
            leftBookmarksView.left, leftBookmarksView.top,
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
        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text = context.getString(R.string.fa_anchor)
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

        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text = context.getString(R.string.fa_anchor_circle_xmark)
        webView.visibility = View.VISIBLE
        rightEyeView.visibility = View.VISIBLE

        updateUiTranslation()

        post {
            startRefreshing()
            invalidate()
        }
    }

    fun setBookmarksView(bookmarksView: BookmarksView) {
        this.leftBookmarksView = bookmarksView.apply {
            val params = MarginLayoutParams(420, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 40  // After toggle bar
                topMargin = 10   // Move higher up
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
        //Log.d("Fling Debug", "Fling handled by DualWebViewGroup")

        // First check if bookmarks are visible (Non-Anchored Mode legacy behavior)
        if (leftBookmarksView.visibility == View.VISIBLE && !isAnchored) {
            //Log.d("DualWebViewGroup", "Delegating fling to bookmarks: velocity=$velocityX")

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
        webView.evaluateJavascript("""
            (function() {
                window.scrollBy({
                    top: ${(-slowedVelocity).toInt()},
                    behavior: 'smooth'
                });
            })();
        """, null)

        // Provide a native scroll backup only if JS execution fails or is slow?
        // Actually, since we want to avoid double-scroll bouncing, relying on JS scrollBy is safer with 'smooth' behavior.
        // However, if we remove this, we rely solely on JS.
        // Let's remove the unconditional native backup to prevent fighting/overshoot.
    }

    private fun initializeToggleButtons() {
        Log.d("ViewDebug", """
    Toggle bar parent: ${leftToggleBar.parent?.javaClass?.simpleName}
    Toggle bar children: ${(leftToggleBar as? ViewGroup)?.childCount ?: "Not a ViewGroup"}
    UI Container children count: ${leftEyeUIContainer.childCount}
    UI Container children:
    ${(0 until leftEyeUIContainer.childCount).joinToString("\n") { index ->
            val child = leftEyeUIContainer.getChildAt(index)
            "Child $index: ${child.javaClass.simpleName} (${child.hashCode()})"+
                    "\n    Location: (${child.x}, ${child.y})"+
                    "\n    Size: ${child.width}x${child.height}"+
                    "\n    Translation: (${child.translationX}, ${child.translationY})"
        }}
""".trimIndent())

        // Get references to all buttons
        val leftModeToggleButton   = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
        val leftDashboardButton    = leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube)
        val leftBookmarksButton    = leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks)
        val leftZoomInButton       = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomIn)
        val leftZoomOutButton      = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomOut)
        val leftMaskButton         = leftToggleBar.findViewById<FontIconView>(R.id.btnMask)
        val leftAnchorButton       = leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)


        // Calculate positioning constants
        val buttonHeight = verticalBarSize / (nButtons)
        val smallButtonWidth = 20 // Size for split buttons (zoom) - Reduced to match 40 width
        val buttonWidth      = 2 * smallButtonWidth
        //val spacing = 8 // Standard spacing between buttons

// Calculate Y positions based on consistent spacing
        val modeToggleY      = 0
        val dashboardY       = buttonHeight
        val bookmarksY       = buttonHeight * 2
        val zoomOutY         = buttonHeight * 3
        val zoomInY          = buttonHeight * 4
        val maskY            = buttonHeight * 5
        val anchorY          = buttonHeight * 6

// Configure main feature buttons (top section)
        listOf(
            leftModeToggleButton to modeToggleY,
            leftDashboardButton to dashboardY,
            leftBookmarksButton to bookmarksY
        ).forEach { (button, yPosition) ->
            try {
                button?.apply {
                    val params = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
                    layoutParams = params
                    visibility = View.VISIBLE
                    background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                    // Icon already set via XML text attribute
                    setPadding(8, 8, 8, 8)
                    elevation = 4f
                    alpha = 1f
                    isEnabled = true
                    layout(left, yPosition, left + buttonWidth, yPosition + buttonHeight)
                    setOnTouchListener { v, event ->
                        val location = IntArray(2)
                        v.getLocationOnScreen(location)
                        val parentLocation = IntArray(2)
                        leftToggleBar.getLocationOnScreen(parentLocation)

                        /* Log.d("TouchDebug", """
                    Button Touch (${v.id}):
                    Raw touch: (${event.rawX}, ${event.rawY})
                    Button screen location: (${location[0]}, ${location[1]})
                    Button size: ${v.width}x${v.height}
                    Toggle bar screen location: (${parentLocation[0]}, ${parentLocation[1]})
                    Button relative to parent: (${v.x}, ${v.y})
                    Y Position in setup: $yPosition
                    Layout bounds: ($left, $top, $right, $bottom)
                """.trimIndent()) */

                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("ToggleButton", "Error configuring button", e)
            }
        }

        // Configure zoom buttons vertically
        leftZoomOutButton?.apply {
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            // Icon already set via XML text attribute
            setPadding(6, 6, 6, 6)
            elevation = 4f
            alpha = 1f
            layout(left, zoomOutY, left + buttonWidth, zoomOutY + buttonHeight)
        }

        leftZoomInButton?.apply {
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            // Icon already set via XML text attribute
            setPadding(6, 6, 6, 6)
            elevation = 4f
            alpha = 1f
            layout(left, zoomInY, left + buttonWidth, zoomInY + buttonHeight)
        }


        leftMaskButton?.apply {
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            // Icon already set via XML text attribute
            setPadding(8, 8, 8, 8)
            elevation = 4f
            alpha = 1f
            layout(left, maskY, left + buttonWidth, maskY + buttonHeight)
        }

        leftAnchorButton?.apply {
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            // Check anchored state and set appropriate icon
            text = context.getString(if (isAnchored) R.string.fa_anchor else R.string.fa_anchor_circle_xmark)
            setPadding(8, 8, 8, 8)
            elevation = 4f
            alpha = 1f
            layout(left, anchorY, left + buttonWidth, anchorY + buttonHeight)
        }

        mapOf(
            leftModeToggleButton to R.id.btnModeToggle,
            leftDashboardButton to R.id.btnYouTube,
            leftBookmarksButton to R.id.btnBookmarks,
            leftZoomOutButton to R.id.btnZoomOut,
            leftZoomInButton to R.id.btnZoomIn,
            leftMaskButton to R.id.btnMask,
            leftAnchorButton to R.id.btnAnchor
        ).forEach { (button, id) ->
            button?.setOnClickListener { handleLeftMenuAction(id) }
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
            settingsMenu = LayoutInflater.from(context)
                .inflate(R.layout.settings_layout, null, false)
                .apply {
                    isClickable = false
                    isFocusable = false
                    elevation = 1001f  // Even higher elevation than scrim

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



            val layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }

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
                val currentBrightness = (context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f
                brightnessSeekBar?.progress = (currentBrightness * 100).toInt()

                // Initialize smoothness seekbar from saved preference
                val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
                val savedSmoothness = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    .getInt("anchorSmoothness", 80)
                smoothnessSeekBar?.progress = savedSmoothness

                // Initialize screen size seekbar (just update the UI, don't apply scale)
                val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
                val savedScaleProgress = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    .getInt("uiScaleProgress", 100)
                screenSizeSeekBar?.progress = savedScaleProgress
                
                // Calculate scale for position slider visibility check only
                val currentScale = 0.25f + (savedScaleProgress / 100f) * 0.75f

                // Initialize position sliders
                val showPosSliders = !isAnchored && currentScale < 0.99f
                val visibility = if (showPosSliders) View.VISIBLE else View.GONE

                menu.findViewById<View>(R.id.settingsPositionLayout)?.visibility = visibility

                menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)?.apply {
                    progress = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getInt("uiTransXProgress", 50)
                }

                menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)?.apply {
                    progress = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getInt("uiTransYProgress", 50)
                }

                // Initialize font size seekbar (50% = 50, 100% = 100, 200% = 200, slider is 0-150 mapping to 50-200%)
                val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
                val savedFontSize = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    .getInt("webFontSize", 50) // Default 50 = 100%
                fontSizeSeekBar?.progress = savedFontSize
                
                // Initialize color buttons with visual background indicators
                // Initialize color wheel with saved color
                menu.findViewById<ColorWheelView>(R.id.colorWheelView)?.apply {
                    val savedTextColor = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getString("webTextColor", null)
                    try {
                        setColor(Color.parseColor(savedTextColor))
                    } catch (e: Exception) {
                        setColor(Color.WHITE)
                    }
                }
                
                // Apply saved font settings
                val savedTextColor = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    .getString("webTextColor", null)
                applyWebFontSettings(savedFontSize, savedTextColor)
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
        return Pair(
            settingsMenu?.width ?: 0,
            settingsMenu?.height ?: 0
        )
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
            // Get screen locations
            val volumeLocation = IntArray(2)
            val brightnessLocation = IntArray(2)
            val smoothnessLocation = IntArray(2)
            val screenSizeLocation = IntArray(2)
            val horzPosLocation = IntArray(2)
            val vertPosLocation = IntArray(2)
            val closeLocation = IntArray(2)
            val helpLocation = IntArray(2)
            val resetLocation = IntArray(2)
            val resetScreenSizeLocation = IntArray(2)
            val fontSizeLocation = IntArray(2)
            val colorWheelLocation = IntArray(2)
            val resetTextColorLocation = IntArray(2)
            val groqKeyLocation = IntArray(2)

            val menuLocation = IntArray(2)
            menu.getLocationOnScreen(menuLocation)

            volumeSeekBar?.getLocationOnScreen(volumeLocation)
            brightnessSeekBar?.getLocationOnScreen(brightnessLocation)
            smoothnessSeekBar?.getLocationOnScreen(smoothnessLocation)
            screenSizeSeekBar?.getLocationOnScreen(screenSizeLocation)
            horizontalPosSeekBar?.getLocationOnScreen(horzPosLocation)
            verticalPosSeekBar?.getLocationOnScreen(vertPosLocation)
            closeButton?.getLocationOnScreen(closeLocation)
            helpButton?.getLocationOnScreen(helpLocation)
            resetButton?.getLocationOnScreen(resetLocation)
            resetScreenSizeButton?.getLocationOnScreen(resetScreenSizeLocation)
            fontSizeSeekBar?.getLocationOnScreen(fontSizeLocation)
            colorWheelView?.getLocationOnScreen(colorWheelLocation)
            resetTextColorButton?.getLocationOnScreen(resetTextColorLocation)
            groqKeyButton?.getLocationOnScreen(groqKeyLocation)

            val slop = 5 // Reduced from 40 to 5 to prevent overlapping touch targets

            if (x >= menuLocation[0] - slop && x <= menuLocation[0] + (menu.width * uiScale) + slop &&
                y >= menuLocation[1] - slop && y <= menuLocation[1] + (menu.height * uiScale) + slop) {
                // Check if click is on volume seekbar
                if (volumeSeekBar != null &&
                    x >= volumeLocation[0] - slop && x <= volumeLocation[0] + (volumeSeekBar.width * uiScale) + slop &&
                    y >= volumeLocation[1] - slop && y <= volumeLocation[1] + (volumeSeekBar.height * uiScale) + slop) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - volumeLocation[0]) / uiScale
                    val percentage = relativeX.coerceIn(0f, volumeSeekBar.width.toFloat()) / volumeSeekBar.width
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
                    Handler(Looper.getMainLooper()).postDelayed({
                        volumeSeekBar.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on brightness seekbar
                if (brightnessSeekBar != null &&
                    x >= brightnessLocation[0] - slop && x <= brightnessLocation[0] + (brightnessSeekBar.width * uiScale) + slop &&
                    y >= brightnessLocation[1] - slop && y <= brightnessLocation[1] + (brightnessSeekBar.height * uiScale) + slop) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - brightnessLocation[0]) / uiScale
                    val percentage = relativeX.coerceIn(0f, brightnessSeekBar.width.toFloat()) / brightnessSeekBar.width
                    val newProgress = (percentage * brightnessSeekBar.max).toInt()

                    // Update brightness
                    brightnessSeekBar.progress = newProgress
                    (context as? Activity)?.window?.attributes = (context as Activity).window.attributes.apply {
                        screenBrightness = newProgress / 100f
                    }

                    // Visual feedback
                    brightnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        brightnessSeekBar.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on smoothness seekbar
                if (smoothnessSeekBar != null &&
                    x >= smoothnessLocation[0] - slop && x <= smoothnessLocation[0] + (smoothnessSeekBar.width * uiScale) + slop &&
                    y >= smoothnessLocation[1] - slop && y <= smoothnessLocation[1] + (smoothnessSeekBar.height * uiScale) + slop) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - smoothnessLocation[0]) / uiScale
                    val percentage = relativeX.coerceIn(0f, smoothnessSeekBar.width.toFloat()) / smoothnessSeekBar.width
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
                    Handler(Looper.getMainLooper()).postDelayed({
                        smoothnessSeekBar.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on screen size seekbar
                if (screenSizeSeekBar != null &&
                    x >= screenSizeLocation[0] - slop && x <= screenSizeLocation[0] + (screenSizeSeekBar.width * uiScale) + slop &&
                    y >= screenSizeLocation[1] - slop && y <= screenSizeLocation[1] + (screenSizeSeekBar.height * uiScale) + slop) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - screenSizeLocation[0]) / uiScale
                    val percentage = relativeX.coerceIn(0f, screenSizeSeekBar.width.toFloat()) / screenSizeSeekBar.width
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
                    Handler(Looper.getMainLooper()).postDelayed({
                        screenSizeSeekBar.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on Groq API Key button
                if (groqKeyButton != null &&
                    x >= groqKeyLocation[0] - slop && x <= groqKeyLocation[0] + (groqKeyButton.width * uiScale) + slop &&
                    y >= groqKeyLocation[1] - slop && y <= groqKeyLocation[1] + (groqKeyButton.height * uiScale) + slop) {

                    // Visual feedback
                    groqKeyButton.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        groqKeyButton.isPressed = false
                        // Show dialog
                        (context as? MainActivity)?.showGroqKeyDialog()
                    }, 100)
                    return
                }

                // Check if click is on reset screen size button
                if (resetScreenSizeButton != null &&
                    x >= resetScreenSizeLocation[0] - slop && x <= resetScreenSizeLocation[0] + (resetScreenSizeButton.width * uiScale) + slop &&
                    y >= resetScreenSizeLocation[1] - slop && y <= resetScreenSizeLocation[1] + (resetScreenSizeButton.height * uiScale) + slop) {

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
                    Handler(Looper.getMainLooper()).postDelayed({
                        resetScreenSizeButton.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on horizontal pos seekbar
                if (horizontalPosSeekBar != null && horizontalPosSeekBar.visibility == View.VISIBLE &&
                    x >= horzPosLocation[0] - slop && x <= horzPosLocation[0] + (horizontalPosSeekBar.width * uiScale) + slop &&
                    y >= horzPosLocation[1] - slop && y <= horzPosLocation[1] + (horizontalPosSeekBar.height * uiScale) + slop) {

                    val relativeX = (x - horzPosLocation[0]) / uiScale
                    val percentage = relativeX.coerceIn(0f, horizontalPosSeekBar.width.toFloat()) / horizontalPosSeekBar.width
                    val newProgress = (percentage * horizontalPosSeekBar.max).toInt()

                    horizontalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("uiTransXProgress", newProgress)
                        .apply()

                    updateUiTranslation()

                    horizontalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        horizontalPosSeekBar.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on vertical pos seekbar
                if (verticalPosSeekBar != null && verticalPosSeekBar.visibility == View.VISIBLE &&
                    x >= vertPosLocation[0] - slop && x <= vertPosLocation[0] + (verticalPosSeekBar.width * uiScale) + slop &&
                    y >= vertPosLocation[1] - slop && y <= vertPosLocation[1] + (verticalPosSeekBar.height * uiScale) + slop) {

                    val relativeX = (x - vertPosLocation[0]) / uiScale
                    val percentage = relativeX.coerceIn(0f, verticalPosSeekBar.width.toFloat()) / verticalPosSeekBar.width
                    val newProgress = (percentage * verticalPosSeekBar.max).toInt()

                    verticalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("uiTransYProgress", newProgress)
                        .apply()

                    updateUiTranslation()

                    verticalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        verticalPosSeekBar.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on reset button
                if (resetButton != null && resetButton.visibility == View.VISIBLE &&
                    x >= resetLocation[0] - slop && x <= resetLocation[0] + (resetButton.width * uiScale) + slop &&
                    y >= resetLocation[1] - slop && y <= resetLocation[1] + (resetButton.height * uiScale) + slop) {

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
                    Handler(Looper.getMainLooper()).postDelayed({
                        resetButton.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on help button
                if (helpButton != null &&
                    x >= helpLocation[0] - slop && x <= helpLocation[0] + (helpButton.width * uiScale) + slop &&
                    y >= helpLocation[1] - slop && y <= helpLocation[1] + (helpButton.height * uiScale) + slop) {

                    // Visual feedback
                    helpButton.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        helpButton.isPressed = false
                        // Show help dialog
                        showHelpDialog()
                    }, 100)
                    return
                }

                // Check if click is on Reset Zoom button
                val resetZoomButton = menu.findViewById<Button>(R.id.btnResetFontSize)
                val resetZoomLocation = IntArray(2)
                resetZoomButton?.getLocationOnScreen(resetZoomLocation)
                
                if (resetZoomButton != null &&
                    x >= resetZoomLocation[0] - slop && x <= resetZoomLocation[0] + (resetZoomButton.width * uiScale) + slop &&
                    y >= resetZoomLocation[1] - slop && y <= resetZoomLocation[1] + (resetZoomButton.height * uiScale) + slop) {

                    // Reset font size to 100% (progress 50)
                    fontSizeSeekBar?.progress = 50
                    
                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("webFontSize", 50)
                        .apply()
                    
                    // Apply to WebView
                    val savedTextColor = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getString("webTextColor", null)
                    applyWebFontSettings(50, savedTextColor)

                    // Visual feedback
                    resetZoomButton.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        resetZoomButton.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on Reset Webpage Zoom button
                val resetWebpageZoomButton = menu.findViewById<Button>(R.id.btnResetWebpageZoom)
                val resetWebpageZoomLocation = IntArray(2)
                resetWebpageZoomButton?.getLocationOnScreen(resetWebpageZoomLocation)
                
                if (resetWebpageZoomButton != null &&
                    x >= resetWebpageZoomLocation[0] - slop && x <= resetWebpageZoomLocation[0] + (resetWebpageZoomButton.width * uiScale) + slop &&
                    y >= resetWebpageZoomLocation[1] - slop && y <= resetWebpageZoomLocation[1] + (resetWebpageZoomButton.height * uiScale) + slop) {

                    // Reset webpage zoom to 1.0
                    currentWebZoom = 1.0f
                    
                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putFloat("webZoomLevel", currentWebZoom)
                        .apply()
                        
                    webView.evaluateJavascript("""
                        (function() {
                            document.body.style.zoom = "$currentWebZoom";
                        })();
                    """, null)

                    // Visual feedback
                    resetWebpageZoomButton.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        resetWebpageZoomButton.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on font size seekbar
                if (fontSizeSeekBar != null &&
                    x >= fontSizeLocation[0] - slop && x <= fontSizeLocation[0] + (fontSizeSeekBar.width * uiScale) + slop &&
                    y >= fontSizeLocation[1] - slop && y <= fontSizeLocation[1] + (fontSizeSeekBar.height * uiScale) + slop) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - fontSizeLocation[0]) / uiScale
                    val percentage = relativeX.coerceIn(0f, fontSizeSeekBar.width.toFloat()) / fontSizeSeekBar.width
                    val newProgress = (percentage * fontSizeSeekBar.max).toInt()

                    // Update font size
                    fontSizeSeekBar.progress = newProgress
                    
                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("webFontSize", newProgress)
                        .apply()
                    
                    // Apply to WebView
                    val savedTextColor = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getString("webTextColor", null)
                    applyWebFontSettings(newProgress, savedTextColor)

                    // Visual feedback
                    fontSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        fontSizeSeekBar.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on color wheel
                if (colorWheelView != null &&
                    x >= colorWheelLocation[0] - slop && x <= colorWheelLocation[0] + (colorWheelView.width * uiScale) + slop &&
                    y >= colorWheelLocation[1] - slop && y <= colorWheelLocation[1] + (colorWheelView.height * uiScale) + slop) {

                    // Calculate relative position
                    val relativeX = (x - colorWheelLocation[0]) / uiScale
                    val relativeY = (y - colorWheelLocation[1]) / uiScale

                    val selectedColor = colorWheelView.calculateColorFromCoordinates(relativeX, relativeY)
                    
                    // Update visual indicator
                    colorWheelView.setColor(selectedColor)

                    // Apply color
                    val hexColor = String.format("#%06X", (0xFFFFFF and selectedColor))
                    applyTextColor(hexColor)

                    // Visual feedback
                    colorWheelView.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        colorWheelView.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on reset text color button
                if (resetTextColorButton != null &&
                    x >= resetTextColorLocation[0] - slop && x <= resetTextColorLocation[0] + (resetTextColorButton.width * uiScale) + slop &&
                    y >= resetTextColorLocation[1] - slop && y <= resetTextColorLocation[1] + (resetTextColorButton.height * uiScale) + slop) {

                    // Reset color to white
                    colorWheelView?.setColor(Color.WHITE)
                    // Reset color to white visually
                    colorWheelView?.setColor(Color.WHITE)
                    applyTextColor(null)

                    // Visual feedback
                    resetTextColorButton.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        resetTextColorButton.isPressed = false
                    }, 100)
                    return
                }

                // Check if click is on close button
                if (closeButton != null &&
                    x >= closeLocation[0] - slop && x <= closeLocation[0] + (closeButton.width * uiScale) + slop &&
                    y >= closeLocation[1] - slop && y <= closeLocation[1] + (closeButton.height * uiScale) + slop) {

                    // Visual feedback
                    closeButton.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        closeButton.isPressed = false
                        // Close settings
                        isSettingsVisible = false
                        settingsMenu?.visibility = View.GONE
                        settingsScrim?.visibility = View.GONE
                        startRefreshing()
                    }, 100)
                    return
                }

                /* Log.d("SettingsDebug", """
            Touch at ($x, $y)
            Volume seekbar at (${volumeLocation[0]}, ${volumeLocation[1]}) size ${volumeSeekBar?.width}x${volumeSeekBar?.height}
            Brightness seekbar at (${brightnessLocation[0]}, ${brightnessLocation[1]}) size ${brightnessSeekBar?.width}x${brightnessSeekBar?.height}
            Smoothness seekbar at (${smoothnessLocation[0]}, ${smoothnessLocation[1]}) size ${smoothnessSeekBar?.width}x${smoothnessSeekBar?.height}
            Close button at (${closeLocation[0]}, ${closeLocation[1]}) size ${closeButton?.width}x${closeButton?.height}
        """.trimIndent()) */
            }
            else {
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
        
        val colorCss = if (textColor != null) {
            "body, body *, p, span, div, h1, h2, h3, h4, h5, h6, a, li, td, th { color: $textColor !important; }"
        } else {
            ""
        }
        
        val js = """
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
        val fontSizeProgress = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            .getInt("webFontSize", 50)
        applyWebFontSettings(fontSizeProgress, colorHex)
    }

    /**
     * Update the color of Custom UI elements (Settings Menu and Keyboard)
     */
    private fun updateCustomUiColor(colorHex: String?) {
        val color = if (colorHex != null) {
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
        settingsMenu?.let { menu ->
            updateViewColorsRecursively(menu, color)
        }

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

    /**
     * Re-apply saved font settings to the WebView. Called when a new page loads.
     */
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
        // Log.d("ScrollMode", "setScrollMode called with enabled=$enabled, current isInScrollMode=$isInScrollMode")

        if (isInScrollMode == enabled) return
        isInScrollMode = enabled

        if (enabled) {
            // Immediately disable touch interception before animating
            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            leftSystemInfoView.visibility = View.GONE

            // Then animate menus away
            leftToggleBar.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    leftToggleBar.visibility = View.GONE
                }
                .start()

            leftNavigationBar.animate()
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
            leftToggleBar.animate()
                .alpha(1f)
                .setDuration(200)
                .start()

            leftNavigationBar.visibility = View.VISIBLE
            leftNavigationBar.alpha = 0f
            leftNavigationBar.animate()
                .alpha(1f)
                .setDuration(200)
                .start()

            // Hide force-show button
            btnShowNavBars.animate().alpha(0f).setDuration(200).withEndAction {
                btnShowNavBars.visibility = View.GONE
            }.start()
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

    fun showPromptDialog(message: String, defaultValue: String?, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
        showDialog("Prompt", message, true, defaultValue, { text -> onConfirm(text ?: "") }, onCancel)
    }

    fun showHelpDialog(page: Int = 1) {
        val (title, message, hasNext, hasPrev) = when(page) {
            1 -> Quadruple(
                "Features: Touch & Menu",
                """
                TOUCH GESTURES:
                • Single Tap: Click links, buttons, and focus fields.
                • Double Tap: Swiftly go back to the previous page.
                
                TRIPLE-TAP MENU:
                • Quick Refresh
                • Navigation (Forward/Home)
                • Quick Bookmarks access
                """.trimIndent(),
                true, false
            )
            2 -> Quadruple(
                "Features: Screen Modes",
                """
                ANCHORED MODE (Anchor Icon):
                • Screen stays fixed in space relative to the world.
                • Smoothness: Controls how rigidly the screen follows tracking.
                
                NON-ANCHORED MODE (Crossed Anchor):
                • Screen is "locked" to your head movement.
                • Screen Position: Shift the display H/V when UI Scale < 100%.
                """.trimIndent(),
                true, true
            )
            3 -> Quadruple(
                "Features: Display & Tools",
                """
                SCROLL MODE (Eye Icon):
                • Hides UI for an immersive browsing experience.
                • Restore UI: Tap the transparent "Show" button.
                
                UTILITIES:
                • Volume & Brightness Sliders.
                • UI Scale: Adjust the global interface size.
                • Web Zoom (+/-): Content zoom level.
                """.trimIndent(),
                true, true
            )
            4 -> Quadruple(
                "Features: Blank Screen Mode",
                """
                BLANK SCREEN MODE (Eye Toggle):
                • Blacks out display while media continues playing.
                • Perfect for listening to audio/podcasts.
                • Note: Disables anchored mode while active.
                
                MEDIA CONTROLS (shown when media is playing):
                • Play/Pause: Toggle media playback.
                • Skip Back/Forward: Jump 10 seconds.
                • Unmask (Eye Icon): Exit blank screen mode.
                """.trimIndent(),
                false, true
            )
            else -> return
        }

        val footerButtons = mutableListOf<View>()
        
        if (hasPrev) {
            footerButtons.add(Button(context).apply {
                text = "Back"
                textSize = 14f
                setTextColor(Color.parseColor("#AAAAAA"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showHelpDialog(page - 1) }
            })
        }
        
        if (hasNext) {
            footerButtons.add(Button(context).apply {
                text = "Next"
                textSize = 14f
                setTextColor(Color.parseColor("#4488FF"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showHelpDialog(page + 1) }
            })
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

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
        
        // Hide keyboard container initially to avoid overlapping, though we might show it again if input is focused
        keyboardContainer.visibility = View.GONE
        
        val padding = 16.dp()
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                500, // Fixed width for consistent look
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#202020"))
                setStroke(2, Color.parseColor("#404040"))
                cornerRadius = 16f
            }
            elevation = 100f
            isClickable = true
            isFocusable = true
        }

        // Title
        val titleView = TextView(context).apply {
            text = title
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16.dp()
            }
        }
        dialogView.addView(titleView)

        // Message
        val messageView = TextView(context).apply {
            text = message
            textSize = 16f
            setTextColor(Color.parseColor("#DDDDDD"))
            maxLines = 15
            isVerticalScrollBarEnabled = true
            movementMethod = ScrollingMovementMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24.dp()
            }
        }
        dialogView.addView(messageView)

        var inputField: EditText? = null
        if (hasInput) {
            inputField = EditText(context).apply {
                setText(defaultValue ?: "")
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(16, 16, 16, 16)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#303030"))
                    cornerRadius = 8f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 24.dp()
                }
                
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
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        if (onCancel != null) {
            val cancelButton = Button(context).apply {
                text = "Cancel"
                textSize = 16f
                setTextColor(Color.parseColor("#AAAAAA"))
                background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
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
               button.background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            }
            buttonContainer.addView(button)
        }

        if (confirmLabel != null) {
            val confirmButton = Button(context).apply {
                text = confirmLabel
                textSize = 16f
                setTextColor(Color.parseColor("#4488FF"))
                background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
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
            val toastView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 64.dp()
                }
                setPadding(padding * 2, padding, padding * 2, padding)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E0303030"))  // Semi-transparent dark
                    cornerRadius = 24f
                }
                elevation = 100f
            }

            val messageView = TextView(context).apply {
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
            dialogContainer.isClickable = false  // Allow clicks to pass through

            // Log.d("Toast", "Toast view added, dialogContainer visible: ${dialogContainer.visibility == View.VISIBLE}, child count: ${dialogContainer.childCount}")

            // Ensure rendering updates
            requestLayout()
            invalidate()
            startRefreshing()

            // Auto-dismiss after duration
            toastRunnable = Runnable {
                hideToast()
            }
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
        if (dialogContainer.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        dialogContainer.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + (dialogContainer.width * uiScale) &&
               y >= loc[1] && y <= loc[1] + (dialogContainer.height * uiScale)
    }

    private fun setupMaskOverlayUI() {


        // Unmask button (Bottom Right)
        btnMaskUnmask = ImageButton(context).apply {
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
        val unmaskParams = FrameLayout.LayoutParams(40, 40).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = 8
            bottomMargin = 8
        }
        maskOverlay.addView(btnMaskUnmask, unmaskParams)

        // Media Controls Container (Bottom Center)
        maskMediaControlsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE // Hidden by default until media detected
        }
        val controlsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            40
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 8
        }
        maskOverlay.addView(maskMediaControlsContainer, controlsParams)

        // Controls - Order: Prev Track, 10s Back, Play, Pause, 10s Forward, Next Track
        btnMaskPrevTrack = createMediaButton(R.string.fa_backward_step) {
            // Try to click previous track button (works on YouTube, Spotify, etc.)
            webView.evaluateJavascript("""
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
            """.trimIndent(), null)
        }
        btnMaskPrev = createMediaButton(R.string.fa_backward) {
            webView.evaluateJavascript("document.querySelector('video, audio').currentTime -= 10;", null)
        }
        btnMaskPlay = createMediaButton(R.string.fa_play) {
            webView.evaluateJavascript("document.querySelector('video, audio').play();", null)
            // Immediately update button visibility for responsive UI
            btnMaskPlay.visibility = View.GONE
            btnMaskPause.visibility = View.VISIBLE
        }
        btnMaskPause = createMediaButton(R.string.fa_pause) {
            webView.evaluateJavascript("document.querySelector('video, audio').pause();", null)
            // Immediately update button visibility for responsive UI
            btnMaskPause.visibility = View.GONE
            btnMaskPlay.visibility = View.VISIBLE
        }
        btnMaskNext = createMediaButton(R.string.fa_forward) {
            webView.evaluateJavascript("document.querySelector('video, audio').currentTime += 10;", null)
        }
        btnMaskNextTrack = createMediaButton(R.string.fa_forward_step) {
            // Try to click next track button (works on YouTube, Spotify, etc.)
            webView.evaluateJavascript("""
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
            """.trimIndent(), null)
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
            layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                leftMargin = 4
                rightMargin = 4
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun updateMediaState(isPlaying: Boolean) {
        // Log.d("MediaControls", "updateMediaState called: isPlaying=$isPlaying, isScreenMasked=$isScreenMasked")
        post {
            if (isPlaying) {
                // Log.d("MediaControls", "Setting to playing state")
                btnMaskPlay.visibility = View.GONE
                btnMaskPause.visibility = View.VISIBLE
                maskMediaControlsContainer.visibility = View.VISIBLE
                // Log.d("MediaControls", "Controls container visibility: ${maskMediaControlsContainer.visibility}, parent: ${maskMediaControlsContainer.parent}")
            } else {
                // Log.d("MediaControls", "Setting to paused state")
                btnMaskPlay.visibility = View.VISIBLE
                btnMaskPause.visibility = View.GONE
                // Keep controls visible if we know media exists
                maskMediaControlsContainer.visibility = View.VISIBLE
                // Log.d("MediaControls", "Controls container visibility: ${maskMediaControlsContainer.visibility}")
            }
        }
    }

    fun hideMediaControls() {
        post {
            maskMediaControlsContainer.visibility = View.GONE
        }
    }

    fun injectLocation(latitude: Double, longitude: Double) {
        val script = """
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
        
        post {
            webView.evaluateJavascript(script, null)
        }
    }

}