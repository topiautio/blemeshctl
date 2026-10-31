package io.github.topiautio.blemeshctl.color

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** A touch-driven hue/saturation wheel with white in the centre. */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var selection = WheelHsv(120f, 1f)
    private var wheelBitmap: Bitmap? = null
    private var wheelRadius = 0f
    private var listener: ((WheelRgb) -> Unit)? = null

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateContentDescription()
    }

    val selectedRgb: WheelRgb
        get() = ColorWheelMath.hsvToRgb(selection)

    fun setRgb(red: Int, green: Int, blue: Int) {
        selection = ColorWheelMath.rgbToHsv(red, green, blue)
        updateContentDescription()
        invalidate()
    }

    fun setOnColorChangedListener(listener: ((WheelRgb) -> Unit)?) {
        this.listener = listener
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        wheelBitmap?.recycle()
        wheelBitmap = null
        // Leave room for the selection marker at the edge of the wheel.
        val markerMargin = resources.displayMetrics.density * 10f
        wheelRadius = (min(width, height) / 2f - markerMargin).coerceAtLeast(1f)
        if (width <= 0 || height <= 0) return

        val centerX = width / 2f
        val centerY = height / 2f
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val deltaX = x + 0.5f - centerX
                val deltaY = y + 0.5f - centerY
                if (hypot(deltaX.toDouble(), deltaY.toDouble()) <= wheelRadius) {
                    val rgb = ColorWheelMath.hsvToRgb(ColorWheelMath.fromPoint(deltaX, deltaY, wheelRadius))
                    pixels[y * width + x] = Color.rgb(rgb.red, rgb.green, rgb.blue)
                }
            }
        }
        wheelBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wheelBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (wheelRadius <= 0f) return

        val (deltaX, deltaY) = ColorWheelMath.pointFor(selection, wheelRadius)
        val markerX = width / 2f + deltaX
        val markerY = height / 2f + deltaY
        val density = resources.displayMetrics.density
        // A white ring inside a black ring stays visible on every hue.
        markerPaint.color = Color.BLACK
        markerPaint.strokeWidth = density * 5f
        canvas.drawCircle(markerX, markerY, density * 8f, markerPaint)
        markerPaint.color = Color.WHITE
        markerPaint.strokeWidth = density * 2f
        canvas.drawCircle(markerX, markerY, density * 8f, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || wheelRadius <= 0f) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Keep the surrounding ScrollView from stealing the drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                selection = ColorWheelMath.fromPoint(event.x - width / 2f, event.y - height / 2f, wheelRadius)
                updateContentDescription()
                invalidate()
                listener?.invoke(selectedRgb)
                true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        wheelBitmap?.recycle()
        wheelBitmap = null
        super.onDetachedFromWindow()
    }

    private fun updateContentDescription() {
        val rgb = selectedRgb
        contentDescription = "Värirulla. Valittu väri: punainen ${rgb.red}, vihreä ${rgb.green}, sininen ${rgb.blue}."
    }
}
