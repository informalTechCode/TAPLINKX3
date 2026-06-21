package com.TapLinkX3.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.children
import kotlin.math.abs

class CustomKeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
        LinearLayout(context, attrs) {
    private var keys: MutableList<Button> = mutableListOf()
    // Change from var to private var
    private var currentRow = 0
    private var currentColumn = 0

    // Add these properties to track the current position pre-commit
    private var tempRow = 0
    private var tempColumn = 0

    // Dynamic text color support
    private var defaultTextColor: Int = Color.WHITE

    fun setCustomTextColor(color: Int) {
        defaultTextColor = color
        // Refresh all keys with new color
        for (i in 0 until keys.size) {
            keys[i].setTextColor(color)
        }
        updateKeyFocus()
    }

    fun getCustomTextColor(): Int = defaultTextColor

    private var isAnchoredMode = false

    private enum class KeyboardMode {
        LETTERS,
        SYMBOLS;

        fun toggle(): KeyboardMode = if (this == LETTERS) SYMBOLS else LETTERS
    }

    private data class KeyboardLayout(val rows: List<List<String>>, val dynamicKeys: List<String>)

    private val keyboardLayouts =
            mapOf(
                    KeyboardMode.LETTERS to
                            KeyboardLayout(
                                    rows =
                                            listOf(
                                                    listOf(
                                                            "Q",
                                                            "W",
                                                            "E",
                                                            "R",
                                                            "T",
                                                            "Y",
                                                            "U",
                                                            "I",
                                                            "O",
                                                            "P"
                                                    ),
                                                    listOf(
                                                            "A",
                                                            "S",
                                                            "D",
                                                            "F",
                                                            "G",
                                                            "H",
                                                            "J",
                                                            "K",
                                                            "L"
                                                    ),
                                                    listOf(
                                                            "Z",
                                                            "X",
                                                            "C",
                                                            "V",
                                                            "B",
                                                            "N",
                                                            "M",
                                                            ".",
                                                            "/"
                                                    )
                                            ),
                                    dynamicKeys = listOf("@")
                            ),
                    KeyboardMode.SYMBOLS to
                            KeyboardLayout(
                                    rows =
                                            listOf(
                                                    listOf(
                                                            "1",
                                                            "2",
                                                            "3",
                                                            "4",
                                                            "5",
                                                            "6",
                                                            "7",
                                                            "8",
                                                            "9",
                                                            "0"
                                                    ),
                                                    listOf(
                                                            "@",
                                                            "#",
                                                            "$",
                                                            "_",
                                                            "&",
                                                            "-",
                                                            "+",
                                                            "(",
                                                            ")"
                                                    ),
                                                    listOf(
                                                            "*",
                                                            "'",
                                                            ":",
                                                            ";",
                                                            "!",
                                                            "?",
                                                            "<",
                                                            ">",
                                                            ""
                                                    )
                                            ),
                                    dynamicKeys =
                                            listOf(
                                                    "\u25C0"
                                            ) // Left Arrow (Right Arrow can be > in row 3 or handle
                                    // separately)
                                    )
            )

    private val currentLayout: KeyboardLayout
        get() = keyboardLayouts.getValue(currentMode)

    private var currentMode = KeyboardMode.LETTERS

    private var firstMove = true

    companion object {
        private val PUNCTUATION_CHARS = listOf(".", "?", "!")
    }

    private var isUpperCase = true
    private var isCapsLocked = false
    private var lastShiftPressTime = 0L
    private val doubleTapShiftTimeout = 300L
    private var lastEmittedChar: String? = null

    private var isSyncing: Boolean = false

    private var hideButton: Button? = null
    private var micButton: Button? = null
    private var isMicActive = false
    private val clickFeedbackDurationMs = 90L
    private val clickFeedbackColor = Color.WHITE
    private val clickFeedbackTextColor = Color.BLACK
    private var clickedKey: Button? = null
    private var clickFeedbackUntil = 0L

    interface OnKeyboardActionListener {
        fun onKeyPressed(key: String)
        fun onBackspacePressed()
        fun onEnterPressed()
        fun onHideKeyboard()
        fun onClearPressed()
        fun onMoveCursorLeft()
        fun onMoveCursorRight()
        fun onMicrophonePressed()
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

        layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.BOTTOM
                }

        // Inflate layout
        LayoutInflater.from(context).inflate(R.layout.keyboard_layout, this, true)

        post {
            initializeKeys()
            findHideButton()
            findMicButton()
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

    fun updateHover(x: Float, y: Float) {
        val key = getKeyAtPosition(x, y)
        if (hoveredKey != key) {
            hoveredKey = key
            updateKeyFocus()
        }
    }

    fun updateHoverScreen(screenX: Float, screenY: Float, uiScale: Float) {
        if (screenX < 0 || screenY < 0) {
            clearHover()
            return
        }
        val key = getKeyAtScreenPosition(screenX, screenY, uiScale)
        if (hoveredKey != key) {
            hoveredKey = key
            updateKeyFocus()
        }
    }

    fun clearHover() {
        if (hoveredKey != null) {
            hoveredKey = null
            updateKeyFocus()
        }
    }

    fun handleAnchoredTap(x: Float, y: Float) {

        val key = getKeyAtPosition(x, y)
        if (key != null) {
            handleButtonClick(key)
        } else {
            DebugLog.d("KeyboardDebug", "No key found at position")
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ⚡ Bolt: Removed overridden onLayout that was causing unnecessary invalidate() calls.
    // 💡 What: Removed onLayout which contained invalidate() calls on all child views.
    // 🎯 Why: Calling invalidate() inside onLayout forces another redraw pass immediately after layout, creating unnecessary CPU overhead and battery drain. The Android view system already handles invalidation correctly when layout bounds change.

    private fun handleButtonClick(button: Button) {
        val buttonId = button.id
        val buttonLabel = button.text.toString()

        val now = SystemClock.uptimeMillis()
        clickedKey = button
        clickFeedbackUntil = now + clickFeedbackDurationMs
        if (button.isAttachedToWindow) {
            updateKeyFocus()
        }

        postDelayed(
                {
                    if (SystemClock.uptimeMillis() >= clickFeedbackUntil) {
                        clickedKey = null
                        clickFeedbackUntil = 0L
                    }
                    if (button.isAttachedToWindow) {
                        // Only touch the view's drawable state if it's still attached. When the
                        // keyboard hides (as it does after pressing enter in anchored mode) the
                        // buttons are detached, and attempting to manipulate their background
                        // triggers a crash.
                        updateKeyFocus()
                    }

                    when (buttonId) {
                        R.id.btn_caps -> {
                            toggleCase()
                        }
                        R.id.btn_switch -> {
                            toggleKeyboardMode()
                        }
                        R.id.btn_hide -> {
                            listener?.onHideKeyboard()
                            // Don't call updateKeyFocus after hiding - keyboard will be gone
                            return@postDelayed
                        }
                        R.id.btn_backspace -> {
                            listener?.onBackspacePressed()
                            lastEmittedChar = null
                        }
                        R.id.btn_enter -> {
                            listener?.onEnterPressed()
                            // Don't call updateKeyFocus after enter - keyboard might be gone
                            return@postDelayed
                        }
                        R.id.btn_space -> {
                            listener?.onKeyPressed(" ")
                            if (lastEmittedChar in PUNCTUATION_CHARS) {
                                if (!isUpperCase && !isCapsLocked) {
                                    isUpperCase = true
                                    updateKeyboardKeys()
                                    syncWithParent()
                                }
                            }
                            lastEmittedChar = " "
                        }
                        R.id.btn_clear -> {
                            listener?.onClearPressed()
                            lastEmittedChar = null
                        }
                        R.id.btn_mic -> {
                            listener?.onMicrophonePressed()
                        }
                        R.id.button_left_dynamic -> handleDynamicButtonClick(button.id)
                        else -> {
                            // Check for arrow keys mapped to buttons in symbols mode
                            if (buttonLabel == "<") {
                                listener?.onMoveCursorLeft()
                            } else if (buttonLabel == ">") {
                                listener?.onMoveCursorRight()
                            } else {
                                listener?.onKeyPressed(buttonLabel)

                                lastEmittedChar = buttonLabel

                                if (isUpperCase &&
                                                !isCapsLocked &&
                                                currentMode == KeyboardMode.LETTERS
                                ) {
                                    isUpperCase = false
                                    updateKeyboardKeys()
                                    syncWithParent()
                                }
                            }
                        }
                    }

                    // Only update focus if we're in non-anchored mode and still attached
                    if (!isAnchoredMode && isAttachedToWindow) {
                        updateKeyFocus()
                    }
                },
                clickFeedbackDurationMs
        )
    }

    private fun toggleKeyboardMode() {
        currentMode = currentMode.toggle()
        updateKeyboardKeys()
        syncWithParent()
    }

    private fun handleDynamicButtonClick(buttonId: Int) {
        when (currentMode) {
            KeyboardMode.LETTERS -> {
                val index =
                        when (buttonId) {
                            R.id.button_left_dynamic -> 0
                            else -> return
                        }
                currentLayout.dynamicKeys.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                    listener?.onKeyPressed(if (isUpperCase) it.uppercase() else it)
                }
            }
            KeyboardMode.SYMBOLS ->
                    when (buttonId) {
                        R.id.button_left_dynamic -> listener?.onMoveCursorLeft()
                    }
        }
    }

    private fun updateKeyboardKeys() {
        val keyboardLayout = getChildAt(0) as? LinearLayout ?: return
        val layoutConfig = currentLayout

        val rowCount = Math.min(layoutConfig.rows.size, keyboardLayout.childCount)
        for (rowIndex in 0 until rowCount) {
            val rowView = keyboardLayout.getChildAt(rowIndex)
            val rowLayout = rowView as? LinearLayout ?: continue
            val keyRow = layoutConfig.rows[rowIndex]
            var keyIndex = 0

            for (childIndex in 0 until rowLayout.childCount) {
                val child = rowLayout.getChildAt(childIndex)
                val button = child as? Button ?: continue
                if (button.id in specialButtonIds) continue

                val keyText = keyRow.getOrNull(keyIndex).orEmpty()
                keyIndex++

                if (keyText.isEmpty()) {
                    button.visibility = View.GONE
                } else {
                    button.text =
                            if (currentMode == KeyboardMode.LETTERS) {
                                if (isUpperCase) keyText.uppercase() else keyText.lowercase()
                            } else {
                                keyText
                            }
                    button.visibility = View.VISIBLE
                }
            }
        }

        val dynamicRow =
                keyboardLayout.children.elementAtOrNull(layoutConfig.rows.size) as? LinearLayout
        dynamicRow?.let { rowLayout ->
            val leftDynamicButton = rowLayout.findViewById<Button>(R.id.button_left_dynamic)

            val dynamicKeys = layoutConfig.dynamicKeys

            configureDynamicButton(leftDynamicButton, dynamicKeys.getOrNull(0))
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

    // Bolt: Performance optimization. Avoids creating intermediate lists and sequences
    // by manually traversing the children and counting only those that are visible.
    // This reduces garbage collection overhead in frequently called focus methods.
    private fun getVisibleChildCount(row: LinearLayout?): Int {
        row ?: return 0
        var count = 0
        for (i in 0 until row.childCount) {
            if (row.getChildAt(i).visibility == View.VISIBLE) count++
        }
        return count
    }

    // Bolt: Performance optimization. Returns the visible child at the specified index
    // directly without instantiating a new collection of visible views.
    private fun getVisibleChildAt(row: LinearLayout?, index: Int): View? {
        row ?: return null
        var count = 0
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                if (count == index) return child
                count++
            }
        }
        return null
    }

    private fun adjustCurrentColumn() {
        val keyboardLayout = getKeyboardLayout()
        keyboardLayout?.let { layout ->
            val row = layout.getChildAt(currentRow) as? LinearLayout
            val maxColumns = getVisibleChildCount(row)
            if (maxColumns > 0) {
                if (currentColumn >= maxColumns) {
                    currentColumn = maxColumns - 1
                }
                if (currentColumn < 0) {
                    currentColumn = 0
                }
            } else {
                currentColumn = 0
            }
        }
    }

    private fun updateCapsButtonText() {
        val capsButton = findViewById<Button>(R.id.btn_caps)
        capsButton?.let {
            it.text =
                    when {
                        isCapsLocked -> "CAPS"
                        isUpperCase -> "ABC"
                        else -> "abc"
                    }
            if (isCapsLocked) {
                it.setTextColor(Color.CYAN)
            } else {
                it.setTextColor(defaultTextColor)
            }
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
        capsButton?.visibility =
                if (currentMode == KeyboardMode.LETTERS) View.VISIBLE else View.GONE
        capsButton?.invalidate()
    }

    private fun initializeKeys() {
        keys.clear()

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
                                it.setTextColor(defaultTextColor)
                            }
                        }
                    }
                }
            }
        }

        // Set initial caps button text
        keys.find { it.id == R.id.btn_caps }?.let { capsButton ->
            capsButton.text =
                    when {
                        isCapsLocked -> "CAPS"
                        isUpperCase -> "ABC"
                        else -> "abc"
                    }
        }
    }

    private val specialButtonIds =
            setOf(
                    R.id.btn_hide,
                    R.id.btn_space,
                    R.id.btn_backspace,
                    R.id.btn_enter,
                    R.id.btn_switch,
                    R.id.btn_caps,
                    R.id.btn_clear,
                    R.id.button_left_dynamic,
                    R.id.btn_mic
            )
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private fun toggleCase() {
        if (currentMode != KeyboardMode.LETTERS) return

        val now = SystemClock.uptimeMillis()

        if (isCapsLocked) {
            // If locked, single tap turns everything off
            isCapsLocked = false
            isUpperCase = false
        } else {
            // Check for double tap
            if (now - lastShiftPressTime < doubleTapShiftTimeout) {
                // Double tap detected -> Enable Caps Lock
                isCapsLocked = true
                isUpperCase = true
            } else {
                // Single tap -> Toggle Shift
                isUpperCase = !isUpperCase
            }
        }

        lastShiftPressTime = now

        updateKeyboardKeys()
        updateCapsButtonText() // Force update text immediately
        syncWithParent()
    }

    fun updateKeyFocus() {
        // In both modes, only highlight the hovered key (cursor-based)
        // No default "focused" key should be highlighted

        val defaultBackground = Color.DKGRAY
        val hoverBackground = Color.parseColor("#4488FF")
        val now = SystemClock.uptimeMillis()

        for (i in 0 until keys.size) {
            val button = keys[i]
            when {
                button == clickedKey && now < clickFeedbackUntil -> {
                    button.setBackgroundColor(clickFeedbackColor)
                    button.setTextColor(clickFeedbackTextColor)
                }
                button == micButton && isMicActive -> {
                    // Active mic gets priority over hover
                    button.setBackgroundColor(Color.GREEN)
                    button.setTextColor(Color.BLACK)
                }
                button == hoveredKey -> {
                    button.setBackgroundColor(hoverBackground)
                    button.setTextColor(defaultTextColor)
                }
                else -> {
                    button.setBackgroundColor(defaultBackground)
                    button.setTextColor(defaultTextColor)
                }
            }
            button.invalidate()
        }

        // Bolt optimization: Removed requestLayout() here as updating text/background
        // colors does not change bounds, saving expensive layout passes.
        invalidate()

        if (!isSyncing) {
            syncWithParent()
        }
    }

    private fun findHideButton() {
        hideButton = keys.find { it.id == R.id.btn_hide }
        // Just find the button reference but DON'T set focus to it
    }

    private fun findMicButton() {
        // Try to find by ID first
        micButton = keys.find { it.id == R.id.btn_mic }

        // If not found by ID, try checking text (if it uses icon text or similar)
        if (micButton == null) {
            micButton = keys.find { it.text.toString().contains("Mic", ignoreCase = true) }
        }
    }

    fun setMicActive(active: Boolean) {
        if (isMicActive != active) {
            isMicActive = active
            updateKeyFocus()
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
        return getVisibleChildAt(row, currentColumn) as? Button
    }

    private fun moveFocusRight() {
        val keyboard = getKeyboardLayout()
        val row = keyboard?.getChildAt(currentRow) as? LinearLayout
        if (row != null) {
            val maxColumns = getVisibleChildCount(row)
            if (maxColumns > 0) {
                currentColumn = (currentColumn + 1) % maxColumns
                updateKeyFocus()
                syncWithParent()
            }
        }
    }

    private fun moveFocusLeft() {
        val keyboard = getKeyboardLayout()
        val row = keyboard?.getChildAt(currentRow) as? LinearLayout
        if (row != null) {
            val maxColumns = getVisibleChildCount(row)
            if (maxColumns > 0) {
                currentColumn = (currentColumn - 1) % maxColumns
                if (currentColumn < 0) currentColumn += maxColumns
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
            // adjustCurrentColumn() // Adjust column for the new row
            updateKeyFocus()
            syncWithParent()
        }
    }

    private fun moveFocusUp() {
        val keyboardLayout = getKeyboardLayout()
        val maxRows = keyboardLayout?.childCount ?: 0
        if (maxRows > 0) {
            currentRow = (currentRow - 1) % maxRows
            // adjustCurrentColumn() // Adjust column for the new row
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

        if (abs(velocityX) > threshold) {
            if (velocityX > horizontalThreshold) {
                // Strong positive X velocity - move horizontally to the right
                moveFocusRight()
            } else if (velocityX < -horizontalThreshold) {
                // Strong negative X velocity - move horizontally to the left
                moveFocusDown()
            }
        }

        // Force update of key focus
        updateKeyFocus()
    }

    private fun getKeyboardLayout(): LinearLayout? {
        return if (childCount > 0) getChildAt(0) as? LinearLayout else null
    }

    fun setAnchoredMode(anchored: Boolean) {
        isAnchoredMode = anchored
        updateKeyFocus()
    }
    fun performFocusedTap() {
        // In non-anchored mode, usage relies on cursor hover. Only click if hovering a key.
        // In other modes (or if fallback needed), use focusedKey.
        val targetKey = if (!isAnchoredMode) hoveredKey else (hoveredKey ?: getFocusedKey())

        targetKey?.let { button ->
            handleButtonClick(button)
            updateKeyFocus()
            syncWithParent()
        }
    }

    fun handleDrag(x: Float, action: Int) {
        if (isAnchoredMode) {
            return
        }

        // Swipe logic removed as per request - only clicks allowed
        // We still track basic state for click detection if needed, but no movement processing

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
            }
            MotionEvent.ACTION_MOVE -> {
                val totalMove = kotlin.math.abs(x - startX)

                // Just track dragging state, but DO NOT MOVE FOCUS
                if (!isDragging && totalMove > touchSlop) {
                    isDragging = true
                }

                lastX = x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dur = System.currentTimeMillis() - touchStartTime
                val totalMove = kotlin.math.abs(lastX - startX)
                val wasTap = !isDragging && totalMove < touchSlop && dur < 300L

                if (wasTap) {
                    // handleTap()
                    // updateKeyFocus()
                    // syncWithParent()
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
    }

    private fun handleTap() {
        if (!isAnchoredMode) {
            performFocusedTap()
        }
    }

    private val reusableRect = android.graphics.Rect()
    private val reusableLocation = IntArray(2)

    private fun getKeyAtScreenPosition(screenX: Float, screenY: Float, uiScale: Float): Button? {
        val shouldLog = false

        var bestCandidate: Button? = null
        var bestDist = Float.MAX_VALUE

        // Tolerance in screen pixels for fuzzy matching (covers gaps between keys)
        val tolerance = 15f

        // Get the screen location of the CustomKeyboardView itself just once per frame
        // to avoid calling getGlobalVisibleRect repeatedly in the loop.
        val keyboardLayout = getChildAt(0) as? LinearLayout ?: return null
        keyboardLayout.getLocationOnScreen(reusableLocation)
        val kbScreenX = reusableLocation[0].toFloat()
        val kbScreenY = reusableLocation[1].toFloat()

        for (i in 0 until keys.size) {
            val button = keys[i]
            if (button.visibility != View.VISIBLE) continue

            val rowLayout = button.parent as? android.view.ViewGroup ?: continue

            // Calculate absolute screen bounds manually, applying uiScale
            // This assumes rows are direct children of keyboardLayout and buttons are direct children of rows
            val btnLeft = kbScreenX + (rowLayout.x + button.x) * uiScale
            val btnTop = kbScreenY + (rowLayout.y + button.y) * uiScale
            val btnRight = btnLeft + button.width * uiScale
            val btnBottom = btnTop + button.height * uiScale

            // --- PASS 1: Strict Hit (screen space) ---
            if (screenX >= btnLeft && screenX < btnRight && screenY >= btnTop && screenY < btnBottom
            ) {
                return button
            }

            // --- PASS 2: Fuzzy matching for gaps between keys ---
            // Only consider buttons within vertical tolerance (same row priority)
            val dy = kotlin.math.max(0f, kotlin.math.max(btnTop - screenY, screenY - btnBottom))

            if (dy > tolerance) {
                continue
            }

            // Horizontal Distance
            val dx = kotlin.math.max(0f, kotlin.math.max(btnLeft - screenX, screenX - btnRight))

            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            if (dist < tolerance && dist < bestDist) {
                bestDist = dist
                bestCandidate = button
            }
        }

        if (bestCandidate != null) {
            return bestCandidate
        }

        return null
    }

    private fun getKeyAtPosition(x: Float, y: Float): Button? {
        val keyboard = getKeyboardLayout()
        if (keyboard == null) {
            return null
        }

        val kX = x - keyboard.x
        val kY = y - keyboard.y

        for (i in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(i) as? LinearLayout
            if (row == null) {
                continue
            }

            val rX = kX - row.x
            val rY = kY - row.y

            // Strict bounds check for Y (with small tolerance for touch slop if needed, but 0 is
            // safer for hover)
            if (rY < 0 || rY > row.height) continue

            for (j in 0 until row.childCount) {
                val button = row.getChildAt(j) as? Button ?: continue
                if (button.visibility != View.VISIBLE) continue

                // Strict bounds check for X
                // Check if the relative X position is within this button's horizontal bounds
                if (rX >= button.left && rX <= button.right) {
                    return button
                }
            }
        }

        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { updateKeyFocus() }
    }

    private var startX = 0f
    private var hoveredKey: Button? = null

    private var isDragging = false
    private var accumulatedX = 0f
    private var accumulatedY = 0f

    private var lastX = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartTime = 0L

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
                // Swipe logic removed - do nothing on move
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
                clearHover() // Reset hover state when keyboard appears
                findHideButton()
                findMicButton()
                updateKeyFocus()
            }
        }
    }
}
