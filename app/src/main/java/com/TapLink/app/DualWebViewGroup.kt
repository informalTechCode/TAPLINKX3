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

    private val webView: WebView = WebView(context)
    private val rightEyeView: SurfaceView = SurfaceView(context)
    val keyboardContainer: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }
    
    val dialogContainer: FrameLayout = FrameLayout(context).apply {
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
    private val refreshInterval = 33L // Reduced to ~30fps for performance
    private var lastCaptureTime = 0L
    private val MIN_CAPTURE_INTERVAL = 33L  // Cap at ~30fps
    private var lastCursorUpdateTime = 0L
    private val CURSOR_UPDATE_INTERVAL = 16L  // 60fps cap for cursor updates

    private lateinit var leftSystemInfoView: SystemInfoView

    lateinit var leftNavigationBar: View
    private val verticalBarSize = 480 - 40
    private val nButtons    = 9
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
    private val refreshLock = Object()

    // Near the top of the class
    private var isDesktopMode = false
    private var isHoveringModeToggle = false
    private var isHoveringScrollToggle = false
    private var isHoveringDashboardToggle = false
    private var isHoveringBookmarksMenu = false
    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private lateinit var leftBookmarksView: BookmarksView

    var navigationListener: NavigationListener? = null
    var linkEditingListener: LinkEditingListener? = null

    private var isBookmarkEditing = false

    private var isHoveringScrollUp = false
    private var isHoveringScrollLeft = false
    private var isHoveringScrollRight = false
    private var isHoveringScrollDown = false
    private val scrollAmount = 50 // Horizontal scroll distance for arrow buttons
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
    private var isHoveringAnchorToggle = false

    private var tripleClickMenu: TripleClickMenu? = null
    private var isTripleClickMenuInitialized = false

    private val bitmapLock = Object()
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
        val left: ImageButton,
        val right: ImageButton,
        var isHovered: Boolean = false
    )

    private fun ImageButton.configureToggleButton(iconRes: Int) {
        visibility = View.VISIBLE
        setImageResource(iconRes)
        setBackgroundResource(R.drawable.nav_button_background)
        scaleType = ImageView.ScaleType.FIT_CENTER
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
    lateinit var urlEditText: EditText
    private val urlFieldMinHeight = 56.dp()

    private var leftEditField: EditText
    private var rightEditField: EditText
    private var isUrlEditing = false

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
    
    // Track scroll mode state before fullscreen
    private var wasInScrollModeBeforeFullscreen = false
    
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
    private lateinit var btnMaskPlay: ImageButton
    private lateinit var btnMaskPause: ImageButton
    private lateinit var btnMaskPrev: ImageButton
    private lateinit var btnMaskNext: ImageButton
    private lateinit var btnMaskUnmask: ImageButton



    var anchorToggleListener: AnchorToggleListener? = null

    // Add properties to track translations
    private var _translationX = 0f
    private var _translationY = 0f
    private var _rotationZ    = 0f

    private var isInScrollMode = false
    private var settingsScrim: View? = null

    init {



        try {
            context.resources.getDrawable(R.drawable.ic_arrow_up, null)
            Log.d("ResourceDebug", "ic_arrow_up found")
        } catch (e: Exception) {
            Log.e("ResourceDebug", "ic_arrow_up not found", e)
        }

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
            evaluateJavascript("""
                document.documentElement.style.backgroundColor = 'black';
                document.body.style.backgroundColor = 'black';
            """, null)

            //setBackgroundColor(Color.TRANSPARENT)  // Changed to TRANSPARENT to allow mirroring
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setOnTouchListener { _, _ ->
                keyboardContainer.visibility == View.VISIBLE
            }
            setOnLongClickListener { true }
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
                Log.d("KeyboardDebug", "leftKeyboardContainer clicked")
            }
            setOnTouchListener { _, event ->
                Log.d("KeyboardDebug", "leftKeyboardContainer received touch event: ${event.action}")
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



        Log.d("ViewDebug", "Toggle bar initialized with hash: ${leftToggleBar.hashCode()}")



        // Add views in correct order for proper layering
        //addView(webView)
        //addView(leftToggleBar)
        //addView(leftNavigationBar)



        setupMaskOverlayUI()

        // Add the container to the main view
        //addView(leftEyeUIContainer)

        // Set background colors to black
        setBackgroundColor(Color.BLACK)
        leftNavigationBar.setBackgroundColor(Color.BLACK)
        leftToggleBar.setBackgroundColor(Color.BLACK)



        // Set up the toggle buttons with explicit configurations
        leftToggleBar.findViewById<ImageButton>(R.id.btnModeToggle).apply {
            configureToggleButton(R.drawable.ic_mode_mobile)
        }

        leftToggleBar.findViewById<ImageButton>(R.id.btnYouTube).apply {
            configureToggleButton(R.drawable.ic_dashboard)
        }


        leftToggleBar.findViewById<ImageButton>(R.id.btnBookmarks).apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.ic_bookmarks)
            setBackgroundResource(R.drawable.nav_button_background)
            scaleType = ImageView.ScaleType.FIT_CENTER
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
                Log.d("TouchDebug", """
                Toggle Bar Layout:
                Width: ${leftToggleBar.width}
                Height: ${leftToggleBar.height}
                Left: ${leftToggleBar.left}
                Top: ${leftToggleBar.top}
                Translation: (${leftToggleBar.translationX}, ${leftToggleBar.translationY})
            """.trimIndent())
                viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })



        // Add views in the correct order (after other views but before maskOverlay)
        //addView(leftSystemInfoView)

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
            Log.d("ViewDebug", "Toggle bar added to UI container with hash: ${leftToggleBar.hashCode()}")

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


        // In init block
        // leftEyeUIContainer is already added to leftEyeClipParent earlier.
        // leftEyeClipParent.addView(fullScreenOverlayContainer) // Reverted move

        // Add the clip parent to the main view
        addView(leftEyeClipParent)
        addView(rightEyeView)  // Keep right eye view separate
        // maskOverlay now added to leftEyeUIContainer above for proper mirroring

        // Load and apply saved UI scale after view hierarchy is ready
        post {
            val savedScaleProgress = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .getInt("uiScaleProgress", 100)
            val savedScale = 0.35f + (savedScaleProgress / 100f) * 0.65f
            updateUiScale(savedScale)
        }

    }



    fun showFullScreenOverlay(view: View) {
        Log.d("FullscreenDebug", "showFullScreenOverlay called")
        
        if (view.parent is ViewGroup) {
            (view.parent as ViewGroup).removeView(view)
        }

        fullScreenOverlayContainer.removeAllViews()
        fullScreenOverlayContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

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
            Log.d("FullscreenDebug", "  Hiding $name (was ${if (target.visibility == View.VISIBLE) "VISIBLE" else "GONE"})")
            previousFullScreenVisibility[target] = target.visibility
            target.visibility = View.GONE
        }

        fullScreenOverlayContainer.visibility = View.VISIBLE
        fullScreenOverlayContainer.bringToFront()
        
        // Save current scroll mode state and enable it to hide all navigation bars
        wasInScrollModeBeforeFullscreen = isInScrollMode
        if (!isInScrollMode) {
            Log.d("FullscreenDebug", "Enabling scroll mode to hide navigation bars")
            setScrollMode(true)
        }
        
        // Hide system UI (status bar if any) for immersive fullscreen
        Log.d("FullscreenDebug", "About to call hideSystemUI()")
        hideSystemUI()
    }

    fun hideFullScreenOverlay() {
        fullScreenOverlayContainer.removeAllViews()
        fullScreenOverlayContainer.visibility = View.GONE
        previousFullScreenVisibility.forEach { (target, visibility) ->
            target.visibility = visibility
        }
        previousFullScreenVisibility.clear()
        
        // Restore previous scroll mode state
        if (!wasInScrollModeBeforeFullscreen && isInScrollMode) {
            Log.d("FullscreenDebug", "Restoring non-scroll mode (showing navigation bars)")
            setScrollMode(false)
        }
        
        // Restore system UI
        showSystemUI()
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
        leftToggleBar.findViewById<ImageButton>(R.id.btnMask)?.setImageResource(R.drawable.ic_visibility_off)
    }

    fun unmaskScreen() {
        isScreenMasked = false
        maskOverlay.visibility = View.GONE
        // Let MainActivity handle cursor visibility restoration - cursors will be shown
        // if they were visible before masking through updateCursorPosition call
        leftToggleBar.findViewById<ImageButton>(R.id.btnMask)?.setImageResource(R.drawable.ic_visibility_on)
    }

    fun isScreenMasked() = isScreenMasked

    fun dispatchMaskOverlayTouch(screenX: Float, screenY: Float) {
        val location = IntArray(2)
        maskOverlay.getLocationOnScreen(location)
        val scale = uiScale
        
        // Convert to local coordinates relative to mask overlay
        val localX = screenX - location[0]
        val localY = screenY - location[1]
        
        Log.d("MediaControls", "dispatchMaskOverlayTouch at local ($localX, $localY), scale: $scale")
        
        // Check unmask button hit (account for scale in button dimensions)
        val unmaskLocation = IntArray(2)
        btnMaskUnmask.getLocationOnScreen(unmaskLocation)
        val unmaskWidth = btnMaskUnmask.width * scale
        val unmaskHeight = btnMaskUnmask.height * scale
        if (screenX >= unmaskLocation[0] && screenX <= unmaskLocation[0] + unmaskWidth &&
            screenY >= unmaskLocation[1] && screenY <= unmaskLocation[1] + unmaskHeight) {
            Log.d("MediaControls", "Unmask button pressed")
            unmaskScreen()
            return
        }
        
        // Check media control buttons
        if (maskMediaControlsContainer.visibility == View.VISIBLE) {
            val controlsLocation = IntArray(2)
            maskMediaControlsContainer.getLocationOnScreen(controlsLocation)
            
            // Iterate through children (the media buttons)
            for (i in 0 until maskMediaControlsContainer.childCount) {
                val button = maskMediaControlsContainer.getChildAt(i) as? ImageButton ?: continue
                if (button.visibility != View.VISIBLE) continue
                
                val btnLocation = IntArray(2)
                button.getLocationOnScreen(btnLocation)
                val btnWidth = button.width * scale
                val btnHeight = button.height * scale
                
                if (screenX >= btnLocation[0] && screenX <= btnLocation[0] + btnWidth &&
                    screenY >= btnLocation[1] && screenY <= btnLocation[1] + btnHeight) {
                    Log.d("MediaControls", "Media button $i pressed")
                    button.performClick()
                    return
                }
            }
        }
        
        Log.d("MediaControls", "Touch on mask overlay but not on any button")
    }


    private fun clearNavigationButtonHoverStates() {
        navButtons.values.forEach { navButton ->
            navButton.isHovered = false
            navButton.left.isHovered = false
            navButton.right.isHovered = false
        }
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
        isUrlEditing = isEditing
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
        return if (isUrlEditing) urlEditText else null
    }

    fun animateViewportAdjustment() {
        webView.animate()
            .setDuration(200)
            .translationY(webView.translationY)
            .start()
    }



    // Method to show link editing UI
    fun showLinkEditing() {
        if (!isUrlEditing) {
            isUrlEditing = true

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
        return isUrlEditing
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
            Log.d("BookmarksDebug", "No tap handling - bookmarks not visible")
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
            Log.d("BookmarksDebug", "handleBookmarkDoubleTap() called. leftVisibility=${leftBookmarksView.visibility}")
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

                // Force cursor refresh if editing
                if (isUrlEditing && urlEditText.isFocused) {
                    urlEditText.invalidate()
                }

                // If triple click menu is visible, ensure it's properly layered
                tripleClickMenu?.let { menu ->
                    if (menu.visibility == View.VISIBLE) {
                        menu.bringToFront()
                        menu.invalidate()
                    }
                }

                val captureRect = android.graphics.Rect(0, 0, halfWidth, height)
                val window = (context as Activity).window

                PixelCopy.request(
                    window,
                    captureRect,
                    bitmapToUse,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS && isRefreshing) {
                            synchronized(bitmapLock) {
                                if (!bitmapToUse.isRecycled && bitmap === bitmapToUse) {
                                    drawBitmapToSurface()
                                    lastCaptureTime = System.currentTimeMillis()
                                }
                            }
                        }
                    },
                    refreshHandler
                )
            } catch (e: Exception) {
                Log.e("CursorDebug", "Error capturing content", e)
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
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isRefreshing && webView.isAttachedToWindow) {
                captureLeftEyeContent()
                refreshHandler.postDelayed(this, refreshInterval)
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

        tripleClickMenu?.let { menu ->
            if (isTripleClickMenuInitialized && menu.visibility == View.VISIBLE) {
                menu.invalidate()
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
        // Ensure toggle bar visibility
        if (leftToggleBar.measuredWidth == 0 || leftToggleBar.measuredHeight == 0) {
            leftToggleBar.measure(
                MeasureSpec.makeMeasureSpec(40, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(596, MeasureSpec.EXACTLY)
            )
            // Instead of directly calling layout, let's request layout
            leftToggleBar.requestLayout()
            // And make sure it's visible
            leftToggleBar.visibility = View.VISIBLE

            // Force a layout pass on the container
            leftEyeUIContainer.requestLayout()
        }


        val width = r - l
        val height = b - t
        val halfWidth = width / 2
        val toggleBarWidth = 40
        val navBarHeight = 40
        // Use actual measured height of keyboard if visible, otherwise default
        val keyboardHeight = if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160
        // Keyboard width is same regardless of mode (matches original keyboard size)
        val keyboardWidth = halfWidth - toggleBarWidth

        // Position the WebView differently based on scroll mode
        // Shrink the WebView when keyboard is visible so content isn't blocked
        val isKeyboardVisible = keyboardContainer.visibility == View.VISIBLE
        
        if (isInScrollMode) {
            val webViewBottom = if (isKeyboardVisible) {
                height - keyboardHeight  // Shrink to fit above keyboard
            } else {
                480
            }
            webView.layout(
                0,  // No left margin in scroll mode
                0,
                640,  // Full width
                webViewBottom
            )
        } else {
            val webViewBottom = if (isKeyboardVisible) {
                minOf(440, height - keyboardHeight)  // Shrink to fit above keyboard
            } else {
                440
            }
            webView.layout(
                40,  // Account for toggle bar
                0,
                640,  // Standard width + toggle bar offset
                webViewBottom
            )
        }

        // Calculate available content height based on keyboard visibility
        val contentHeight = if (keyboardContainer.visibility == View.VISIBLE) {
            height - keyboardHeight
        } else {
            height - navBarHeight
        }

        // Layout the clip parent
        leftEyeClipParent.layout(
            0,  // After toggle bar
            0,
            640,  // Fixed width for left eye
            height
        )

        fullScreenOverlayContainer.layout(
            0,  // Relative to leftEyeClipParent
            0,
            halfWidth,  // 640px width (matches clip parent)
            height
        )


        // Position SurfaceView exactly like WebView but offset horizontally for right eye
        rightEyeView.layout(
            halfWidth,
            0,
            width ,
            height
        )



        // Layout toggle bar - make sure it's tall enough for all buttons
        leftToggleBar.layout(0, 0, toggleBarWidth, 596)
//            Log.d("ToggleBarDebug", """
//        Toggle Bar Layout:
//        Visibility: ${leftToggleBar.visibility}
//        Width: $toggleBarWidth
//        Height: 596
//        Background: ${leftToggleBar.background}
//        Parent: ${leftToggleBar.parent?.javaClass?.simpleName}
//    """.trimIndent())

        val keyboardY = height - keyboardHeight
        keyboardContainer.layout(toggleBarWidth, keyboardY, toggleBarWidth + keyboardWidth, height)

        // Position ProgressBar - at bottom in scroll mode, above nav bar otherwise
        val progressBarHeight = 4
        if (isInScrollMode) {
            // In scroll mode, position at very bottom, full width
            progressBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = height - progressBarHeight
                progressBar.layout(0, pbY, halfWidth, height)
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
                val pbY = height - navBarHeight - progressBarHeight
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
            keyboardContainer.layout(kbLeft, keyboardY, kbLeft + keyboardWidth, height)

            // Hide navigation bars
            leftNavigationBar.visibility = View.GONE

            // Position bookmarks menu if visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                val bookmarksHeight = leftBookmarksView.measuredHeight
                val bookmarksY = if (isUrlEditing) {
                    40  // Below URL edit field
                } else {
                    keyboardY - bookmarksHeight
                }

                leftBookmarksView.layout(
                    toggleBarWidth,
                    bookmarksY,
                    toggleBarWidth + 480,
                    bookmarksY + bookmarksHeight
                )

                leftBookmarksView.bringToFront()
            }

            // Handle edit fields for both URL and bookmark editing
            if (isUrlEditing || isBookmarkEditing) {
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
            keyboardContainer.layout(toggleBarWidth, height, toggleBarWidth + keyboardWidth, height + keyboardHeight)

            // Position bookmarks when keyboard is not visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                leftBookmarksView.layout(
                    toggleBarWidth,
                    168,
                    toggleBarWidth + 480,
                    height
                )
            }

            // Show and position navigation bars
            leftNavigationBar.visibility = View.VISIBLE
            leftNavigationBar.layout(0, height - navBarHeight, halfWidth, height)
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
            val infoBarY = height - navBarHeight - infoBarHeight  // Position above nav bar

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
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
            )
            
            val measuredH = dialogContainer.measuredHeight
            
            val dialogLeft = toggleBarWidth + (keyboardWidth - dialogWidth) / 2
            
            // Calculate available vertical space, respecting the keyboard if it is visible
            val availableHeight = if (keyboardContainer.visibility == View.VISIBLE) {
                height - keyboardHeight
            } else {
                height
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

        // Layout the UI container to cover just the left half
        leftEyeUIContainer.layout(0, 0, halfWidth, height)
    }

    private fun toggleScreenMask() {
        isScreenMasked = !isScreenMasked
        maskOverlay.visibility = if (isScreenMasked) View.VISIBLE else View.GONE

        // Update the mask button icon
        leftToggleBar.findViewById<ImageButton>(R.id.btnMask)?.let { button ->
            button.setImageResource(
                if (isScreenMasked) R.drawable.ic_visibility_off else R.drawable.ic_visibility_on
            )
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
        System.gc()  // Request garbage collection
    }

    fun getCurrentEditText(): String {
        return urlEditText.text.toString()
    }


    fun hideLinkEditing() {
        isUrlEditing = false
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
        Log.d("KeyboardDebug", "setKeyboard called with keyboard: ${originalKeyboard.hashCode()}")

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
        leftToggleBar.findViewById<ImageButton>(R.id.btnModeToggle)?.invalidate()
    }

    private fun getCursorInContainerCoords(): Pair<Float, Float> {
        return computeAnchoredCoordinates(lastCursorX, lastCursorY)
    }

    private fun computeAnchoredKeyboardCoordinates(): Pair<Float, Float>? {
        val keyboard = keyboardContainer
        if (keyboard.width == 0 || keyboard.height == 0) {
            Log.d("TouchDebug", "computeAnchoredKeyboardCoordinates: keyboard not laid out")
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

        Log.d(
            "TouchDebug",
            "Anchored cursor mapped to keyboard local=($localX, $localY) kbSize=(${kbView.width}, ${kbView.height})"
        )

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
        Log.d("GestureDebug", "DualWebViewGroup onInterceptTouchEvent: ${ev.action}")

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            // Allow interactions with menus that are on top
            if (tripleClickMenu?.let { isTouchOnView(it, ev.x, ev.y) } == true) {
                return false
            }
            if (::leftBookmarksView.isInitialized && isTouchOnView(leftBookmarksView, ev.x, ev.y)) {
                return false
            }

            fullScreenTapDetector.onTouchEvent(ev)
            return true
        }

        if (isAnchored) {
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
                    Log.d("TouchDebug", "Intercepting anchored tap for bookmarks")
                }
            }

            // Check TripleClickMenu
            tripleClickMenu?.let { menu ->
                if (!isOverTarget && menu.visibility == View.VISIBLE) {
                    if (cursorX >= menu.left && cursorX <= menu.right &&
                        cursorY >= menu.top && cursorY <= menu.bottom) {
                        isOverTarget = true
                        anchoredTarget = 3
                        Log.d("TouchDebug", "Intercepting anchored tap for menu")
                    }
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
                        Log.d("TouchDebug", "Intercepting anchored ACTION_DOWN target=$anchoredTarget")
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
                        Log.d("TouchDebug", "Intercepting anchored ACTION_UP")
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
        val navBarHeight = 40
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
        if (isInScrollMode) {
            webView.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY)
            )
        } else {
            // 640 - 40 = 600
            webView.measure(
                MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(440, MeasureSpec.EXACTLY)
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

        if (isAnchored) {
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
                                        Log.d("TouchDebug", "Dispatching anchored tap to bookmarks")
                                        leftBookmarksView.handleAnchoredTap(cursorX - leftBookmarksView.left, cursorY - leftBookmarksView.top)
                                    }
                                }
                                3 -> { // Menu
                                    tripleClickMenu?.let { menu ->
                                        if (menu.visibility == View.VISIBLE) {
                                            Log.d("TouchDebug", "Dispatching anchored tap to menu")
                                            menu.handleAnchoredTap(cursorX - menu.left, cursorY - menu.top)
                                        }
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
                android.util.Log.d("TouchDebug",
                    "DWG UP: wasTap=$wasTap dur=${dur}ms travel=(${travelX},${travelY}) kbVisible=$kbVisible isAnchored=$isAnchored")
                
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

        Log.d("KeyboardDebug", "Keyboard tap: screen($screenX, $screenY) -> local($finalX, $finalY)")
        kbView.handleAnchoredTap(finalX, finalY)
    }

    fun updateBrowsingMode(isDesktop: Boolean) {
        //Log.d("ModeToggle", "Updating browsing mode to: ${if (isDesktop) "desktop" else "mobile"}")
        isDesktopMode = isDesktop

        // Step 1: Update WebView settings (user agent)
        webView.settings.apply {
            userAgentString = if (isDesktop) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            @Suppress("DEPRECATION")
            defaultZoom = WebSettings.ZoomDensity.MEDIUM
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // Step 2: Update viewport using JavaScript without forcing a complete reload
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
                viewport.content = 'width=600, initial-scale=1.0, maximum-scale=1.0';
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
            val leftButton = leftToggleBar.findViewById<ImageButton>(R.id.btnModeToggle)
            val newResource = if (isDesktop) R.drawable.ic_mode_desktop else R.drawable.ic_mode_mobile
            leftButton?.setImageResource(newResource)
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
            Log.d("DualWebViewGroup", """
            Edit field state:
            Text: $text
            Visibility: $visibility
            Parent: ${parent?.javaClass?.simpleName}
            Position: ($x, $y)
            Width: $width, Height: $height
        """.trimIndent())
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


    private fun showButtonClickFeedback(button: ImageButton) {
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

        val button = leftToggleBar.findViewById<ImageButton>(buttonId)

        when (buttonId) {
            R.id.btnModeToggle -> {
                button?.let { showButtonClickFeedback(it) }
                isDesktopMode = !isDesktopMode
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
                webView.evaluateJavascript(
                    """
                        (function() {
                            document.body.style.zoom = (parseFloat(document.body.style.zoom || 1) * 0.9).toString();
                        })();
                    """,
                    null
                )
            }

            R.id.btnZoomIn -> {
                button?.let { showButtonClickFeedback(it) }
                webView.evaluateJavascript(
                    """
                        (function() {
                            document.body.style.zoom = (parseFloat(document.body.style.zoom || 1) * 1.1).toString();
                        })();
                    """,
                    null
                )
            }

            R.id.btnScrollUp -> {
                button?.let { showButtonClickFeedback(it) }
                scrollViewportByFraction(-1)
            }

            R.id.btnScrollLeft -> {
                button?.let { showButtonClickFeedback(it) }
                webView.evaluateJavascript("window.scrollBy(-50, 0);", null)
            }

            R.id.btnScrollRight -> {
                button?.let { showButtonClickFeedback(it) }
                webView.evaluateJavascript("window.scrollBy(50, 0);", null)
            }

            R.id.btnScrollDown -> {
                button?.let { showButtonClickFeedback(it) }
                scrollViewportByFraction(1)
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
        
        // Debug: Log cursor position
        Log.d("HoverDebug", "Cursor at screen: ($screenX, $screenY)")
        
        // Helper function to check if cursor is over a button or view
        fun isOver(button: View?): Boolean {
            if (button == null || button.visibility != View.VISIBLE) return false
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            
            // Account for scale in hit testing
            val isOver = screenX >= location[0] && screenX <= location[0] + (button.width * uiScale) &&
                   screenY >= location[1] && screenY <= location[1] + (button.height * uiScale)
            
            if (isOver) {
                Log.d("HoverDebug", "Button at screen: (${location[0]}, ${location[1]}) size: ${button.width}x${button.height}")
            }
            return isOver
        }
        
        // Check bottom navigation bar buttons using actual screen positions
        navButtons.forEach { (name, navButton) ->
            if (isOver(navButton.left)) {
                navButton.isHovered = true
                navButton.left.isHovered = true
                navButton.right.isHovered = true
                Log.d("HoverDebug", "Hovering over nav button: $name")
                return  // Found the hovered button, stop checking
            }
        }
        
        // Check left toggle bar buttons
        val toggleBarButtons = listOf(
            Triple(R.id.btnModeToggle, "ModeToggle") { isHoveringModeToggle = true },
            Triple(R.id.btnYouTube, "Dashboard") { isHoveringDashboardToggle = true },
            Triple(R.id.btnBookmarks, "Bookmarks") { isHoveringBookmarksMenu = true },
            Triple(R.id.btnZoomOut, "ZoomOut") { isHoveringZoomOut = true },
            Triple(R.id.btnZoomIn, "ZoomIn") { isHoveringZoomIn = true },
            Triple(R.id.btnScrollUp, "ScrollUp") { isHoveringScrollUp = true },
            Triple(R.id.btnScrollLeft, "ScrollLeft") { isHoveringScrollLeft = true },
            Triple(R.id.btnScrollRight, "ScrollRight") { isHoveringScrollRight = true },
            Triple(R.id.btnScrollDown, "ScrollDown") { isHoveringScrollDown = true },
            Triple(R.id.btnMask, "Mask") { isHoveringMaskToggle = true },
            Triple(R.id.btnAnchor, "Anchor") { isHoveringAnchorToggle = true }
        )
        
        for ((buttonId, name, setHoverFlag) in toggleBarButtons) {
            val button = leftToggleBar.findViewById<ImageButton>(buttonId)
            if (isOver(button)) {
                button?.isHovered = true
                setHoverFlag()
                clearNavigationButtonStates()
                Log.d("HoverDebug", "Hovering over toggle button: $name")
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
                    R.id.horizontalPosSeekBar,
                    R.id.verticalPosSeekBar,
                    R.id.btnResetPosition,
                    R.id.btnHelp,
                    R.id.btnCloseSettings
                )
                for (id in settingsElements) {
                    val view = menu.findViewById<View>(id)
                    if (isOver(view)) {
                        view?.isHovered = true
                        Log.d("HoverDebug", "Hovering over settings element: $id")
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
                            Log.d("HoverDebug", "Hovering over dialog button: $i")
                            return
                        }
                    }
                }
            }
        }
        
        // Check keyboard elements if visible
        if (keyboardContainer.visibility == View.VISIBLE) {
            val kbView = customKeyboard
            if (kbView != null && kbView.visibility == View.VISIBLE) {
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

                // Check if the logical point is within the keyboard's logical bounds
                if (localX >= keyboardContainer.left && localX <= keyboardContainer.right &&
                    localY >= keyboardContainer.top && localY <= keyboardContainer.bottom) {
                    
                    val innerX = localX - keyboardContainer.left
                    val innerY = localY - keyboardContainer.top
                    
                    kbView.updateHover(innerX, innerY)
                    return // Found hovered keyboard, stop checking
                } else {
                    // Not over keyboard, clear its hover
                    kbView.updateHover(-1f, -1f)
                }
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
        isHoveringScrollUp      = false
        isHoveringScrollLeft    = false
        isHoveringScrollRight   = false
        isHoveringScrollDown    = false
        isHoveringMaskToggle    = false
        isHoveringAnchorToggle  = false

        // Clear visual hover states
        listOf(
            R.id.btnModeToggle,
            R.id.btnYouTube,
            R.id.btnBookmarks,
            R.id.btnZoomIn,
            R.id.btnZoomOut,
            R.id.btnScrollUp,
            R.id.btnScrollLeft,
            R.id.btnScrollRight,
            R.id.btnScrollDown,
            R.id.btnMask,
            R.id.btnAnchor

        ).forEach { id ->
            leftToggleBar.findViewById<ImageButton>(id)?.isHovered = false
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
                    R.id.horizontalPosSeekBar,
                    R.id.verticalPosSeekBar,
                    R.id.btnResetPosition,
                    R.id.btnHelp,
                    R.id.btnCloseSettings
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
        customKeyboard?.updateHover(-1f, -1f)
    }


    private fun scrollViewportByFraction(directionMultiplier: Int) {
        val script = """
            (function() {
                const fallbackViewportHeight = window.innerHeight
                    || (document.documentElement && document.documentElement.clientHeight)
                    || (document.body && document.body.clientHeight)
                    || 0;
                const hud = document.querySelector('.hud');
                const target = (hud && hud.scrollHeight > hud.clientHeight) ? hud : window;
                const targetHeight = (target === hud && hud && hud.clientHeight) ? hud.clientHeight : fallbackViewportHeight;
                const scrollAmount = targetHeight * $verticalScrollFraction;
                const delta = scrollAmount * $directionMultiplier;

                if (target === window) {
                    window.scrollBy({ top: delta, behavior: 'smooth' });
                    return delta;
                }

                const start = target.scrollTop;
                if (typeof target.scrollBy === 'function') {
                    target.scrollBy({ top: delta, behavior: 'smooth' });
                } else {
                    target.scrollTop += delta;
                }
                return target.scrollTop - start;
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.d("ScrollDebug", "Viewport scroll result: $result")
        }
    }

    private fun handleScrollButtonClick(direction: String) {
        when (direction) {
            "up" -> scrollViewportByFraction(-1)
            "down" -> scrollViewportByFraction(1)
            "left" -> webView.evaluateJavascript("window.scrollBy({ left: -$scrollAmount, behavior: 'smooth' });", null)
            "right" -> webView.evaluateJavascript("window.scrollBy({ left: $scrollAmount, behavior: 'smooth' });", null)
            else -> return
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
        val smallButtonWidth = toggleBarWidth / 2

        Log.d("TouchDebug", """
        handleNavigationClick:
        Coordinates: ($x, $y)
        Toggle bar width: $toggleBarWidth
        Is in toggle bar area: ${x < toggleBarWidth}
        Button height: $buttonHeight
        Current button row: ${(y / buttonHeight).toInt()}
    """.trimIndent())

        // First check if settings menu is visible and if click is within its bounds
        if (isSettingsVisible && settingsMenu != null) {
            val menuLocation = IntArray(2)
            settingsMenu?.getLocationOnScreen(menuLocation)
            val menuWidth = settingsMenu?.width ?: 0
            val menuHeight = settingsMenu?.height ?: 0
            Log.d("SettingsDebug", """x: $x, y: $y, menuLocation: ${menuLocation[0]}, ${menuLocation[1]},menuWidth: $menuWidth, menuHeight: $menuHeight""")
            if (x <= menuWidth &&
                y <= menuHeight) {
                Log.d("SettingsDebug", "Dispatching the event to settings")

                // Click is within settings menu bounds - dispatch touch event to settings menu
                dispatchSettingsTouchEvent(lastCursorX, lastCursorY)

                return  // Important: return early to prevent click from reaching toggle bar
            }

        }


        // Handle toggle bar clicks
        if (localX < toggleBarWidth) {
            // Special handling for zoom buttons (add this section)
            if (y >= 3*buttonHeight && y < 4*buttonHeight) {  // Y-range for zoom buttons (adjust range as needed)
                if (localX < smallButtonWidth) {
                    // Zoom out button
                    handleLeftMenuAction(R.id.btnZoomOut)
                    return
                } else if (localX < 2 * smallButtonWidth) {
                    // Zoom in button
                    handleLeftMenuAction(R.id.btnZoomIn)
                    return
                }
            }
            // Special handling for left/right scroll buttons
            if (y >= 5*buttonHeight && y < 6*buttonHeight) {  // Y-range for horizontal scroll buttons
                if (localX < smallButtonWidth) {
                    // Left scroll button
                    handleLeftMenuAction(R.id.btnScrollLeft)
                    return
                } else if (localX < 2 * smallButtonWidth) {
                    // Right scroll button
                    handleLeftMenuAction(R.id.btnScrollRight)
                    return
                }
            }

            // Regular toggle buttons
            data class ToggleButtonInfo(
                val id: Int,
                val name: String,
                val clickHandler: (ImageButton) -> Unit
            )

            val toggleButtons = mapOf(
                0f..buttonHeight.toFloat() to ToggleButtonInfo(
                    R.id.btnModeToggle,
                    "ModeToggle"
                ) { button -> handleLeftMenuAction(button.id) },
                buttonHeight.toFloat()..2*buttonHeight.toFloat() to ToggleButtonInfo(
                    R.id.btnYouTube,
                    "Dashboard"
                ) { button -> handleLeftMenuAction(button.id) },
                2*buttonHeight.toFloat()..3*buttonHeight.toFloat() to ToggleButtonInfo(
                    R.id.btnBookmarks,
                    "Bookmarks"
                ) { button -> handleLeftMenuAction(button.id) },
                4*buttonHeight.toFloat()..5*buttonHeight.toFloat() to ToggleButtonInfo(
                    R.id.btnScrollUp,
                    "ScrollUp"
                ) { button -> handleLeftMenuAction(button.id) },
                6*buttonHeight.toFloat()..7*buttonHeight.toFloat() to ToggleButtonInfo(
                    R.id.btnScrollDown,
                    "ScrollDown"
                ) { button -> handleLeftMenuAction(button.id) },
                7*buttonHeight.toFloat()..8*buttonHeight.toFloat() to ToggleButtonInfo(
                    R.id.btnMask,
                    "Mask"
                ) { button -> handleLeftMenuAction(button.id) },
                8*buttonHeight.toFloat()..9*buttonHeight.toFloat() to ToggleButtonInfo(
                    R.id.btnAnchor,
                    "Anchor"
                ) { button -> handleLeftMenuAction(button.id) }
            )

            // Handle regular button clicks
            toggleButtons.forEach { (range, buttonInfo) ->
                if (y in range) {
                    leftToggleBar.findViewById<ImageButton>(buttonInfo.id)?.let { button ->
                        buttonInfo.clickHandler(button)
                    }
                    return
                }
            }
        }

        if (y >= height - 40) {
            keyboardListener?.onHideKeyboard()
            Log.d("AnchoredTouchDebug","handling navigation click")
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
                isHoveringScrollUp -> handleScrollButtonClick("up")
                isHoveringScrollLeft -> handleScrollButtonClick("left")
                isHoveringScrollRight -> handleScrollButtonClick("right")
                isHoveringScrollDown -> handleScrollButtonClick("down")
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
        val zoomFactor = if (direction == "in") 1.1 else 0.9
        webView.evaluateJavascript("""
        (function() {
            document.body.style.zoom = (parseFloat(document.body.style.zoom || 1) * $zoomFactor).toString();
        })();
    """, null)
    }

    fun refreshBothBookmarks() {
        // Refresh left bookmarks view
        leftBookmarksView.refreshBookmarks()
        leftBookmarksView.visibility = View.VISIBLE
        leftBookmarksView.bringToFront()
        leftBookmarksView.measure(
            MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY),
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

        // Disable direct touch handling on toggle bar buttons in anchored mode
        // Logic removed to keep buttons enabled


        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(true)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(true)
        }

        // Use unbarred anchor icon when anchored
        leftToggleBar.findViewById<ImageButton>(R.id.btnAnchor)?.setImageResource(R.drawable.ic_anchor)
        tripleClickMenu?.updateAnchorButtonState(true)
    }

    fun stopAnchoring() {
        isAnchored = false
        resetPositions()

        // Re-enable touch handling on toggle bar buttons
        // Logic removed as buttons are no longer disabled


        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(false)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(false)
        }

        leftToggleBar.findViewById<ImageButton>(R.id.btnAnchor)?.setImageResource(R.drawable.ic_anchor_barred)
        tripleClickMenu?.updateAnchorButtonState(false)
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
            val params = MarginLayoutParams(480, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 40  // After toggle bar
                topMargin = 168  // Below toggle buttons
            }
            layoutParams = params
            elevation = 1000f
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#202020"))
            setPadding(8, 8, 8, 8)
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
            leftBookmarksView.handleAnchoredFling(velocity)
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
        val leftModeToggleButton   = leftToggleBar.findViewById<ImageButton>(R.id.btnModeToggle)
        val leftDashboardButton    = leftToggleBar.findViewById<ImageButton>(R.id.btnYouTube)
        val leftBookmarksButton    = leftToggleBar.findViewById<ImageButton>(R.id.btnBookmarks)
        val leftZoomInButton       = leftToggleBar.findViewById<ImageButton>(R.id.btnZoomIn)
        val leftZoomOutButton      = leftToggleBar.findViewById<ImageButton>(R.id.btnZoomOut)
        val leftScrollUpButton     = leftToggleBar.findViewById<ImageButton>(R.id.btnScrollUp)
        val leftScrollLeftButton   = leftToggleBar.findViewById<ImageButton>(R.id.btnScrollLeft)
        val leftScrollRightButton  = leftToggleBar.findViewById<ImageButton>(R.id.btnScrollRight)
        val leftScrollDownButton   = leftToggleBar.findViewById<ImageButton>(R.id.btnScrollDown)
        val leftMaskButton         = leftToggleBar.findViewById<ImageButton>(R.id.btnMask)
        val leftAnchorButton       = leftToggleBar.findViewById<ImageButton>(R.id.btnAnchor)


        // Calculate positioning constants
        val buttonHeight = verticalBarSize / (nButtons)
        val smallButtonWidth = 20 // Size for split buttons (zoom and scroll) - Reduced to match 40 width
        val buttonWidth      = 2 * smallButtonWidth
        //val spacing = 8 // Standard spacing between buttons

// Calculate Y positions based on consistent spacing (56dp per button)
        val modeToggleY      = 0
        val dashboardY       = buttonHeight
        val bookmarksY       = buttonHeight * 2
        val zoomButtonsY     = buttonHeight * 3
        val scrollUpY        = buttonHeight * 4
        val leftRightScrollY = buttonHeight * 5
        val scrollDownY      = buttonHeight * 6
        val maskY            = buttonHeight * 7
        val anchorY          = buttonHeight * 8

// Configure main feature buttons (top section)
        listOf(
            leftModeToggleButton to modeToggleY,
            leftDashboardButton to dashboardY,
            leftBookmarksButton to bookmarksY
        ).forEach { (button, yPosition) ->
            try {
                button?.apply {
                    val params = LinearLayout.LayoutParams(buttonHeight, buttonWidth)
                    layoutParams = params
                    visibility = View.VISIBLE
                    background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                    scaleType = ImageView.ScaleType.FIT_CENTER
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

                        Log.d("TouchDebug", """
                    Button Touch (${v.id}):
                    Raw touch: (${event.rawX}, ${event.rawY})
                    Button screen location: (${location[0]}, ${location[1]})
                    Button size: ${v.width}x${v.height}
                    Toggle bar screen location: (${parentLocation[0]}, ${parentLocation[1]})
                    Button relative to parent: (${v.x}, ${v.y})
                    Y Position in setup: $yPosition
                    Layout bounds: ($left, $top, $right, $bottom)
                """.trimIndent())

                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("ToggleButton", "Error configuring button", e)
            }
        }

        // Configure zoom buttons
        leftZoomOutButton?.apply {
            layoutParams = LinearLayout.LayoutParams(smallButtonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            setImageResource(R.drawable.ic_zoom_out)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 8, 0, 8)
            elevation = 4f
            alpha = 1f
            layout(left, zoomButtonsY, left + smallButtonWidth, zoomButtonsY + buttonHeight)
        }

        leftZoomInButton?.apply {
            layoutParams = LinearLayout.LayoutParams(smallButtonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            setImageResource(R.drawable.ic_zoom_in)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 8, 0, 8)
            elevation = 4f
            alpha = 1f
            layout(left + smallButtonWidth, zoomButtonsY, left + 2*smallButtonWidth, zoomButtonsY + buttonHeight)
        }

        // Configure scroll buttons
        leftScrollUpButton?.apply {
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(8, 8, 8, 8)
            elevation = 4f
            alpha = 1f
            layout(left, scrollUpY, left + buttonWidth, scrollUpY + buttonHeight)
        }

        // Configure left scroll button
        leftScrollLeftButton?.apply {
            layoutParams = LinearLayout.LayoutParams(smallButtonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            setImageResource(R.drawable.ic_arrow_left)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 8, 0, 8)
            elevation = 4f
            alpha = 1f
            layout(left, leftRightScrollY, left + smallButtonWidth, leftRightScrollY + buttonHeight)
        }

        // Configure right scroll button
        leftScrollRightButton?.apply {
            layoutParams = LinearLayout.LayoutParams(smallButtonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            setImageResource(R.drawable.ic_arrow_right)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 8, 0, 8)
            elevation = 4f
            alpha = 1f
            layout(left + smallButtonWidth, leftRightScrollY, left + 2*smallButtonWidth, leftRightScrollY + buttonHeight)
        }

        leftScrollDownButton?.apply {
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(8, 8, 8, 8)
            elevation = 4f
            alpha = 1f
            layout(left, scrollDownY, left + buttonWidth, scrollDownY + buttonHeight)
        }

        leftMaskButton?.apply {
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight)
            visibility = View.VISIBLE
            background = ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            setImageResource(R.drawable.ic_visibility_on)
            scaleType = ImageView.ScaleType.FIT_CENTER
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
            setImageResource(if (isAnchored) R.drawable.ic_anchor else R.drawable.ic_anchor_barred)
            scaleType = ImageView.ScaleType.FIT_CENTER
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
            leftScrollUpButton to R.id.btnScrollUp,
            leftScrollLeftButton to R.id.btnScrollLeft,
            leftScrollRightButton to R.id.btnScrollRight,
            leftScrollDownButton to R.id.btnScrollDown,
            leftMaskButton to R.id.btnMask,
            leftAnchorButton to R.id.btnAnchor
        ).forEach { (button, id) ->
            button?.setOnClickListener { handleLeftMenuAction(id) }
        }



    }


    fun setTripleClickMenu(menu: TripleClickMenu) {
        this.tripleClickMenu = menu
        isTripleClickMenuInitialized = true

        // Add the menu to the left eye UI container with explicit dimensions and margins
        (menu.parent as? ViewGroup)?.removeView(menu)
    leftEyeUIContainer.addView(menu, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            // Add explicit margins to ensure visibility
            topMargin = 48
            bottomMargin = 48
        })

        // Ensure proper z-ordering and visibility
        menu.elevation = 1000f
        menu.visibility = View.GONE
        menu.bringToFront()

        // Force immediate layout
        menu.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        // Add layout change listener
        menu.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                menu.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (menu.visibility == View.VISIBLE) {
                    menu.bringToFront()
                    startRefreshing()
                }
            }
        })

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun isSettingsVisible(): Boolean {
        return isSettingsVisible
    }

    private fun initializeSettingsBars() {
        settingsMenu?.let { menu ->
            val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
            val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)

            // Initialize Volume from AudioManager:
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            volumeSeekBar?.max = maxVolume
            volumeSeekBar?.progress = currentVolume
            Log.d("SettingsDebug", "Volume: $currentVolume of $maxVolume")

            // Initialize Brightness from System Settings:
            try {
                // Read brightness from system settings (0–255)
                val currentBrightness = android.provider.Settings.System.getInt(
                    (context as Activity).contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                )
                // Scale to a 0–100 range for your seek bar
                brightnessSeekBar?.max = 100
                brightnessSeekBar?.progress = ((currentBrightness / 255f) * 100).toInt()
                Log.d("SettingsDebug", "Brightness: $currentBrightness (scaled to ${brightnessSeekBar?.progress})")
            } catch (e: Exception) {
                Log.e("SettingsDebug", "Error retrieving screen brightness", e)
            }
        }
    }



    fun showSettings() {
        Log.d("SettingsDebug", "showSettings() called, isSettingsVisible: $isSettingsVisible")

        if (settingsMenu == null) {
            settingsMenu = LayoutInflater.from(context)
                .inflate(R.layout.settings_layout, null, false)
                .apply {
                    isClickable = false
                    isFocusable = false
                    elevation = 1001f  // Even higher elevation than scrim

                }


            // Add click handler for close button
            settingsMenu?.findViewById<Button>(R.id.btnCloseSettings)?.setOnClickListener {
                Log.d("SettingsDebug", "Close button clicked")
                isSettingsVisible = false
                settingsMenu?.visibility = View.GONE
                settingsScrim?.visibility = View.GONE
                startRefreshing()
            }

            // Add click handler for help button
            settingsMenu?.findViewById<ImageButton>(R.id.btnHelp)?.setOnClickListener {
                Log.d("SettingsDebug", "Help button clicked")
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

            Log.d("SettingsDebug", "Menu added with height: ${settingsMenu?.measuredHeight}")
        }

        // Before toggling visibility, update the seek bars with current system values.
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

            // Initialize screen size seekbar
            val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
            val savedScaleProgress = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .getInt("uiScaleProgress", 100)
            screenSizeSeekBar?.progress = savedScaleProgress
            
            // Apply initial scale
            val initialScale = 0.25f + (savedScaleProgress / 100f) * 0.75f
            updateUiScale(initialScale)

            // Initialize position sliders
            val showPosSliders = !isAnchored && initialScale < 0.99f
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
            val closeButton = menu.findViewById<Button>(R.id.btnCloseSettings)
            val helpButton = menu.findViewById<ImageButton>(R.id.btnHelp)
            val resetButton = menu.findViewById<Button>(R.id.btnResetPosition)
            val resetScreenSizeButton = menu.findViewById<Button>(R.id.btnResetScreenSize)

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

            if (x >= menuLocation[0] && x <= menuLocation[0] + (menu.width * uiScale) &&
                y >= menuLocation[1] && y <= menuLocation[1] + (menu.height * uiScale)) {
                // Check if click is on volume seekbar
                if (volumeSeekBar != null &&
                    x >= volumeLocation[0] && x <= volumeLocation[0] + (volumeSeekBar.width * uiScale) &&
                    y >= volumeLocation[1] && y <= volumeLocation[1] + (volumeSeekBar.height * uiScale)) {

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
                    x >= brightnessLocation[0] && x <= brightnessLocation[0] + (brightnessSeekBar.width * uiScale) &&
                    y >= brightnessLocation[1] && y <= brightnessLocation[1] + (brightnessSeekBar.height * uiScale)) {

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
                    x >= smoothnessLocation[0] && x <= smoothnessLocation[0] + (smoothnessSeekBar.width * uiScale) &&
                    y >= smoothnessLocation[1] && y <= smoothnessLocation[1] + (smoothnessSeekBar.height * uiScale)) {

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
                    x >= screenSizeLocation[0] && x <= screenSizeLocation[0] + (screenSizeSeekBar.width * uiScale) &&
                    y >= screenSizeLocation[1] && y <= screenSizeLocation[1] + (screenSizeSeekBar.height * uiScale)) {

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

                // Check if click is on reset screen size button
                if (resetScreenSizeButton != null &&
                    x >= resetScreenSizeLocation[0] && x <= resetScreenSizeLocation[0] + (resetScreenSizeButton.width * uiScale) &&
                    y >= resetScreenSizeLocation[1] && y <= resetScreenSizeLocation[1] + (resetScreenSizeButton.height * uiScale)) {

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
                    x >= horzPosLocation[0] && x <= horzPosLocation[0] + (horizontalPosSeekBar.width * uiScale) &&
                    y >= horzPosLocation[1] && y <= horzPosLocation[1] + (horizontalPosSeekBar.height * uiScale)) {

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
                    x >= vertPosLocation[0] && x <= vertPosLocation[0] + (verticalPosSeekBar.width * uiScale) &&
                    y >= vertPosLocation[1] && y <= vertPosLocation[1] + (verticalPosSeekBar.height * uiScale)) {

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
                    x >= resetLocation[0] && x <= resetLocation[0] + (resetButton.width * uiScale) &&
                    y >= resetLocation[1] && y <= resetLocation[1] + (resetButton.height * uiScale)) {

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
                    x >= helpLocation[0] && x <= helpLocation[0] + (helpButton.width * uiScale) &&
                    y >= helpLocation[1] && y <= helpLocation[1] + (helpButton.height * uiScale)) {

                    // Visual feedback
                    helpButton.isPressed = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        helpButton.isPressed = false
                        // Show help dialog
                        showHelpDialog()
                    }, 100)
                    return
                }

                // Check if click is on close button
                if (closeButton != null &&
                    x >= closeLocation[0] && x <= closeLocation[0] + (closeButton.width * uiScale) &&
                    y >= closeLocation[1] && y <= closeLocation[1] + (closeButton.height * uiScale)) {

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

                Log.d("SettingsDebug", """
            Touch at ($x, $y)
            Volume seekbar at (${volumeLocation[0]}, ${volumeLocation[1]}) size ${volumeSeekBar?.width}x${volumeSeekBar?.height}
            Brightness seekbar at (${brightnessLocation[0]}, ${brightnessLocation[1]}) size ${brightnessSeekBar?.width}x${brightnessSeekBar?.height}
            Smoothness seekbar at (${smoothnessLocation[0]}, ${smoothnessLocation[1]}) size ${smoothnessSeekBar?.width}x${smoothnessSeekBar?.height}
            Close button at (${closeLocation[0]}, ${closeLocation[1]}) size ${closeButton?.width}x${closeButton?.height}
        """.trimIndent())
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
        Log.d("ScrollMode", "setScrollMode called with enabled=$enabled, current isInScrollMode=$isInScrollMode")

        if (isInScrollMode == enabled) return
        isInScrollMode = enabled

        if (enabled) {
            // First set WebView to full size
            webView.layoutParams = FrameLayout.LayoutParams(640, 480).apply {
                leftMargin = 0
                topMargin = 0
                rightMargin = 0
                bottomMargin = 0
            }
            webView.requestLayout()

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
            // First set WebView back to original size
            webView.layoutParams = FrameLayout.LayoutParams(600, LayoutParams.MATCH_PARENT).apply {
                leftMargin = 40
                topMargin = 0
                rightMargin = 0
                bottomMargin = 40
            }
            webView.requestLayout()

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
        val halfWidth = 640 // Logical width

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

        // Controls
        btnMaskPrev = createMediaButton(R.drawable.ic_media_skip_previous) {
            webView.evaluateJavascript("document.querySelector('video, audio').currentTime -= 10;", null)
        }
        btnMaskPlay = createMediaButton(R.drawable.ic_media_play) {
            webView.evaluateJavascript("document.querySelector('video, audio').play();", null)
            // Immediately update button visibility for responsive UI
            btnMaskPlay.visibility = View.GONE
            btnMaskPause.visibility = View.VISIBLE
        }
        btnMaskPause = createMediaButton(R.drawable.ic_media_pause) {
            webView.evaluateJavascript("document.querySelector('video, audio').pause();", null)
            // Immediately update button visibility for responsive UI
            btnMaskPause.visibility = View.GONE
            btnMaskPlay.visibility = View.VISIBLE
        }
        btnMaskNext = createMediaButton(R.drawable.ic_media_skip_next) {
            webView.evaluateJavascript("document.querySelector('video, audio').currentTime += 10;", null)
        }

        btnMaskPause.visibility = View.GONE // Initially show Play

        maskMediaControlsContainer.addView(btnMaskPrev)
        maskMediaControlsContainer.addView(btnMaskPlay)
        maskMediaControlsContainer.addView(btnMaskPause)
        maskMediaControlsContainer.addView(btnMaskNext)
    }

    private fun createMediaButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            setBackgroundResource(R.drawable.nav_button_background)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                leftMargin = 4
                rightMargin = 4
            }
            setOnClickListener { onClick() }
        }
    }

    fun updateMediaState(isPlaying: Boolean) {
        Log.d("MediaControls", "updateMediaState called: isPlaying=$isPlaying, isScreenMasked=$isScreenMasked")
        post {
            if (isPlaying) {
                Log.d("MediaControls", "Setting to playing state")
                btnMaskPlay.visibility = View.GONE
                btnMaskPause.visibility = View.VISIBLE
                maskMediaControlsContainer.visibility = View.VISIBLE
                Log.d("MediaControls", "Controls container visibility: ${maskMediaControlsContainer.visibility}, parent: ${maskMediaControlsContainer.parent}")
            } else {
                Log.d("MediaControls", "Setting to paused state")
                btnMaskPlay.visibility = View.VISIBLE
                btnMaskPause.visibility = View.GONE
                // Keep controls visible if we know media exists
                maskMediaControlsContainer.visibility = View.VISIBLE
                Log.d("MediaControls", "Controls container visibility: ${maskMediaControlsContainer.visibility}")
            }
        }
    }

    fun hideMediaControls() {
        post {
            maskMediaControlsContainer.visibility = View.GONE
        }
    }

}