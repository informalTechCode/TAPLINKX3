package com.TapLinkX3.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONObject

class ChatView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
        LinearLayout(context, attrs) {

    companion object {
        private const val TAG = "ChatView"
    }

    private val titleText =
            TextView(context).apply {
                text = "TapLink AI"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }

    private val closeButton =
            FontIconView(context).apply {
                setText(R.string.fa_xmark)
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(16, 8, 16, 8)
                setBackgroundResource(R.drawable.nav_button_background)
                isClickable = true
                isFocusable = true
                setOnClickListener { closeMenu() }
            }

    private val headerView =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                setPadding(16, 8, 8, 8)
                gravity = Gravity.CENTER_VERTICAL

                addView(titleText)
                addView(closeButton)
            }

    var keyboardListener: DualWebViewGroup.KeyboardListener? = null

    private inner class ChatInputBridge {
        @JavascriptInterface
        fun onInputFocus() {
            post { keyboardListener?.onShowKeyboard() }
        }
    }

    val webView =
            WebView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                setBackgroundColor(Color.TRANSPARENT)

                // Inject the GroqBridge
                addJavascriptInterface(GroqInterface(context, this), "GroqBridge")
                addJavascriptInterface(ChatInputBridge(), "ChatInputBridge")

                webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                injectInputFocusHook()
                            }
                        }

                // Load the dedicated clean chat interface
                loadUrl("file:///android_asset/clean_chat.html")
            }

    init {
        orientation = VERTICAL
        // Use same background style as Bookmarks
        background = ContextCompat.getDrawable(context, R.drawable.bookmarks_background)
        elevation = 24f
        setPadding(4, 4, 4, 4)

        addView(headerView)
        addView(webView)

        // Ensure touch events are consumed
        isClickable = true
        isFocusable = true
    }

    fun disableSystemKeyboard() {
        try {
            val method =
                    WebView::class.java.getMethod(
                            "setShowSoftInputOnFocus",
                            Boolean::class.javaPrimitiveType ?: Boolean::class.java
                    )
            method.invoke(webView, false)
        } catch (e: Exception) {
            // Ignore for older versions
        }
    }

    fun closeMenu() {
        if (visibility == View.GONE) return
        visibility = View.GONE
    }

    // For manual touch handling if needed (similar to bookmarks)
    fun handleAnchoredTap(localX: Float, localY: Float): Boolean {
        // Just consume plain taps if within bounds
        if (localX >= 0 && localX <= width && localY >= 0 && localY <= height) {
            // Check if tapped header close button
            if (isOverView(closeButton, headerView, localX, localY)) {
                closeMenu()
                return true
            }
            // Pass taps inside the WebView to it directly for cursor-based clicks.
            if (localX >= webView.left &&
                            localX <= webView.right &&
                            localY >= webView.top &&
                            localY <= webView.bottom
            ) {
                dispatchTapToWebView(localX - webView.left, localY - webView.top)
                return true
            }

            return true
        }
        return false
    }

    private fun isOverView(view: View, parent: ViewGroup, localX: Float, localY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val vx = localX - parent.left - view.left
        val vy = localY - parent.top - view.top
        return vx >= 0 && vx <= view.width && vy >= 0 && vy <= view.height
    }

    fun updateHover(screenX: Float, screenY: Float): Boolean {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = screenX - loc[0]
        val localY = screenY - loc[1]

        return updateHoverLocal(localX, localY)
    }

    fun updateHoverLocal(localX: Float, localY: Float): Boolean {
        if (localX < 0 || localX > width || localY < 0 || localY > height) {
            closeButton.isHovered = false
            updateWebHover(-1f, -1f, false)
            return false
        }

        closeButton.isHovered = isOverView(closeButton, headerView, localX, localY)
        if (localX >= webView.left &&
                        localX <= webView.right &&
                        localY >= webView.top &&
                        localY <= webView.bottom
        ) {
            val webLocalX = localX - webView.left
            val webLocalY = localY - webView.top
            updateWebHover(webLocalX, webLocalY, true)
        } else {
            updateWebHover(-1f, -1f, false)
        }
        return true
    }

    private var lastWebHoverAt = 0L
    private var lastHoverId: String? = null

    private fun updateWebHover(x: Float, y: Float, isInside: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastWebHoverAt < 50) return
        lastWebHoverAt = now

        val jsX = if (isInside) x else -1f
        val jsY = if (isInside) y else -1f

        webView.evaluateJavascript(
                """
            (function() {
                var x = $jsX;
                var y = $jsY;
                var root = (x >= 0 && y >= 0) ? document.elementFromPoint(x, y) : null;
                var btn = root && root.closest ? root.closest('#sendBtn, #summarizeBtn') : null;
                var prev = window.__taplinkHoverBtn || null;

                if (prev && prev !== btn) {
                    prev.classList.remove('taplink-hover');
                }

                if (btn) {
                    if (!btn.classList.contains('taplink-hover')) {
                        btn.classList.add('taplink-hover');
                    }
                    window.__taplinkHoverBtn = btn;
                    return btn.id || '';
                }

                window.__taplinkHoverBtn = null;
                return '';
            })();
        """.trimIndent(),
                { result ->
                    val trimmed = result?.trim('"')
                    lastHoverId = if (trimmed.isNullOrBlank()) null else trimmed
                }
        )
    }

    fun clearHover() {
        closeButton.isHovered = false
    }

    // Pass touch events to WebView if using cursor simulation
    fun dispatchTouchEventToWebView(x: Float, y: Float) {
        // Transform coords relative to WebView
        val webViewX = x - webView.left
        val webViewY = y - webView.top

        // TODO: Implement proper event dispatch if needed.
        // For now, let's rely on standard touch propagation if the user taps directly.
        // But for cursor mode, we might need dispatchTouchEvent.
    }

    private fun dispatchTapToWebView(localX: Float, localY: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent =
                android.view.MotionEvent.obtain(
                        downTime,
                        downTime,
                        android.view.MotionEvent.ACTION_DOWN,
                        localX,
                        localY,
                        0
                )
        webView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        val upTime = android.os.SystemClock.uptimeMillis()
        val upEvent =
                android.view.MotionEvent.obtain(
                        downTime,
                        upTime,
                        android.view.MotionEvent.ACTION_UP,
                        localX,
                        localY,
                        0
                )
        webView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }

    fun sendTextToFocusedInput(text: String) {
        webView.evaluateJavascript(
                """
            (function() {
                var el = document.activeElement;
                if (!el) return;
                var isInput = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable;
                if (!isInput) return;
                var value = el.value || '';
                var start = el.selectionStart || 0;
                var end = el.selectionEnd || 0;
                var insertText = ${JSONObject.quote(text)};
                el.value = value.slice(0, start) + insertText + value.slice(end);
                if (el.setSelectionRange) {
                    var pos = start + insertText.length;
                    el.setSelectionRange(pos, pos);
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
            })();
        """.trimIndent(),
                null
        )
    }

    fun sendBackspaceToFocusedInput() {
        webView.evaluateJavascript(
                """
            (function() {
                var el = document.activeElement;
                if (!el) return;
                var isInput = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable;
                if (!isInput) return;
                var start = el.selectionStart || 0;
                var end = el.selectionEnd || 0;
                var value = el.value || '';
                if (start === end && start > 0) {
                    el.value = value.slice(0, start - 1) + value.slice(end);
                    if (el.setSelectionRange) {
                        el.setSelectionRange(start - 1, start - 1);
                    }
                } else if (start !== end) {
                    el.value = value.slice(0, start) + value.slice(end);
                    if (el.setSelectionRange) {
                        el.setSelectionRange(start, start);
                    }
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
            })();
        """.trimIndent(),
                null
        )
    }

    fun sendEnterToFocusedInput() {
        webView.evaluateJavascript(
                """
            (function() {
                var btn = document.getElementById('sendBtn');
                if (btn) {
                    btn.click();
                    return;
                }
                var el = document.activeElement;
                if (!el) return;
                var event = new KeyboardEvent('keypress', {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true
                });
                el.dispatchEvent(event);
            })();
        """.trimIndent(),
                null
        )
    }

    private fun injectInputFocusHook() {
        webView.evaluateJavascript(
                """
            (function() {
                if (window.__taplinkChatInputHooked) return;
                window.__taplinkChatInputHooked = true;

                var lastNotify = 0;
                var lastFocused = null;

                function isInput(el) {
                    if (!el) return false;
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') return true;
                    return !!el.isContentEditable;
                }

                function notify(target) {
                    var now = Date.now();
                    if (now - lastNotify < 150) return;
                    lastNotify = now;
                    if (window.ChatInputBridge && window.ChatInputBridge.onInputFocus) {
                        window.ChatInputBridge.onInputFocus();
                    }
                    if (target && target.scrollIntoView && target.getBoundingClientRect) {
                        var rect = target.getBoundingClientRect();
                        var viewH = window.innerHeight || document.documentElement.clientHeight;
                        if (rect.bottom > viewH - 8 || rect.top < 8) {
                            target.scrollIntoView({ block: 'center', inline: 'nearest' });
                        }
                    }
                }

                function handleFocusIn(e) {
                    if (!isInput(e.target)) return;
                    if (lastFocused === e.target) return;
                    lastFocused = e.target;
                    notify(e.target);
                }

                function handleFocusOut(e) {
                    if (lastFocused === e.target) {
                        lastFocused = null;
                    }
                }

                document.addEventListener('focusin', handleFocusIn, true);
                document.addEventListener('focusout', handleFocusOut, true);
            })();
        """.trimIndent(),
                null
        )
    }
}
