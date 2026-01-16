package com.TapLinkX3.app

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import android.view.Gravity

class FontIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        try {
            val typeface = ResourcesCompat.getFont(context, R.font.fa_solid_900)
            setTypeface(typeface)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        gravity = Gravity.CENTER
    }
}