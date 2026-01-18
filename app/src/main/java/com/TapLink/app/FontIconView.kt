package com.TapLinkX3.app

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import com.TapLinkX3.app.R

class FontIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        // Default to Solid icons
        updateTypeface(isBrand = false)
        gravity = android.view.Gravity.CENTER
    }

    fun setIsBrand(isBrand: Boolean) {
        updateTypeface(isBrand)
    }

    private fun updateTypeface(isBrand: Boolean) {
        val typeFace: Typeface? = if (isBrand) {
            ResourcesCompat.getFont(context, R.font.fa_brands_400)
        } else {
            ResourcesCompat.getFont(context, R.font.fa_solid_900)
        }
        setTypeface(typeFace)
    }
}
