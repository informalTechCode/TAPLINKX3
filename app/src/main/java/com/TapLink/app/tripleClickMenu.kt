package com.TapLink.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

class TripleClickMenu(context: Context) : FrameLayout(context) {
    private val menuContainer: LinearLayout
    private val buttons: MutableList<View> = mutableListOf()
    private var isVisible = false

    // Add a lock object for thread safety
    private val focusLock = Any()

    private var _currentFocusIndex = 3
    private var currentFocusIndex: Int
        get() = synchronized(focusLock) { _currentFocusIndex }
        set(value) {
            synchronized(focusLock) {
                if (isVisible) {
                    // Remove coerceIn to allow multi-button skips
                    _currentFocusIndex = ((value % buttons.size) + buttons.size) % buttons.size
                    updateFocus()
                }
            }
        }

    // Add movement tracking properties
    private var accumulatedMovement = 0f
    private val movementThreshold = 50f

    private var flingAnimator: ValueAnimator? = null
    private val frameInterval = 16L                 // ~60 FPS

    interface TripleClickMenuListener {
        fun onAnchorTogglePressed()
        fun onQuitPressed()
        fun onBackPressed()
        fun onMaskTogglePressed()  // Add new listener method
    }

    var listener: TripleClickMenuListener? = null

    init {
        menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#202020"))
            setPadding(16, 16, 16, 16)
            elevation = 1000f

            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        // Create anchor toggle button
        val anchorButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_anchor_barred)
            setBackgroundResource(R.drawable.nav_button_background)
            layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                setMargins(0, 8, 0, 8)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }

        // Create mask toggle button
        val maskButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_visibility_off)
            setBackgroundResource(R.drawable.nav_button_background)
            layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                setMargins(0, 8, 0, 8)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }

        // Create quit button
        val quitButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(R.drawable.nav_button_background)
            layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                setMargins(0, 8, 0, 8)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }

        // Create back button
        val backButton = Button(context).apply {
            text = "back"
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundResource(R.drawable.nav_button_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                96
            ).apply {
                setMargins(0, 8, 0, 8)
                gravity = Gravity.CENTER
            }
            setPadding(32, 0, 32, 0)
            minWidth = 96
        }

        // Add buttons in order
        buttons.add(anchorButton)
        buttons.add(maskButton)    // Add mask button after anchor
        buttons.add(quitButton)
        buttons.add(backButton)    // Back button is last (index 3)

        anchorButton.setBackgroundResource(R.drawable.tripleclickmenu_item_background)
        maskButton.setBackgroundResource(R.drawable.tripleclickmenu_item_background)
        quitButton.setBackgroundResource(R.drawable.tripleclickmenu_item_background)
        backButton.setBackgroundResource(R.drawable.tripleclickmenu_item_background)

        buttons.forEach { button ->
            menuContainer.addView(button)
        }

        addView(menuContainer)
        visibility = View.GONE

        measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    fun show() {
        synchronized(focusLock) {
            isVisible = true
            visibility = View.VISIBLE
            _currentFocusIndex = 3  // Set focus to back button
            updateFocus()

            // Force parent to refresh
            (parent as? DualWebViewGroup)?.let { parent ->
                parent.post {
                    parent.startRefreshing()
                    parent.invalidate()
                }
            }
        }
    }

    fun hide() {
        synchronized(focusLock) {
            isVisible = false
            buttons.forEach { it.isPressed = false }
            visibility = View.GONE

            // Force parent to refresh
            (parent as? DualWebViewGroup)?.let { parent ->
                parent.post {
                    parent.startRefreshing()
                    parent.invalidate()
                }
            }
        }
    }

    fun isMenuVisible() = isVisible

    fun updateAnchorButtonState(isAnchored: Boolean) {
        (buttons[0] as ImageButton).setImageResource(
            if (!isAnchored) R.drawable.ic_anchor else R.drawable.ic_anchor_barred
        )
    }

    fun updateMaskButtonState(isMasked: Boolean) {
        (buttons[1] as ImageButton).setImageResource(
            if (isMasked) R.drawable.ic_visibility_off else R.drawable.ic_visibility_on
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        menuContainer.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val width = Math.max(menuContainer.measuredWidth, minimumWidth)
        val height = Math.max(menuContainer.measuredHeight, minimumHeight)
        setMeasuredDimension(width, height)
    }





    fun handleFling(velocityX: Float) {
        flingAnimator?.cancel()

        Log.d("FlingMotionDebug", """
        Starting new fling:
        Initial velocity: $velocityX
        Current focus: $_currentFocusIndex
        Movement threshold: $movementThreshold
    """.trimIndent())

        // Track if focus changed during motion
        var hadFocusChange = false
        // Track initial focus for comparison
        val initialFocus = _currentFocusIndex
        // Track total accumulated motion direction
        var totalMotion = 0f

        flingAnimator = ValueAnimator.ofFloat(velocityX, 0f).apply {
            duration = 200
            interpolator = null

            addUpdateListener { animator ->
                val currentVelocity = animator.animatedValue as Float
                val delta = currentVelocity * (frameInterval / 5000f)
                accumulatedMovement += delta
                totalMotion += delta  // Track total motion

                Log.d("FlingMotionDebug", """
            Animation frame:
            Current velocity: $currentVelocity
            Delta: $delta
            Accumulated movement: $accumulatedMovement
            Total motion: $totalMotion
        """.trimIndent())

                // Store previous focus to detect changes
                val previousFocus = _currentFocusIndex


                while (abs(accumulatedMovement) >= movementThreshold) {
                    if (accumulatedMovement > 0) {
                        _currentFocusIndex = (_currentFocusIndex - 1 + buttons.size) % buttons.size
                        accumulatedMovement -= movementThreshold
                    } else {
                        _currentFocusIndex = (_currentFocusIndex + 1) % buttons.size
                        accumulatedMovement += movementThreshold
                    }
                    updateFocus()
                }


                // Check if focus changed during this update
                if (_currentFocusIndex != previousFocus) {
                    hadFocusChange = true
                }
            }

            // Add end listener to handle final increment if needed
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // If we're back at the initial focus and never had any changes
                    if (_currentFocusIndex == initialFocus && !hadFocusChange) {
                        // Move one position based on total motion direction
                        if (totalMotion > 0) {
                            _currentFocusIndex = (_currentFocusIndex - 1 + buttons.size) % buttons.size
                        } else if (totalMotion < 0) {
                            _currentFocusIndex = (_currentFocusIndex + 1) % buttons.size
                        }
                        updateFocus()
                    }
                }
            })
        }
        flingAnimator?.start()
    }

    fun handleScroll(distance: Float) {
        // Similar logic to handleFling but for immediate movement
        accumulatedMovement += distance

        val previousFocus = _currentFocusIndex

        while (abs(accumulatedMovement) >= movementThreshold) {
            if (accumulatedMovement > 0) {
                _currentFocusIndex = (_currentFocusIndex - 1 + buttons.size) % buttons.size
                accumulatedMovement -= movementThreshold
            } else {
                _currentFocusIndex = (_currentFocusIndex + 1) % buttons.size
                accumulatedMovement += movementThreshold
            }
            updateFocus()
        }

        Log.d("ScrollMotionDebug", """
        Scroll update:
        Distance: $distance
        Accumulated: $accumulatedMovement
        Focus changed: ${_currentFocusIndex != previousFocus}
    """.trimIndent())
    }

    fun stopFling() {
        flingAnimator?.cancel()
        flingAnimator = null
        accumulatedMovement = 0f
    }


    fun handleTap() {
        if (!isVisible) return

        Log.d("MenuDebug", "Handling tap in menu, currentFocusIndex: $currentFocusIndex")

        when (currentFocusIndex) {
            0 -> {
                hide()
                listener?.onAnchorTogglePressed()
            }
            1 -> {
                hide()
                listener?.onMaskTogglePressed()  // Handle mask toggle
            }
            2 -> {
                hide()
                listener?.onQuitPressed()
            }
            3 -> {
                hide()
                listener?.onBackPressed()
            }
        }
    }

    private fun updateFocus() {
        buttons.forEachIndexed { index, button ->
            // If index == _currentFocusIndex, that button gets the lighter gray
            val isFocused = (index == _currentFocusIndex)
            button.isSelected = isFocused
        }
    }
}