package com.TapLinkX3.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val selectorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    
    private var selectedColor = Color.WHITE
    private var selectedAngle = 0f

    init {
        // Create a sweep gradient for the color wheel
        val colors = intArrayOf(
            Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
            Color.GREEN, Color.YELLOW, Color.RED
        )
        // Ensure shader is created in onSizeChanged/onDraw where dimensions are known, 
        // or just rely on draw logic. 
        // Actually, simplest is to update shader in onSizeChanged.
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f - 10f // Padding

        val colors = intArrayOf(
            Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
            Color.GREEN, Color.YELLOW, Color.RED
        )
        val sweepGradient = SweepGradient(centerX, centerY, colors, null)
        colorPaint.shader = sweepGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Color Wheel
        canvas.drawCircle(centerX, centerY, radius, colorPaint)

        // Draw Selector Indicator based on selected angle (or color)
        // We put a small circle at the edge or slightly inside
        val indicatorRadius = radius * 0.8f
        val indX = centerX + indicatorRadius * cos(Math.toRadians(selectedAngle.toDouble())).toFloat()
        val indY = centerY + indicatorRadius * sin(Math.toRadians(selectedAngle.toDouble())).toFloat()

        selectorFillPaint.color = selectedColor
        canvas.drawCircle(indX, indY, 12f, selectorFillPaint) // Fill with selected color
        canvas.drawCircle(indX, indY, 14f, selectorPaint) // White border
    }
    
    fun setColor(color: Int) {
        selectedColor = color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        selectedAngle = hsv[0] // Hue is 0-360
        invalidate()
    }
    
    /**
     * returns the color at the specific angle (0-360)
     */
    fun getColorAtAngle(angle: Float): Int {
        val hsv = floatArrayOf(angle, 1f, 1f)
        return Color.HSVToColor(hsv)
    }

    // Since we handle touch manually in DualWebViewGroup, we expose a calculation helper
    fun calculateColorFromCoordinates(x: Float, y: Float): Int {
        val dx = x - centerX
        val dy = y - centerY
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        
        return getColorAtAngle(angle)
    }
    
    // We also need to update visual state without triggering logic possibly
    fun updateSelector(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        
        selectedAngle = angle
        selectedColor = getColorAtAngle(angle)
        invalidate()
    }
}
