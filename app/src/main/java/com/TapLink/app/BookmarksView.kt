package com.TapLinkX3.app

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import kotlin.math.max

// BookmarkEntry data class
data class BookmarkEntry(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var isHome: Boolean = false
)

// BookmarkListener interface
interface BookmarkListener {
    fun onBookmarkSelected(url: String)
    fun getCurrentUrl(): String
}


// BookmarkManager class
class BookmarkManager(private val context: Context) {
    private val prefsName = "BookmarkPrefs"
    private val keyBookmarks = "bookmarks"
    private val defaultHomeUrl = "https://www.google.com"

    private var bookmarks: MutableList<BookmarkEntry> = mutableListOf()

    init {
        loadBookmarks()
        if (bookmarks.isEmpty()) {
            bookmarks.add(BookmarkEntry(url = defaultHomeUrl, isHome = true))
            saveBookmarks()
        }
    }

    fun getBookmarks(): List<BookmarkEntry> = bookmarks.toList()



    fun addBookmark(url: String): BookmarkEntry {
        val entry = BookmarkEntry(url = url)
        bookmarks.add(entry)
        saveBookmarks()
        return entry
    }

    fun updateBookmark(id: String, newUrl: String) {
        bookmarks.find { it.id == id }?.let { entry ->
            entry.url = newUrl
            if (entry.isHome && newUrl.isEmpty()) {
                entry.url = defaultHomeUrl
            }
            saveBookmarks()
        }
    }

    fun deleteBookmark(id: String) {
        bookmarks.removeAll { it.id == id && !it.isHome }
        saveBookmarks()
    }

    fun setAsHome(id: String) {
        val index = bookmarks.indexOfFirst { it.id == id }
        if (index != -1) {
            // Update flags
            bookmarks.forEach { it.isHome = false }

            // Move to top
            val entry = bookmarks.removeAt(index)
            entry.isHome = true
            bookmarks.add(0, entry)

            saveBookmarks()
        }
    }

    private fun loadBookmarks() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val bookmarksJson = prefs.getString(keyBookmarks, null)

        if (bookmarksJson != null) {
            try {
                val type = object : TypeToken<List<BookmarkEntry>>() {}.type
                bookmarks = Gson().fromJson(bookmarksJson, type)
            } catch (e: Exception) {
                DebugLog.e("BookmarkManager", "Error loading bookmarks", e)
                bookmarks = mutableListOf()
            }
        }
    }

    fun copyDataFrom(sourceManager: BookmarkManager) {
        bookmarks.clear()
        bookmarks.addAll(sourceManager.getBookmarks())
        saveBookmarks()
    }

    private fun saveBookmarks() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val bookmarksJson = Gson().toJson(bookmarks)
        prefs.edit().putString(keyBookmarks, bookmarksJson).apply()
    }

}

// Add keyboard listener interface
interface BookmarkKeyboardListener {
    fun onShowKeyboardForEdit(text: String)
    fun onShowKeyboardForNew()
    fun onHideKeyboard()
}





// BookmarksView.kt
class BookmarksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private const val TAG = "BookmarksView"
    }

    private enum class ActionType { OPEN, DELETE, SET_HOME, NEW, CLOSE, PREV, NEXT }
    private data class ViewAction(val type: ActionType, val id: String? = null, val url: String? = null)

    private val bookmarkManager = BookmarkManager(context)
    private val bookmarksList = LinearLayout(context)
    private var currentSelection = -1
    private var isAnchoredMode = false

    fun setAnchoredMode(anchored: Boolean) {
        isAnchoredMode = anchored
        if (anchored) {
            currentSelection = -1
        } else {
            if (currentSelection == -1 && bookmarkViews.isNotEmpty()) {
                currentSelection = 0
            }
        }
        updateAllSelections()
    }

    private var bookmarkListener: BookmarkListener? = null
    private var keyboardListener: BookmarkKeyboardListener? = null

    private val bookmarkViews = mutableListOf<View>()


    private val pageSize = 4
    private var currentPage = 0

    private var editingBookmarkId: String? = null

    // Modern color palette - must be declared before editField which uses colorAccent
    private val colorBackground = Color.parseColor("#E80B0F1A")
    private val colorItemDefault = Color.parseColor("#15FFFFFF")
    private val colorItemSelected = Color.parseColor("#3582B1FF")
    private val colorAccent = Color.parseColor("#82B1FF")
    private val colorAccentGreen = Color.parseColor("#69F0AE")
    private val colorTextPrimary = Color.WHITE
    private val colorTextSecondary = Color.parseColor("#B0FFFFFF")
    private val colorDanger = Color.parseColor("#FF5252")

    private val editField = EditText(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(16, 8, 16, 8)
        }
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#30FFFFFF"))
            cornerRadius = 12f
            setStroke(2, colorAccent)
        }
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#80FFFFFF"))
        setHint("URL...")
        setPadding(16, 12, 16, 12)
        visibility = View.GONE
        isSingleLine = true
    }

    // Header view for the dialog
    private val headerView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setPadding(16, 8, 8, 8)
        gravity = Gravity.CENTER_VERTICAL

        val titleText = TextView(context).apply {
            text = "Bookmarks"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            setOnClickListener { closeMenu() }
            // Add a highlight view for hover consistency later if needed
            tag = ViewAction(ActionType.CLOSE)
        }

        addView(titleText)
        addView(closeButton)
        bookmarkViews.add(closeButton)
    }

    private val footerView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 64)
        setPadding(8, 4, 8, 4)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 64
    }

    private fun closeMenu() {
        if (visibility == View.GONE) return
        post { visibility = View.GONE }
    }

    init {
        orientation = VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bookmarks_background)
        elevation = 24f
        setPadding(4, 4, 4, 4)

        // Add header
        addView(headerView)
        
        // Add edit field below header
        addView(editField)

        // Fixed height container for exactly 4 bookmarks
        // 4 bookmarks * (52 height + 8 margin) = 240
        bookmarksList.apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                240
            )
            setPadding(8, 0, 8, 0)
        }

        addView(bookmarksList)
        addView(footerView)
        
        // Ensure touch events are consumed by this view, not propagated to webview
        isClickable = true
        isFocusable = true
    }


    fun getHomeUrl(): String {
        val bookmarks = bookmarkManager.getBookmarks()
        return if (bookmarks.isNotEmpty()) {
            bookmarks[0].url
        } else {
            "https://www.google.com"  // Default fallback
        }
    }

    fun getCurrentEditField(): EditText? {
        return if (editField.visibility == View.VISIBLE) editField else null
    }






    private fun addBookmarkView(entry: BookmarkEntry) {
        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 52).apply {
                setMargins(4, 4, 4, 4)
            }
            gravity = Gravity.CENTER_VERTICAL
            tag = entry.id
            setPadding(8, 0, 8, 0)

            // Set initial modern background
            background = GradientDrawable().apply {
                setColor(colorItemDefault)
                cornerRadius = 12f
            }
        }

        // Home/Set Home Button with modern styling
        val homeButton = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(44, 44).apply {
                setMargins(4, 0, 8, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(if (entry.isHome) Color.parseColor("#2069F0AE") else Color.TRANSPARENT)
                cornerRadius = 8f
            }
            tag = ViewAction(ActionType.SET_HOME, entry.id, entry.url)

            val homeIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_home)
                layoutParams = LinearLayout.LayoutParams(28, 28)
                alpha = if (entry.isHome) 1.0f else 0.4f
                setColorFilter(if (entry.isHome) colorAccentGreen else colorTextPrimary)
            }
            addView(homeIcon)
            // Click handled by handleTap system via ViewAction tag
        }

        val urlView = TextView(context).apply {
            text = entry.url
                .replace("https://", "")
                .replace("http://", "")
                .replace("www.", "")
                .trimEnd('/')
            textSize = 16f
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            tag = ViewAction(ActionType.OPEN, entry.id, entry.url)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        rowLayout.addView(homeButton)
        rowLayout.addView(urlView)
        bookmarkViews.add(homeButton)
        bookmarkViews.add(urlView)

        // Only add delete button if not home
        if (!entry.isHome) {
            val deleteButton = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                    setMargins(4, 0, 4, 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#15FF5252"))
                    cornerRadius = 8f
                }
                tag = ViewAction(ActionType.DELETE, entry.id)

                val closeIcon = TextView(context).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(colorDanger)
                    gravity = Gravity.CENTER
                }
                addView(closeIcon)
                // Click handled by handleTap system via ViewAction tag
            }
            rowLayout.addView(deleteButton)
            bookmarkViews.add(deleteButton)
        }

        bookmarksList.addView(rowLayout)
        DebugLog.d(TAG, "Added bookmark view: ${entry.url}, isHome: ${entry.isHome}")
    }

    private fun handleSetAsHome(bookmarkId: String) {
        bookmarkManager.setAsHome(bookmarkId)

        var currentParent = parent
        while (currentParent != null) {
            if (currentParent is DualWebViewGroup) {
                currentParent.refreshBothBookmarks()
                return
            }
            currentParent = currentParent.parent
        }
        refreshBookmarks()
    }

    private fun handleDeleteBookmark(bookmarkId: String) {
        bookmarkManager.deleteBookmark(bookmarkId)

        var currentParent = parent
        while (currentParent != null) {
            if (currentParent is DualWebViewGroup) {
                currentParent.refreshBothBookmarks()
                return
            }
            currentParent = currentParent.parent
        }

        refreshBookmarks()
    }


    fun getBookmarkManager(): BookmarkManager {
        return bookmarkManager
    }



    fun refreshBookmarks() {
        DebugLog.d(TAG, "refreshBookmarks() called, current page: $currentPage")

        // 1. Clear everything
        bookmarksList.removeAllViews()
        footerView.removeAllViews()
        
        // bookmarkViews should only contain interactive elements on screen
        bookmarkViews.clear()
        
        // Re-add close button from header to bookmarkViews
        headerView.getChildAt(1)?.let { bookmarkViews.add(it) }

        val allBookmarks = bookmarkManager.getBookmarks()
        val totalPages = max(1, (allBookmarks.size + pageSize - 1) / pageSize)
        
        if (currentPage >= totalPages) currentPage = totalPages - 1
        if (currentPage < 0) currentPage = 0

        // 2. Add current page's bookmarks
        val startIdx = currentPage * pageSize
        val endIdx = minOf(startIdx + pageSize, allBookmarks.size)
        
        for (i in startIdx until endIdx) {
            addBookmarkView(allBookmarks[i])
        }
        
        // Fill empty slots to maintain fixed height
        for (i in (endIdx - startIdx) until pageSize) {
            val emptySlot = View(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 52).apply {
                    setMargins(4, 4, 4, 4)
                }
            }
            bookmarksList.addView(emptySlot)
        }

        // 3. Setup Footer
        setupFooter(totalPages)

        // Ensure selection is within bounds
        if (currentSelection != -1) {
            currentSelection = currentSelection.coerceIn(0, bookmarkViews.size - 1)
        }
        updateAllSelections()
        
        // Force measure and layout after content change (like toggle() does)
        measure(
            MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        layout(left, top, left + measuredWidth, top + measuredHeight)
        
        // Force redraw after content change
        invalidate()
        
        // Also trigger parent refresh for mirroring
        post {
            var currentParent = parent
            while (currentParent != null) {
                if (currentParent is DualWebViewGroup) {
                    currentParent.startRefreshing()
                    break
                }
                currentParent = currentParent.parent
            }
        }
    }

    private fun setupFooter(totalPages: Int) {
        val btnPrev = createFooterButton("< Prev") {
            if (currentPage > 0) {
                currentPage--
                refreshBookmarks()
            }
        }
        
        val btnNext = createFooterButton("Next >") {
            if (currentPage < totalPages - 1) {
                currentPage++
                refreshBookmarks()
            }
        }
        
        val btnAdd = createFooterButton("+ Add") {
            startEditWithId("NEW_BOOKMARK", bookmarkListener?.getCurrentUrl() ?: "")
        }.apply {
            (background as GradientDrawable).setColor(Color.parseColor("#6069F0AE"))
        }

        footerView.apply {
            addView(btnPrev, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(btnAdd, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f))
            addView(btnNext, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        
        bookmarkViews.add(btnPrev)
        bookmarkViews.add(btnAdd)
        bookmarkViews.add(btnNext)
        
        // Visually disable buttons if at bounds
        btnPrev.alpha = if (currentPage > 0) 1.0f else 0.5f
        btnNext.alpha = if (currentPage < totalPages - 1) 1.0f else 0.5f
    }

    private fun createFooterButton(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 6, 8, 6)
            background = GradientDrawable().apply {
                setColor(colorItemDefault)
                cornerRadius = 8f
            }
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 4, 4, 4)
            }
            setOnClickListener { onClick() }
            tag = when {
                text.contains("+") -> ViewAction(ActionType.NEW)
                text.contains("< Prev") -> ViewAction(ActionType.PREV)
                text.contains("Next >") -> ViewAction(ActionType.NEXT)
                else -> null
            }
        }
    }

    //this function handles the change in focus of bookmarks rows
    private fun updateAllSelections() {
        // Only proceed if we have views
        if (bookmarkViews.isEmpty()) {
            DebugLog.d(TAG, "No bookmark views to update")
            return
        }

        DebugLog.d(TAG, "Updating all selections, current=$currentSelection, total views=${bookmarkViews.size}")

        // Update all views
        bookmarkViews.forEachIndexed { index, view ->
            val isSelected = index == currentSelection
            updateSelectionBackground(view, isSelected)

            if (isSelected) {
                DebugLog.d(TAG, "View $index is selected")
            }
        }

        if (currentSelection != -1) {
            //calculateAndSetScroll()
        }

    }



    private fun addSpecialButton(text: String, position: Int) {
        val isAddButton = text == "+"
        val isCloseButton = text == "Close"

        val buttonView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                52
            ).apply {
                setMargins(4, if (isAddButton) 8 else 4, 4, 4)
            }

            tag = if (isAddButton) {
                ViewAction(ActionType.NEW)
            } else {
                ViewAction(ActionType.CLOSE)
            }

            background = GradientDrawable().apply {
                when {
                    isAddButton -> {
                        setColor(Color.parseColor("#2069F0AE"))
                        setStroke(1, colorAccentGreen)
                    }
                    isCloseButton -> {
                        setColor(Color.parseColor("#20FFFFFF"))
                    }
                    else -> {
                        setColor(colorItemDefault)
                    }
                }
                cornerRadius = 12f
            }

            // Icon for the button
            val iconView = TextView(context).apply {
                this.text = if (isAddButton) "+" else ""
                textSize = if (isAddButton) 20f else 0f
                setTextColor(colorAccentGreen)
                gravity = Gravity.CENTER
                if (isAddButton) setPadding(0, 0, 8, 0)
            }

            // Label text
            val labelView = TextView(context).apply {
                this.text = if (isAddButton) "Add Bookmark" else "Close"
                textSize = 14f
                setTextColor(if (isAddButton) colorAccentGreen else colorTextSecondary)
                gravity = Gravity.CENTER
            }

            if (isAddButton) addView(iconView)
            addView(labelView)
        }

        bookmarksList.addView(buttonView)
        bookmarkViews.add(buttonView)
        DebugLog.d(TAG, "Added special button: $text at position $position")
    }

    private fun updateSelectionBackground(view: View, isSelected: Boolean) {
        val paddingLeft = view.paddingLeft
        val paddingTop = view.paddingTop
        val paddingRight = view.paddingRight
        val paddingBottom = view.paddingBottom

        view.background = GradientDrawable().apply {
            if (isSelected) {
                setColor(colorItemSelected)
                setStroke(2, colorAccent)
            } else {
                setColor(colorItemDefault)
                setStroke(0, Color.TRANSPARENT)
            }
            cornerRadius = 12f
        }

        view.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
    }



    fun handleFling(isForward: Boolean) {
        // Safety check - don't handle fling if menu isn't visible
        if (visibility != View.VISIBLE || bookmarkViews.isEmpty()) {
            DebugLog.d(TAG, "Ignoring fling - menu not visible or no views")
            return
        }

        val oldSelection = currentSelection
        currentSelection = when {
            isForward -> (currentSelection + 1) % bookmarkViews.size
            currentSelection > 0 -> currentSelection - 1
            else -> bookmarkViews.size - 1
        }

        DebugLog.d(TAG, "Fling: forward=$isForward, old=$oldSelection, new=$currentSelection")

        try {
            updateAllSelections()
        } catch (e: Exception) {
            DebugLog.e(TAG, "Error during fling handling", e)
            // Reset selection to safe value
            currentSelection = 0
        }
    }

    private val hitTolerance = 0f

    private fun findSelectionIndexAt(localX: Float, localY: Float): Int {
        // Check header (Close button)
        val closeBtn = headerView.getChildAt(1)
        if (closeBtn != null && isOverView(closeBtn, headerView, localX, localY)) {
            return bookmarkViews.indexOf(closeBtn)
        }

        // Check footer
        for (i in 0 until footerView.childCount) {
            val btn = footerView.getChildAt(i)
            if (isOverView(btn, footerView, localX, localY)) {
                return bookmarkViews.indexOf(btn)
            }
        }

        // Check bookmarks list
        if (localX >= bookmarksList.left && localX <= bookmarksList.right &&
            localY >= bookmarksList.top && localY <= bookmarksList.bottom) {

            val relX = localX - bookmarksList.left
            val relY = localY - bookmarksList.top

            for (i in 0 until bookmarksList.childCount) {
                val child = bookmarksList.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue

                if (relY >= child.top && relY <= child.bottom) {
                    if (child is LinearLayout) {
                        val rowRelX = relX - child.left

                        for (j in 0 until child.childCount) {
                            val innerView = child.getChildAt(j)
                            if (rowRelX >= innerView.left - hitTolerance && rowRelX <= innerView.right + hitTolerance) {
                                return bookmarkViews.indexOf(innerView)
                            }
                        }
                    } else {
                        // Empty slot
                    }
                }
            }
        }
        return -1
    }

    private fun isOverView(view: View, parent: ViewGroup, localX: Float, localY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val vx = localX - parent.left - view.left
        val vy = localY - parent.top - view.top
        return vx >= -hitTolerance && vx <= view.width + hitTolerance &&
               vy >= -hitTolerance && vy <= view.height + hitTolerance
    }

    fun updateHover(localX: Float, localY: Float): Boolean {
        // Basic bounds check
        if (localX < 0 || localX > width || localY < 0 || localY > height) {
            val oldSelection = currentSelection
            currentSelection = -1
            if (oldSelection != -1) {
                updateAllSelections()
            }
            return false
        }

        val index = findSelectionIndexAt(localX, localY)
        if (index != currentSelection) {
            currentSelection = index
            updateAllSelections()
        }
        
        // Return true if we're over the bookmarks window at all
        return true
    }

    fun handleAnchoredTap(localX: Float, localY: Float): Boolean {
        // Always consume taps within the window bounds to prevent propagation
        if (localX < 0 || localX > width || localY < 0 || localY > height) {
            return false // Outside window - don't consume
        }
        
        if (editField.visibility == View.VISIBLE) {
            if (localX >= editField.left && localX <= editField.right &&
                localY >= editField.top && localY <= editField.bottom) {
                return true
            }
        }

        val index = findSelectionIndexAt(localX, localY)
        if (index != -1) {
            currentSelection = index
            updateAllSelections()
            handleTap()
        }
        // Always return true to consume the tap and prevent propagation to webpage
        return true
    }

    private var scrollResidue = 0f

    // Anchored mode: handle vertical swipe (scrolling logic removed as we use pagination)
    @Suppress("UNUSED_PARAMETER")
    fun handleAnchoredSwipe(_verticalDelta: Float) {
        // No-op for now as we use pagination. 
        // We could potentially use this to switch pages.
    }

    // Anchored mode: handle fling
    @Suppress("UNUSED_PARAMETER")
    fun handleAnchoredFling(_velocity: Float) {
        // No-op for now
    }

    // Non-anchored mode: drag handling (similar to CustomKeyboardView)
    private var isDragging = false
    private var startX = 0f
    private var lastX = 0f
    private var touchStartTime = 0L
    private var accumulatedX = 0f
    private val stepThresholdX = 100f
    private val touchSlop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }

    fun handleDrag(x: Float, action: Int) {
        if (isAnchoredMode) {
            return
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                lastX = x
                touchStartTime = System.currentTimeMillis()
                isDragging = false
                accumulatedX = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                val totalMove = kotlin.math.abs(x - startX)

                // Swipe logic removed: only track drag state to prevent accidental taps
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
                    performFocusedTap()
                }

                isDragging = false
                accumulatedX = 0f
            }
        }
    }

    fun performFocusedTap() {
        if (!isAnchoredMode && currentSelection >= 0) {
            handleTap()
        }
    }

    fun handleTap(): Boolean {
        if (currentSelection !in bookmarkViews.indices) {
            DebugLog.e(TAG, "Invalid selection index: $currentSelection")
            return false
        }

        val view = bookmarkViews[currentSelection]
        val action = view.tag as? ViewAction

        if (action == null) {
            DebugLog.e(TAG, "No action tag found for view at $currentSelection")
            return false
        }

        DebugLog.d(TAG, "Handling tap for action: $action")

        return when (action.type) {
            ActionType.SET_HOME -> {
                action.id?.let { handleSetAsHome(it) }
                true
            }
            ActionType.OPEN -> {
                action.url?.let { bookmarkListener?.onBookmarkSelected(it) }
                closeMenu()
                true
            }
            ActionType.DELETE -> {
                action.id?.let { handleDeleteBookmark(it) }
                true
            }
            ActionType.NEW -> {
                startEditWithId("NEW_BOOKMARK", bookmarkListener?.getCurrentUrl() ?: "")
                keyboardListener?.onShowKeyboardForNew()
                true
            }
            ActionType.CLOSE -> {
                closeMenu()
                true
            }
            ActionType.PREV -> {
                if (currentPage > 0) {
                    currentPage--
                    refreshBookmarks()
                }
                true
            }
            ActionType.NEXT -> {
                val allBookmarks = bookmarkManager.getBookmarks()
                val totalPages = (allBookmarks.size + pageSize - 1) / pageSize
                if (currentPage < totalPages - 1) {
                    currentPage++
                    refreshBookmarks()
                }
                true
            }
        }
    }

    fun toggle() {
        if (visibility == View.VISIBLE) {
            DebugLog.d(TAG, "Hiding bookmarks menu")
            visibility = View.GONE
        } else {
            DebugLog.d(TAG, "Showing bookmarks menu")
            // First refresh the bookmarks to create the views
            refreshBookmarks()

            // Force layout measurement before making visible
            measure(
                MeasureSpec.makeMeasureSpec(420, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            layout(left, top, left + measuredWidth, top + measuredHeight)

            // Make visible
            visibility = View.VISIBLE

            // Set initial selection based on mode
            if (isAnchoredMode) {
                currentSelection = -1
            } else if (bookmarkViews.isNotEmpty()) {
                currentSelection = 0
            }
            updateAllSelections()
        }
    }

    fun isEditing(): Boolean {
        // Check if parent DualWebViewGroup's edit field is actually visible
        var currentParent = parent
        while (currentParent != null) {
            if (currentParent is DualWebViewGroup) {
                // Check if urlEditText is visible, not just the flag
                val isEditing = currentParent.urlEditText.visibility == View.VISIBLE
                DebugLog.d("BookmarksDebug", "isEditing() via parent urlEditText visibility: $isEditing")
                return isEditing
            }
            currentParent = currentParent.parent
        }
        // Fallback to internal field check
        val isVisible = editField.visibility == View.VISIBLE
        DebugLog.d("BookmarksDebug", "isEditing() fallback, editField visibility: $isVisible")
        return isVisible
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(440, MeasureSpec.AT_MOST)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (visibility == View.VISIBLE) {

            bookmarksList.measure(
                MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
    }



    fun handleDoubleTap(): Boolean {
        DebugLog.d(TAG, "handleDoubleTap() called. Current selection: $currentSelection")

        val bookmarks = bookmarkManager.getBookmarks()
        if (currentSelection < bookmarks.size) {
            val bookmark = bookmarks[currentSelection]
            DebugLog.d(TAG, "handleDoubleTap(): About to edit bookmark ${bookmark.id} with URL: ${bookmark.url}")

            // Ensure keyboard listener exists and is called
            val listener = keyboardListener
            if (listener == null) {
                DebugLog.e(TAG, "No keyboard listener set!")
                return false
            }

            // Start editing mode
            startEditWithId(bookmark.id, bookmark.url)

            // Explicitly request keyboard
            DebugLog.d(TAG, "Requesting keyboard for edit with text: ${bookmark.url}")
            listener.onShowKeyboardForEdit(bookmark.url)

            return true
        }
        return false
    }

    fun logStackTrace(tag: String, message: String) {
        DebugLog.d(tag, "$message\n" + Log.getStackTraceString(Throwable()))
    }

    fun startEditWithId(bookmarkId: String?, currentUrl: String) {
        DebugLog.d(TAG, "startEditWithId local called with id: $bookmarkId, url: $currentUrl")
        editingBookmarkId = bookmarkId

        // Find parent DualWebViewGroup and use its shared edit field
        var currentParent = parent
        while (currentParent != null) {
            if (currentParent is DualWebViewGroup) {
                currentParent.showEditField(currentUrl)
                return
            }
            currentParent = currentParent.parent
        }
        
        // Fallback: use internal field if parent not found (shouldn't happen)
        DebugLog.w(TAG, "DualWebViewGroup parent not found, using internal editField")
        editField.apply {
            setText(currentUrl)
            visibility = View.VISIBLE
            requestFocus()
            setSelection(currentUrl.length)
        }
        keyboardListener?.onShowKeyboardForEdit(currentUrl)
    }

    // Method to end editing
    fun endEdit() {
        DebugLog.d(TAG, "endEdit called")
        editingBookmarkId = null
        editField.visibility = View.GONE

        keyboardListener?.onHideKeyboard()
    }



    // Method to handle visibility changes
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.GONE) {
            currentSelection = 0  // Reset to home when hiding
        }
    }

    fun onEnterPressed() {
        DebugLog.d(TAG, "onEnterPressed - starting")
        // Get parent DualWebViewGroup
        var dualWebViewGroup: DualWebViewGroup? = null
        var currentParent = parent
        while (currentParent != null) {
            if (currentParent is DualWebViewGroup) {
                dualWebViewGroup = currentParent
                break
            }
            currentParent = currentParent.parent
        }

        // Check if we're in bookmark editing mode via the parent
        if (dualWebViewGroup?.isBookmarkEditing() == true) {
            val newUrl = dualWebViewGroup.getCurrentLinkText()
            val bookmarkId = editingBookmarkId

            DebugLog.d(TAG, "Processing enter - bookmarkId: $bookmarkId, newUrl: $newUrl")

            // Handle new bookmark
            if (bookmarkId == "NEW_BOOKMARK" || bookmarkId == null) {
                val urlToAdd = newUrl.ifEmpty {
                    bookmarkListener?.getCurrentUrl() ?: "https://www.google.com"
                }

                if (urlToAdd.isNotEmpty()) {
                    DebugLog.d(TAG, "Adding new bookmark with URL: $urlToAdd")
                    bookmarkManager.addBookmark(urlToAdd)
                }
                endEdit()
                dualWebViewGroup.hideBookmarkEditing()
                refreshBookmarks()
                dualWebViewGroup.refreshBothBookmarks()
                return
            }

            // Handle existing bookmark
            val bookmarks = bookmarkManager.getBookmarks()
            val bookmark = bookmarks.find { it.id == bookmarkId }

            if (bookmark != null) {
                if (newUrl.isEmpty() && !bookmark.isHome) {
                    bookmarkManager.deleteBookmark(bookmarkId)
                } else if (bookmark.isHome && newUrl.isEmpty()) {
                    bookmarkManager.updateBookmark(bookmarkId, "https://www.google.com")
                } else {
                    bookmarkManager.updateBookmark(bookmarkId, newUrl)
                }
            }

            endEdit()
            dualWebViewGroup.hideBookmarkEditing()
            refreshBookmarks()
        }
    }


    @SuppressLint("SetTextI18n")
    fun handleKeyboardInput(text: String) {
        if (editField.visibility == View.VISIBLE) {
            val currentText = editField.text.toString()
            val start = editField.selectionStart
            val end = editField.selectionEnd

            when (text) {
                "backspace" -> {
                    if (start > 0 && start == end) {
                        // Delete character before cursor
                        val newText = currentText.substring(0, start - 1) +
                                currentText.substring(end)
                        editField.setText(newText)
                        editField.setSelection(start - 1)
                    } else if (start != end) {
                        // Delete selected text
                        val newText = currentText.substring(0, start) +
                                currentText.substring(end)
                        editField.setText(newText)
                        editField.setSelection(start)
                    }
                }
                "clear" -> {
                    editField.setText("")
                    editField.setSelection(0)
                }
                else -> {
                    // Regular character input
                    val newText = currentText.substring(0, start) + text +
                            currentText.substring(end)
                    editField.setText(newText)
                    editField.setSelection(start + text.length)
                }
            }

            // Log the edit field state for debugging
            DebugLog.d(TAG, """
            Edit field state:
            Text: ${editField.text}
            Selection: ${editField.selectionStart}-${editField.selectionEnd}
            Visible: ${editField.visibility == View.VISIBLE}
        """.trimIndent())
        }
    }




    // Setter methods for listeners
    fun setBookmarkListener(listener: BookmarkListener) {
        bookmarkListener = listener
    }

    fun setKeyboardListener(listener: BookmarkKeyboardListener) {
        DebugLog.d(TAG, "Setting keyboard listener: $listener")
        keyboardListener = listener
    }

}
