package com.TapLinkX3.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class DialogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val titleView: TextView
    private val messageView: TextView
    private val inputField: android.widget.EditText
    private val positiveButton: Button
    private val negativeButton: Button
    private val contentContainer: LinearLayout

    var onPositiveClick: ((String?) -> Unit)? = null
    var onNegativeClick: (() -> Unit)? = null

    init {
        // Set up the main container with a dark background and rounded corners
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )

        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2C"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#404040"))
            })
            setPadding(32, 32, 32, 32)
            layoutParams = LayoutParams(
                480, // Fixed width
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        messageView = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        inputField = android.widget.EditText(context).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackground(GradientDrawable().apply {
                setColor(Color.parseColor("#404040"))
                cornerRadius = 8f
            })
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        negativeButton = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                onNegativeClick?.invoke()
            }
        }

        positiveButton = Button(context).apply {
            text = "OK"
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                onPositiveClick?.invoke(if (inputField.visibility == View.VISIBLE) inputField.text.toString() else null)
            }
        }

        buttonContainer.addView(negativeButton)
        buttonContainer.addView(positiveButton)

        contentContainer.addView(titleView)
        contentContainer.addView(messageView)
        contentContainer.addView(inputField)
        contentContainer.addView(buttonContainer)

        addView(contentContainer)
    }

    fun showAlert(message: String, callback: () -> Unit) {
        titleView.text = "Alert"
        messageView.text = message
        inputField.visibility = View.GONE
        negativeButton.visibility = View.GONE
        positiveButton.text = "OK"
        onPositiveClick = { callback() }
        visibility = View.VISIBLE
    }

    fun showConfirm(message: String, onConfirm: (Boolean) -> Unit) {
        titleView.text = "Confirm"
        messageView.text = message
        inputField.visibility = View.GONE
        negativeButton.visibility = View.VISIBLE
        positiveButton.text = "OK"
        onPositiveClick = { onConfirm(true) }
        onNegativeClick = { onConfirm(false) }
        visibility = View.VISIBLE
    }

    fun showPrompt(message: String, defaultValue: String?, onInput: (String?) -> Unit) {
        titleView.text = "Prompt"
        messageView.text = message
        inputField.visibility = View.VISIBLE
        inputField.setText(defaultValue ?: "")
        negativeButton.visibility = View.VISIBLE
        positiveButton.text = "OK"
        onPositiveClick = { text -> onInput(text) }
        onNegativeClick = { onInput(null) }
        visibility = View.VISIBLE
        inputField.requestFocus()
    }

    // Helper method to get the input field for keyboard integration
    fun getInputField(): android.widget.EditText {
        return inputField
    }

    fun submit() {
        positiveButton.performClick()
    }
}
