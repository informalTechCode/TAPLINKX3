package com.TapLink.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.children
import kotlin.math.abs
import android.view.ViewConfiguration

private const val DEBUG_TOUCH = true
private const val TAG_TOUCH = "TouchDebug"
class CustomKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private fun actName(action: Int) = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> action.toString()
    }

    private fun dbg(msg: String) {
        if (DEBUG_TOUCH) android.util.Log.d(TAG_TOUCH, msg)
    }
    private var keys: MutableList<Button> = mutableListOf()
    // Change from var to private var
    private var currentRow = 0
    private var currentColumn = 0

    private val movementThreshold = 40f  // Adjust this for sensitivity

    // Add these properties to track the current position pre-commit
    private var tempRow = 0
    private var tempColumn = 0


    private var isAnchoredMode = false

    private val clickThreshold = 10f
    private val clickTimeout = 300L // milliseconds

    private enum class KeyboardMode {
        LETTERS,
        SYMBOLS;

        fun toggle(): KeyboardMode = if (this == LETTERS) SYMBOLS else LETTERS
    }

    private data class KeyboardLayout(
        val rows: List<List<String>>,
        val dynamicKeys: List<String>
    )

    private val keyboardLayouts = mapOf(
        KeyboardMode.LETTERS to KeyboardLayout(
            rows = listOf(
                listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
                listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
                listOf("Z", "X", "C", "V", "B", "N", "M")
            ),
            dynamicKeys = listOf("@", ".", "/")
        ),
        KeyboardMode.SYMBOLS to KeyboardLayout(
            rows = listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"),
                listOf("/", "*", ".", "'", ":", ";", "!", "?")
            ),
            dynamicKeys = listOf("\u25C0", "\u25B6", "")
        )
    )

    private val currentLayout: KeyboardLayout
        get() = keyboardLayouts.getValue(currentMode)

    private var currentMode = KeyboardMode.LETTERS

    private var firstMove = true

    private var isUpperCase = true

    private var isSyncing: Boolean = false

    private var hideButton: Button? = null  // Add if not already present

    interface OnKeyboardActionListener {
        fun onKeyPressed(key: String)
        fun onBackspacePressed()
        fun onEnterPressed()
        fun onHideKeyboard()
        fun onClearPressed()
        fun onMoveCursorLeft()
        fun onMoveCursorRight()
    }

    private var listener: OnKeyboardActionListener? = null

    fun setOnKeyboardActionListener(listener: OnKeyboardActionListener) {
        this.listener = listener
    }

    init {
        // Set orientation first
        orientation = VERTICAL


        // Initialize temp values
        tempRow = currentRow
        tempColumn = currentColumn

        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        // Inflate layout
        LayoutInflater.from(context).inflate(R.layout.keyboard_layout, this, true)




        post {
            //Log.d("KeyboardDebug", "Starting keyboard initialization")
            initializeKeys()
            findHideButton()
            updateKeyFocus()

            updateCapsButtonText()

        }


        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isEnabled = true

        // Remove any existing touch listeners
        setOnTouchListener(null)
        setBackgroundColor(Color.TRANSPARENT)
    }

    // Update copyStateFrom to properly handle case states
    fun copyStateFrom(source: CustomKeyboardView) {
        isSyncing = true
        this.currentRow = source.currentRow
        this.currentColumn = source.currentColumn
        this.isUpperCase = source.isUpperCase
        this.currentMode = source.currentMode

        adjustCurrentColumn()
        updateKeyboardKeys()
        updateKeyFocus()
        isSyncing = false
    }

    fun handleAnchoredTap(x: Float, y: Float) {
        if (!isAnchoredMode) return

        Log.d("KeyboardDebug", "handleAnchoredTap at ($x, $y)")

        val key = getKeyAtPosition(x, y)
        if (key != null) {
            Log.d("KeyboardDebug", "Found key: ${key.text}")
            handleButtonClick(key)
        } else {
            Log.d("KeyboardDebug", "No key found at position")
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
//        Log.d("TouchDebug", "CustomKeyboardView: Received dispatch - action: ${getActionString(event.action)}")
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        //Log.d("KeyboardDebug", "CustomKeyboardView measured dimensions: ${measuredWidth}x${measuredHeight}")
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        //Log.d("KeyboardDebug", "CustomKeyboardView onLayout: ($left, $top, $right, $bottom)")

        // Force redraw of child views after layout
        if (changed) {
            val keyboardLayout = getChildAt(0) as? LinearLayout
            keyboardLayout?.let { layout ->
                for (i in 0 until layout.childCount) {
                    val row = layout.getChildAt(i) as? LinearLayout
                    row?.let { rowLayout ->
                        for (j in 0 until rowLayout.childCount) {
                            val button = rowLayout.getChildAt(j) as? Button
                            button?.invalidate()
                        }
                        rowLayout.invalidate()
                    }
                }
                layout.invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        Log.d("KeyboardDebug", """
        onDraw called:
        Width: $width
        Height: $height
        Visibility: $visibility
        Alpha: $alpha
        TranslationY: $translationY
        Window visibility: ${windowVisibility}
        Is hardware accelerated: ${isHardwareAccelerated}
        Layer type: ${layerType}
    """.trimIndent())


        super.onDraw(canvas)
        // Force redraw of all buttons
        val keyboardLayout = getChildAt(0) as? LinearLayout
        keyboardLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val row = layout.getChildAt(i) as? LinearLayout
                row?.let { rowLayout ->
                    for (j in 0 until rowLayout.childCount) {
                        val button = rowLayout.getChildAt(j) as? Button
                        button?.invalidate()
                    }
                }
            }
        }
    }



    private fun handleButtonClick(button: Button) {
        val buttonId = button.id
        val buttonLabel = button.text.toString()

        Log.d("KeyboardDebug", "Processing click for button: $buttonLabel")

        if (button.isAttachedToWindow) {
            // Provide immediate visual feedback when the view hierarchy is still intact
            button.setBackgroundColor(Color.GRAY)
        }

        postDelayed({
            if (button.isAttachedToWindow) {
                // Only touch the view's drawable state if it's still attached. When the
                // keyboard hides (as it does after pressing enter in anchored mode) the
                // buttons are detached, and attempting to manipulate their background
                // triggers a crash.
                button.setBackgroundColor(Color.DKGRAY)
                button.setTextColor(Color.WHITE)
            }

            when (buttonId) {
                R.id.btn_caps -> {
                    Log.d("KeyboardDebug", "Handling caps button")
                    toggleCase()
                }
                R.id.btn_switch -> {
                    Log.d("KeyboardDebug", "Handling switch button")
                    toggleKeyboardMode()
                }
                R.id.btn_hide -> {
                    Log.d("KeyboardDebug", "Handling hide button")
                    listener?.onHideKeyboard()
                    // Don't call updateKeyFocus after hiding - keyboard will be gone
                    return@postDelayed
                }
                R.id.btn_backspace -> {
                    Log.d("KeyboardDebug", "Handling backspace")
                    listener?.onBackspacePressed()
                }
                R.id.btn_enter -> {
                    Log.d("KeyboardDebug", "Handling enter")
                    listener?.onEnterPressed()
                    // Don't call updateKeyFocus after enter - keyboard might be gone
                    return@postDelayed
                }
                R.id.btn_space -> {
                    Log.d("KeyboardDebug", "Handling space")
                    listener?.onKeyPressed(" ")
                }
                R.id.btn_clear -> {
                    Log.d("KeyboardDebug", "Handling clear")
                    listener?.onClearPressed()
                }
                R.id.button_left_dynamic,
                R.id.button_middle_dynamic,
                R.id.button_right_dynamic -> handleDynamicButtonClick(button.id)
                else -> {
                    Log.d("KeyboardDebug", "Handling character key: $buttonLabel")
                    listener?.onKeyPressed(buttonLabel)
                }
            }

            // Only update focus if we're in non-anchored mode and still attached
            if (!isAnchoredMode && isAttachedToWindow) {
                updateKeyFocus()
            }
        }, 50)
    }

    private fun toggleKeyboardMode() {
        currentMode = currentMode.toggle()
        updateKeyboardKeys()
        syncWithParent()
    }

    private fun handleDynamicButtonClick(buttonId: Int) {
        when (currentMode) {
            KeyboardMode.LETTERS -> {
                val index = when (buttonId) {
                    R.id.button_left_dynamic -> 0
                    R.id.button_middle_dynamic -> 1
                    R.id.button_right_dynamic -> 2
                    else -> return
                }
                currentLayout.dynamicKeys.getOrNull(index)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listener?.onKeyPressed(if (isUpperCase) it.uppercase() else it) }
            }
            KeyboardMode.SYMBOLS -> when (buttonId) {
                R.id.button_left_dynamic -> listener?.onMoveCursorLeft()
                R.id.button_middle_dynamic -> listener?.onMoveCursorRight()
            }
        }
    }

    private fun updateKeyboardKeys() {
        val keyboardLayout = getChildAt(0) as? LinearLayout ?: return
        val layoutConfig = currentLayout

        keyboardLayout.children.take(layoutConfig.rows.size).forEachIndexed { rowIndex, rowView ->
            val rowLayout = rowView as? LinearLayout ?: return@forEachIndexed
            val keyRow = layoutConfig.rows[rowIndex]
            var keyIndex = 0
            rowLayout.children.forEach { child ->
                val button = child as? Button ?: return@forEach
                if (button.id in specialButtonIds) return@forEach

                val keyText = keyRow.getOrNull(keyIndex).orEmpty()
                keyIndex++

                if (keyText.isEmpty()) {
                    button.visibility = View.GONE
                } else {
                    button.text = if (currentMode == KeyboardMode.LETTERS) {
                        if (isUpperCase) keyText.uppercase() else keyText.lowercase()
                    } else {
                        keyText
                    }
                    button.visibility = View.VISIBLE
                }
            }
        }

        val dynamicRow = keyboardLayout.children.elementAtOrNull(layoutConfig.rows.size) as? LinearLayout
        dynamicRow?.let { rowLayout ->
            val leftDynamicButton = rowLayout.findViewById<Button>(R.id.button_left_dynamic)
            val middleDynamicButton = rowLayout.findViewById<Button>(R.id.button_middle_dynamic)
            val rightDynamicButton = rowLayout.findViewById<Button>(R.id.button_right_dynamic)

            val dynamicKeys = layoutConfig.dynamicKeys

            configureDynamicButton(leftDynamicButton, dynamicKeys.getOrNull(0))
            configureDynamicButton(middleDynamicButton, dynamicKeys.getOrNull(1))
            configureDynamicButton(
                rightDynamicButton,
                dynamicKeys.getOrNull(2),
                isVisibleOverride = currentMode == KeyboardMode.LETTERS
            )
        }

        adjustCurrentColumn()
        updateSwitchButtonText()
        updateCapsButtonVisibility()
        postInvalidate()
        requestLayout()
    }

    private fun configureDynamicButton(
        button: Button?,
        label: String?,
        isVisibleOverride: Boolean? = null
    ) {
        button ?: return
        val shouldShow = isVisibleOverride ?: !label.isNullOrBlank()
        button.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (shouldShow && !label.isNullOrEmpty()) {
            button.text = label
        }
    }

    private fun adjustCurrentColumn() {
        val keyboardLayout = getKeyboardLayout()
        keyboardLayout?.let { layout ->
            val row = layout.getChildAt(currentRow) as? LinearLayout
            val visibleButtons = row?.children?.filter { it.visibility == View.VISIBLE }?.toList() ?: emptyList()
            val maxColumns = visibleButtons.size
            if (currentColumn >= maxColumns) {
                currentColumn = maxColumns - 1
            }
            if (currentColumn < 0) {
                currentColumn = 0
            }
        }
    }

    private fun updateCapsButtonText() {
        val capsButton = findViewById<Button>(R.id.btn_caps)
        capsButton?.let {
            it.text = if (isUpperCase) "ABC" else "abc"
            it.invalidate()
        }
    }

    private fun updateSwitchButtonText() {
        val switchButton = findViewById<Button>(R.id.btn_switch)
        switchButton?.let {
            it.text = if (currentMode == KeyboardMode.LETTERS) "123" else "ABC"
            it.invalidate()
        }
    }

    private fun updateCapsButtonVisibility() {
        val capsButton = findViewById<Button>(R.id.btn_caps)
        capsButton?.visibility = if (currentMode == KeyboardMode.LETTERS) View.VISIBLE else View.GONE
        capsButton?.invalidate()
    }

    private fun initializeKeys() {
        keys.clear()
        //Log.d("KeyboardDebug", "Initializing keys. Child count: $childCount")

        if (childCount > 0) {
            val keyboardLayout = getChildAt(0) as? LinearLayout
            keyboardLayout?.let { layout ->
                for (i in 0 until layout.childCount) {
                    val row = layout.getChildAt(i) as? LinearLayout
                    row?.let { rowLayout ->
                        for (j in 0 until rowLayout.childCount) {
                            val button = rowLayout.getChildAt(j) as? Button
                            button?.let {
                                keys.add(it)
                                // Store original text as the uppercase version
                                if (it.id !in specialButtonIds) {
                                    it.tag = it.text.toString().uppercase() // Store original text
                                }
                                it.isClickable = false
                                it.isFocusable = false
                                it.setBackgroundColor(Color.DKGRAY)
                                it.setTextColor(Color.WHITE)
                                //Log.d("KeyboardDebug", "Added button: ${it.text} with tag: ${it.tag}")
                            }
                        }
                    }
                }
            }
        }

        // Set initial caps button text
        keys.find { it.id == R.id.btn_caps }?.let { capsButton ->
            capsButton.text = if (isUpperCase) "ABC" else "abc"
        }
    }

    private val specialButtonIds = setOf(
        R.id.btn_hide,
        R.id.btn_space,
        R.id.btn_backspace,
        R.id.btn_enter,
        R.id.btn_switch,
        //R.id.btn_caps,
        R.id.btn_clear,
        R.id.button_left_dynamic,
        R.id.button_middle_dynamic,
        R.id.button_right_dynamic
    )
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }


    private fun toggleCase() {
        if (currentMode != KeyboardMode.LETTERS) return
        isUpperCase = !isUpperCase
        updateKeyboardKeys()
        syncWithParent()
    }


    fun updateKeyFocus() {
        if (isAnchoredMode) {
            // Don't update focus in anchored mode - no focus system needed
            return
        }

        // Non-anchored mode: use the focus system
        keys.forEach { button ->
            button.setBackgroundColor(Color.DKGRAY)
            button.setTextColor(Color.WHITE)
            button.invalidate()
        }

        getFocusedKey()?.let { button ->
            button.setBackgroundColor(Color.BLUE)
            button.setTextColor(Color.WHITE)
            button.invalidate()
        }

        invalidate()
        requestLayout()

        if (!isSyncing) {
            syncWithParent()
        }
    }

    private fun findHideButton() {
        hideButton = keys.find { it.id == R.id.btn_hide }

        if (hideButton != null) {
            // Find its position
            var foundRow = 0
            var foundCol = 0

            // Get the keyboard container
            if (childCount > 0) {
                val keyboard = getChildAt(0) as? LinearLayout
                if (keyboard != null) {
                    outerLoop@ for (i in 0 until keyboard.childCount) {
                        val row = keyboard.getChildAt(i) as? LinearLayout
                        if (row != null) {
                            for (j in 0 until row.childCount) {
                                if (row.getChildAt(j)?.id == R.id.btn_hide) {
                                    foundRow = i
                                    foundCol = j
                                    break@outerLoop
                                }
                            }
                        }
                    }

                    currentRow = foundRow
                    currentColumn = foundCol
                    //Log.d("KeyboardDebug", "Found hide button at row: $currentRow, col: $currentColumn")
                }
            } else {
                Log.d("KeyboardDebug", "Hide button not found - no keyboard container")
            }
        } else {
            Log.d("KeyboardDebug", "Hide button not found in keys list")
        }
    }

    private fun getActionString(action: Int): String {
        return when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "OTHER($action)"
        }
    }


    private fun getFocusedKey(): Button? {
        val keyboard = getKeyboardLayout()
        val row = keyboard?.getChildAt(currentRow) as? LinearLayout
        val visibleButtons = row?.children?.filter { it.visibility == View.VISIBLE }?.toList() ?: emptyList()
        return visibleButtons.getOrNull(currentColumn) as? Button
    }




    private fun moveFocusRight() {
        val keyboard = getKeyboardLayout()
        val row = keyboard?.getChildAt(currentRow) as? LinearLayout
        if (row != null) {
            val visibleButtons = row.children.filter { it.visibility == View.VISIBLE }.toList()
            val maxColumns = visibleButtons.size
            if (maxColumns > 0) {
                currentColumn = (currentColumn + 1) % maxColumns
                //Log.d("KeyboardDebug", "Moved to column: $currentColumn of $maxColumns columns")
                updateKeyFocus()
                syncWithParent()
            }
        }
    }

    private fun moveFocusLeft() {
        val keyboard = getKeyboardLayout()
        val row = keyboard?.getChildAt(currentRow) as? LinearLayout
        if (row != null) {
            val visibleButtons = row.children.filter { it.visibility == View.VISIBLE }.toList()
            val maxColumns = visibleButtons.size
            if (maxColumns > 0) {
                currentColumn = (currentColumn - 1) % maxColumns
                //Log.d("KeyboardDebug", "Moved to column: $currentColumn of $maxColumns columns")
                updateKeyFocus()
                syncWithParent()
            }
        }
    }


    private fun moveFocusDown() {
        val keyboardLayout = getKeyboardLayout()
        val maxRows = keyboardLayout?.childCount ?: 0
        if (maxRows > 0) {
            currentRow = (currentRow + 1) % maxRows
            //adjustCurrentColumn() // Adjust column for the new row
            updateKeyFocus()
            syncWithParent()
        }
    }

    private fun moveFocusUp() {
        val keyboardLayout = getKeyboardLayout()
        val maxRows = keyboardLayout?.childCount ?: 0
        if (maxRows > 0) {
            currentRow = (currentRow - 1) % maxRows
            //adjustCurrentColumn() // Adjust column for the new row
            updateKeyFocus()
            syncWithParent()
        }
    }



    // Modify handleFlingEvent to only use X velocity
    fun handleFlingEvent(velocityX: Float) {
        if (isAnchoredMode) {
            // Ignore fling events in anchored mode
            return
        }

        val threshold = 1000 // Adjust this value based on your AR glasses' sensitivity
        val horizontalThreshold = 500 // Minimum velocity to consider for horizontal movement

        // Log the velocity for debugging
        Log.d("KeyboardDebug", "Fling detected with velocityX=$velocityX")

        if (abs(velocityX) > threshold) {
            if (velocityX > horizontalThreshold) {
                // Strong positive X velocity - move horizontally to the right
                Log.d("KeyboardDebug", "Forward fling - Moving horizontally")
                moveFocusRight()
            } else if (velocityX < -horizontalThreshold) {
                // Strong negative X velocity - move horizontally to the left
                Log.d("KeyboardDebug", "Backward fling - Moving horizontally (left)")
                moveFocusDown()
            } else {
                // Small negative velocity (close to zero) - ignore or treat as no movement
                Log.d("KeyboardDebug", "Small velocity - Ignoring fling")
            }
        } else {
            // Velocity below threshold - ignore the fling
            Log.d("KeyboardDebug", "Velocity below threshold - Ignoring fling")
        }

        // Force update of key focus
        updateKeyFocus()
    }


    private fun getKeyboardLayout(): LinearLayout? {
        return if (childCount > 0) getChildAt(0) as? LinearLayout else null
    }

    fun setAnchoredMode(anchored: Boolean) {
        Log.d("KeyboardDebug", """
        setAnchoredMode:
        Previous mode: $isAnchoredMode
        New mode: $anchored
        Current visibility: $visibility
        Current alpha: $alpha
        Current dimensions: ${width}x${height}
    """.trimIndent())

        isAnchoredMode = anchored


        updateKeyFocus()
    }
    fun performFocusedTap() {
        getFocusedKey()?.let { button ->
            android.util.Log.d("TouchDebug", "CKV.performFocusedTap on: ${button.text}")
            handleButtonClick(button)
            updateKeyFocus()
            syncWithParent()
        } ?: run {
            android.util.Log.d("TouchDebug", "CKV.performFocusedTap: no focused key")
        }
    }

    fun handleDrag(x: Float, action: Int) {
        if (isAnchoredMode) {
            dbg("handleDrag: IGNORED (anchored) action=${actName(action)}")
            return
        }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                resetDragState()
                startX = x
                lastX = x
                touchStartTime = System.currentTimeMillis()
                firstMove = true
                isDragging = false
                accumulatedX = 0f
                accumulatedY = 0f

                dbg(
                    "handleDrag DOWN  x=%.2f | touchSlop=%d threshX=%.1f threshY=%.1f focus=%s"
                        .format(x, touchSlop, stepThresholdX, stepThresholdY, getFocusedKey()?.text ?: "null")
                )

            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val totalMove = kotlin.math.abs(x - startX)

                if (!isDragging && totalMove > touchSlop) {
                    isDragging = true
                    dbg(
                        "handleDrag MOVE  START DRAG  dx=%.2f total=%.2f > slop=%d"
                            .format(dx, totalMove, touchSlop)
                    )
                }

                if (isDragging) {
                    accumulatedX += dx

                    var stepsR = 0; var stepsL = 0
                    while (accumulatedX >= stepThresholdX) { moveFocusRight(); updateKeyFocus(); syncWithParent(); accumulatedX -= stepThresholdX; stepsR++ }
                    while (accumulatedX <= -stepThresholdX) { moveFocusLeft();  updateKeyFocus(); syncWithParent(); accumulatedX += stepThresholdX; stepsL++ }

                    if (stepsR + stepsL > 0) {
                        dbg(
                            "handleDrag MOVE  dx=%.2f accX=%.2f  stepsR=%d stepsL=%d  focus=%s"
                                .format(dx, accumulatedX, stepsR, stepsL, getFocusedKey()?.text ?: "null")
                        )
                    }
                } else {
                    // Not yet a drag—helpful to see small jitter vs slop
                    dbg(
                        "handleDrag MOVE  dx=%.2f total=%.2f (waiting > slop=%d)"
                            .format(dx, totalMove, touchSlop)
                    )
                }

                lastX = x
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dur = System.currentTimeMillis() - touchStartTime
                val totalMove = kotlin.math.abs(lastX - startX)
                val wasTap = !isDragging && totalMove < touchSlop && dur < 300L

                dbg(
                    "handleDrag ${actName(action)} dur=${dur}ms totalMove=%.2f slop=%d " +
                            "isDragging=$isDragging wasTap=$wasTap focus=%s"
                                .format(totalMove, touchSlop, getFocusedKey()?.text ?: "null")
                )

                if (wasTap) {
                    dbg("handleDrag TAP → handleTap() on focus=%s".format(getFocusedKey()?.text ?: "null"))
                    handleTap()
                    updateKeyFocus()
                    syncWithParent()
                }

                isDragging = false
                firstMove = true
                accumulatedX = 0f
                accumulatedY = 0f
            }
        }
    }

    private fun resetDragState() {
        accumulatedX = 0f
        isDragging = false
        firstMove = true
        Log.d("FlingDebug", "Drag state reset")
    }

    fun endDrag() {
        if (isAnchoredMode) return

        val touchDuration = System.currentTimeMillis() - touchStartTime
        val totalMove = abs(lastX - startX)

        val wasTap = !isDragging && touchDuration < clickTimeout && totalMove < touchSlop
        if (wasTap) {
            Log.d("TouchDebug", "Processing as tap (dur=$touchDuration, move=$totalMove < slop=$touchSlop)")
            handleTap()   // clicks the focused key
        }

        // reset state
        isDragging = false
        firstMove = true
        accumulatedX = 0f
    }

    private fun handleTap() {
        Log.d("AnchoringDebug", "isAnchoredMode: $isAnchoredMode")
        if (!isAnchoredMode) {
            //Log.d("TouchDebug", "Processing tap")
            getFocusedKey()?.let { button ->
                //Log.d("TouchDebug", "Tapped key: ${button.text}")
                // Visual feedback
                button.setBackgroundColor(Color.GRAY)
                postDelayed({
                    handleButtonClick(button)
                    updateKeyFocus()
                }, 50)
            }
        }


    }



    private fun getKeyAtPosition(x: Float, y: Float): Button? {
        Log.d("KeyboardDebug", "getKeyAtPosition called with ($x, $y)")

        val keyboard = getKeyboardLayout()
        if (keyboard == null) {
            Log.d("KeyboardDebug", "No keyboard layout found")
            return null
        }

        for (i in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(i) as? LinearLayout
            if (row == null) {
                Log.d("KeyboardDebug", "Row $i is not a LinearLayout")
                continue
            }

            // Get row's position relative to keyboard
            val rowTop = row.top.toFloat()
            val rowBottom = row.bottom.toFloat()

            Log.d("KeyboardDebug", "Checking row $i: top=$rowTop, bottom=$rowBottom")

            // Check if Y is within this row first
            if (y < rowTop || y >= rowBottom) {
                continue
            }

            // Y is in this row, now check X for buttons
            for (j in 0 until row.childCount) {
                val button = row.getChildAt(j) as? Button
                if (button == null || button.visibility != View.VISIBLE) continue

                // Get button position relative to keyboard (not row)
                val buttonLeft = button.left.toFloat()
                val buttonRight = button.right.toFloat()
                val buttonTop = rowTop
                val buttonBottom = rowBottom

                Log.d("KeyboardDebug", """
                Checking button: ${button.text}
                Button bounds: ($buttonLeft, $buttonTop) to ($buttonRight, $buttonBottom)
                Test point: ($x, $y)
            """.trimIndent())

                if (x >= buttonLeft && x <= buttonRight &&
                    y >= buttonTop && y <= buttonBottom) {
                    Log.d("KeyboardDebug", "Found matching button: ${button.text}")
                    return button
                }
            }
        }

        Log.d("KeyboardDebug", "No button found at ($x, $y)")
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            updateKeyFocus()
        }
    }

//    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
//        Log.d("TouchDebug", "CustomKeyboardView: Intercepting - action: ${getActionString(event.action)}")
//        // Intercept all touches to handle them in onTouchEvent
//        return true
//    }
// In CustomKeyboardView.kt (top of class with other fields)
    private var startX = 0f
    private var hoveredKey: Button? = null

    private var isDragging = false
    private var accumulatedX = 0f
    private var accumulatedY = 0f

    private var lastX = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartTime = 0L

    // Increase the step threshold to make keyboard swipe less sensitive
    private val stepThresholdX = 100f
    private val stepThresholdY = 100f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartTime = System.currentTimeMillis()
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY

                // accumulate deltas
                accumulatedX += dx
                accumulatedY += dy
                isDragging = true

                // Decide which axis to step first to avoid diagonal jitter:
                // step the axis with the larger absolute distance first.
                fun stepHorizontal() {
                    val keyboard = getKeyboardLayout()
                    val row = keyboard?.getChildAt(currentRow) as? LinearLayout
                    val visibleButtons = row?.children?.filter { it.visibility == View.VISIBLE }?.toList() ?: emptyList()
                    val maxColumns = visibleButtons.size

                    if (maxColumns > 0) {
                        while (accumulatedX >= stepThresholdX) {
                            currentColumn = (currentColumn + 1).coerceIn(0, maxColumns - 1)
                            updateKeyFocus()
                            syncWithParent()
                            accumulatedX -= stepThresholdX
                        }
                        while (accumulatedX <= -stepThresholdX) {
                            currentColumn = (currentColumn - 1).coerceIn(0, maxColumns - 1)
                            updateKeyFocus()
                            syncWithParent()
                            accumulatedX += stepThresholdX
                        }
                    }
                }

                fun stepVertical() {
                    val keyboardLayout = getKeyboardLayout()
                    val maxRows = keyboardLayout?.childCount ?: 0

                    if (maxRows > 0) {
                        while (accumulatedY >= stepThresholdY) {
                            currentRow = (currentRow + 1).coerceIn(0, maxRows - 1)
                            adjustCurrentColumn() // Adjust column when changing rows
                            updateKeyFocus()
                            syncWithParent()
                            accumulatedY -= stepThresholdY
                        }
                        while (accumulatedY <= -stepThresholdY) {
                            currentRow = (currentRow - 1).coerceIn(0, maxRows - 1)
                            adjustCurrentColumn() // Adjust column when changing rows
                            updateKeyFocus()
                            syncWithParent()
                            accumulatedY += stepThresholdY
                        }
                    }
                }

                if (abs(accumulatedX) > abs(accumulatedY)) {
                    stepHorizontal(); stepVertical()
                } else {
                    stepVertical();   stepHorizontal()
                }

                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                isDragging = false
                accumulatedX = 0f
                accumulatedY = 0f
                return true
            }
        }

        return true
    }
    private fun syncWithParent() {
        var parent = parent
        while (parent != null) {
            if (parent is DualWebViewGroup) {
                parent.syncKeyboardStates()
                break
            }
            parent = parent.parent
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            post {
                findHideButton()
                updateKeyFocus()
            }
        }
    }

}