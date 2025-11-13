package com.TapLink.app

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    private fun loadBookmarks() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val bookmarksJson = prefs.getString(keyBookmarks, null)

        if (bookmarksJson != null) {
            try {
                val type = object : TypeToken<List<BookmarkEntry>>() {}.type
                bookmarks = Gson().fromJson(bookmarksJson, type)
            } catch (e: Exception) {
                Log.e("BookmarkManager", "Error loading bookmarks", e)
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
}

interface BookmarkStateListener {
    fun onSelectionChanged(position: Int)
    fun onVisibilityChanged(isVisible: Boolean)
    fun onEditModeChanged(isEditing: Boolean, text: String?)
}




// BookmarksView.kt
class BookmarksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private const val TAG = "BookmarksView"
    }

    private val bookmarkManager = BookmarkManager(context)
    private val bookmarksList = LinearLayout(context)
    private var currentSelection = 0

    private var stateListener: BookmarkStateListener? = null

    private var bookmarkListener: BookmarkListener? = null
    private var keyboardListener: BookmarkKeyboardListener? = null

    private val bookmarkViews = mutableListOf<TextView>()

    private var _keyboardListener: BookmarkKeyboardListener? = null

    private val scrollContainer = ScrollView(context)

    private var editingBookmarkId: String? = null
    private val editField = EditText(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(8, 8, 8, 8)
        }
        setBackgroundColor(Color.parseColor("#303030"))
        setTextColor(Color.WHITE)
        visibility = View.GONE
    }



    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#202020"))
        elevation = 16f

        scrollContainer.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                320
            )
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.parseColor("#202020"))  // Add explicit background
        }

        bookmarksList.apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#202020"))  // Add explicit background
        }

        scrollContainer.addView(bookmarksList)
        addView(scrollContainer)
        addView(editField)
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



    private fun calculateAndSetScroll() {
        if (currentSelection < 0 || currentSelection >= bookmarkViews.size) return

        val selectedView = bookmarkViews[currentSelection]

        post {
            // Get the visible boundaries
            val containerHeight = scrollContainer.height

            // Calculate the current position of the selected view
            val viewTop = selectedView.top
            val viewHeight = selectedView.height

            // Calculate how much room we want at the bottom (e.g., 2 items worth of space)
            val bottomPadding = viewHeight * 2

            // Calculate scroll position that would put the selected item with proper bottom padding
            val targetScroll = max(0, viewTop - (containerHeight - bottomPadding))

            Log.d("BookmarksScroll", """
            Scroll Calculation:
            Container height: $containerHeight
            View top: $viewTop
            View height: $viewHeight
            Bottom padding: $bottomPadding
            Target scroll: $targetScroll
        """.trimIndent())

            scrollContainer.smoothScrollTo(0, targetScroll)

            // Ensure view hierarchy is updated
            requestLayout()
            invalidate()
        }
    }



    private fun addBookmarkView(entry: BookmarkEntry) {
        val bookmarkView = TextView(context).apply {
            // Set explicit text appearance
            text = entry.url
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)

            // Set fixed height and margins
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                48
            ).apply {
                setMargins(4, 4, 4, 4)
            }

            // Set initial background
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#303030"))
                cornerRadius = 4f
            }

            // Set home icon if needed
            if (entry.isHome) {
                setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_home, 0, 0, 0
                )
                compoundDrawablePadding = 16
            }
        }

        // Add to lists and update
        bookmarksList.addView(bookmarkView)
        bookmarkViews.add(bookmarkView)
        Log.d(TAG, "Added bookmark view: ${entry.url}, isHome: ${entry.isHome}")
    }


    fun getBookmarkManager(): BookmarkManager {
        return bookmarkManager
    }



    fun refreshBookmarks() {
        Log.d(TAG, "refreshBookmarks() called")

        bookmarksList.removeAllViews()
        bookmarkViews.clear()

        val bookmarks = bookmarkManager.getBookmarks()
        Log.d(TAG, "Found ${bookmarks.size} bookmarks")

        // Add bookmarks in order (0 to n-1)
        bookmarks.forEachIndexed { index, entry ->
            addBookmarkView(entry)
            Log.d(TAG, "Added bookmark ${index}: ${entry.url}, isHome: ${entry.isHome}")
        }

        // Add "+" button at index n
        addSpecialButton("+", bookmarks.size)
        Log.d(TAG, "Added + button at index ${bookmarks.size}")

        // Add "Close" button at index n+1
        addSpecialButton("Close", bookmarks.size + 1)
        Log.d(TAG, "Added close button at index ${bookmarks.size + 1}")

        // Ensure selection is within bounds
        currentSelection = currentSelection.coerceIn(0, bookmarkViews.size - 1)
        updateAllSelections()
    }

    //this function handles the change in focus of bookmarks rows
    private fun updateAllSelections() {
        // Only proceed if we have views
        if (bookmarkViews.isEmpty()) {
            Log.d(TAG, "No bookmark views to update")
            return
        }

        Log.d(TAG, "Updating all selections, current=$currentSelection, total views=${bookmarkViews.size}")

        // Ensure currentSelection is valid
        currentSelection = currentSelection.coerceIn(0, bookmarkViews.size - 1)

        // Update all views
        bookmarkViews.forEachIndexed { index, view ->
            val isSelected = index == currentSelection
            updateSelectionBackground(view, isSelected)

            if (isSelected) {
                Log.d(TAG, "View $index is selected")
            }
        }

        // Ensure selected view is visible
        calculateAndSetScroll()

        // Notify listeners
        stateListener?.onSelectionChanged(currentSelection)
    }



    private fun addSpecialButton(text: String, position: Int) {
        val buttonView = TextView(context).apply {
            this.text = text
            textSize = if (text == "+") 24f else 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                48
            ).apply {
                setMargins(4, 4, 4, 4)
            }

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#303030"))
                cornerRadius = 4f
            }
        }

        bookmarksList.addView(buttonView)
        bookmarkViews.add(buttonView)
        Log.d(TAG, "Added special button: $text at position $position")
    }

    private fun updateSelectionBackground(view: View, isSelected: Boolean) {
        view.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                48  // Fixed height for each item
            ).apply {
                setMargins(4, 4, 4, 4)
            }

            background = GradientDrawable().apply {
                setColor(if (isSelected) {
                    Color.parseColor("#0066cc")
                } else {
                    Color.parseColor("#303030")
                })
                cornerRadius = 4f
            }
            setPadding(16, 12, 16, 12)
        }
    }



    fun handleFling(isForward: Boolean) {
        // Safety check - don't handle fling if menu isn't visible
        if (visibility != View.VISIBLE || bookmarkViews.isEmpty()) {
            Log.d(TAG, "Ignoring fling - menu not visible or no views")
            return
        }

        val oldSelection = currentSelection
        currentSelection = when {
            isForward -> (currentSelection + 1) % bookmarkViews.size
            currentSelection > 0 -> currentSelection - 1
            else -> bookmarkViews.size - 1
        }

        Log.d(TAG, "Fling: forward=$isForward, old=$oldSelection, new=$currentSelection")

        try {
            updateAllSelections()
        } catch (e: Exception) {
            Log.e(TAG, "Error during fling handling", e)
            // Reset selection to safe value
            currentSelection = 0
        }
    }

    fun handleTap(): Boolean {
        val bookmarks = bookmarkManager.getBookmarks()
        Log.d(TAG, """
        Tap Debug:
        Current Selection: $currentSelection
        Total Bookmarks: ${bookmarks.size}
        Bookmark List: ${bookmarks.map { it.url }}
        Bookmark Views Size: ${bookmarkViews.size}
        ---
        Visual Layout:
        0: Home
        ${bookmarks.drop(1).mapIndexed { i, b -> "${i+1}: ${b.url}" }.joinToString("\n")}
        ${bookmarks.size}: +
        ${bookmarks.size + 1}: Close
        ---
        Attempting action for selection: $currentSelection
    """.trimIndent())

        return when (currentSelection) {
            in bookmarks.indices -> {
                val selectedUrl = bookmarks[currentSelection].url
                Log.d(TAG, "Loading URL from bookmark: $selectedUrl")
                bookmarkListener?.let { listener ->
                    listener.onBookmarkSelected(selectedUrl)
                    Log.d(TAG, "Bookmark listener called successfully")
                } ?: Log.e(TAG, "Bookmark listener is null!")
                visibility = View.GONE
                true
            }
            bookmarks.size -> {
                Log.d(TAG, "New bookmark at index $currentSelection")
                startEditWithId("NEW_BOOKMARK", bookmarkListener?.getCurrentUrl() ?: "")
                keyboardListener?.onShowKeyboardForNew()
                true
            }
            bookmarks.size + 1 -> {
                Log.d(TAG, "Close at index $currentSelection")
                visibility = View.GONE
                true
            }
            else -> {
                Log.e(TAG, "Invalid selection index: $currentSelection")
                false
            }
        }
    }

    fun toggle() {
        if (visibility == View.VISIBLE) {
            Log.d(TAG, "Hiding bookmarks menu")
            visibility = View.GONE
        } else {
            Log.d(TAG, "Showing bookmarks menu")
            // First refresh the bookmarks to create the views
            refreshBookmarks()

            // Force layout measurement before making visible
            measure(
                MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            layout(left, top, left + measuredWidth, top + measuredHeight)

            // Make visible
            visibility = View.VISIBLE

            // Set initial selection
            if (bookmarkViews.isNotEmpty()) {
                currentSelection = 0
                updateAllSelections()
            }
        }
    }

    fun isEditing(): Boolean {
        val isVisible = editField.visibility == View.VISIBLE
        Log.d("BookmarksDebug", "isEditing() called, editField visibility: $isVisible")
        return isVisible
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(400, MeasureSpec.AT_MOST)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (visibility == View.VISIBLE) {
            scrollContainer.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY)
            )

            bookmarksList.measure(
                MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
    }



    fun handleDoubleTap(): Boolean {
        Log.d(TAG, "handleDoubleTap() called. Current selection: $currentSelection")

        val bookmarks = bookmarkManager.getBookmarks()
        if (currentSelection < bookmarks.size) {
            val bookmark = bookmarks[currentSelection]
            Log.d(TAG, "handleDoubleTap(): About to edit bookmark ${bookmark.id} with URL: ${bookmark.url}")

            // Ensure keyboard listener exists and is called
            val listener = _keyboardListener
            if (listener == null) {
                Log.e(TAG, "No keyboard listener set!")
                return false
            }

            // Start editing mode
            startEditWithId(bookmark.id, bookmark.url)

            // Explicitly request keyboard
            Log.d(TAG, "Requesting keyboard for edit with text: ${bookmark.url}")
            listener.onShowKeyboardForEdit(bookmark.url)

            return true
        }
        return false
    }

    fun logStackTrace(tag: String, message: String) {
        Log.d(tag, "$message\n" + Log.getStackTraceString(Throwable()))
    }

    fun startEditWithId(bookmarkId: String?, currentUrl: String) {
        Log.d(TAG, "startEditWithId called with id: $bookmarkId, url: $currentUrl")
        editingBookmarkId = bookmarkId

        // Find DualWebViewGroup by traversing up the view hierarchy
        var currentParent = parent
        while (currentParent != null) {
            if (currentParent is DualWebViewGroup) {
                Log.d(TAG, "Found DualWebViewGroup, showing edit field")
                currentParent.showEditField(currentUrl)
                currentParent.urlEditText.setSelection(currentUrl.length)
                break
            }
            currentParent = currentParent.parent
        }
        if (currentParent == null) {
            Log.e(TAG, "Could not find DualWebViewGroup in parent hierarchy!")
        }

        Log.d(TAG, "Calling keyboard listener: ${_keyboardListener != null}")
        keyboardListener?.onShowKeyboardForEdit(currentUrl)
    }

    // Method to end editing
    fun endEdit() {
        Log.d(TAG, "endEdit called")
        editingBookmarkId = null

        var dualWebViewGroup: DualWebViewGroup? = null
        var currentParent = parent
        while (currentParent != null) {
            if (currentParent is DualWebViewGroup) {
                dualWebViewGroup = currentParent
                break
            }
            currentParent = currentParent.parent
        }

        dualWebViewGroup?.apply {
            hideLinkEditing()
            // Make sure keyboard is hidden
            keyboardListener?.onHideKeyboard()
        }
    }



    // Method to handle visibility changes
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.GONE) {
            currentSelection = 0  // Reset to home when hiding
        }
        stateListener?.onVisibilityChanged(visibility == View.VISIBLE)
    }

    fun onEnterPressed() {
        Log.d(TAG, "onEnterPressed - starting")
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

        if (dualWebViewGroup != null) {
            val newUrl = dualWebViewGroup.getCurrentEditText()
            val bookmarkId = editingBookmarkId

            Log.d(TAG, "Processing enter - bookmarkId: $bookmarkId, newUrl: $newUrl")

            // Handle new bookmark
            if (bookmarkId == "NEW_BOOKMARK" || bookmarkId == null) {
                val urlToAdd = newUrl.ifEmpty {
                    bookmarkListener?.getCurrentUrl() ?: "https://www.google.com"
                }

                if (urlToAdd.isNotEmpty()) {
                    Log.d(TAG, "Adding new bookmark with URL: $urlToAdd")
                    bookmarkManager.addBookmark(urlToAdd)
                }
                endEdit()

                // Refresh both bookmarks views
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
            dualWebViewGroup.refreshBothBookmarks()
        } else {
            Log.e(TAG, "Could not find DualWebViewGroup when handling enter!")
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
            Log.d(TAG, """
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
        Log.d(TAG, "Setting keyboard listener: $listener")
        _keyboardListener = listener
    }

}