package io.github.codehasan.colorpicker.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import kotlin.math.min

class TargetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * Returns the center point of the view.
     */
    fun getScanOffset(): PointF {
        val offset = IntArray(2)
        getLocationOnScreen(offset)
        return PointF(offset[0] + (width / 2f), offset[1] + (height / 2f))
    }

    /**
     * Returns the size which can be safely captured without colliding
     * with the TargetView circles.
     */
    fun getSafeCropSize() = 12

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        val size = min(width, height).toFloat()

        val thinStrokePct = 0.02f
        val mainStrokePct = 0.28f
        val outerStrokePct = 0.05f

        val holeRadius = size * 0.13f

        val thinWidth = size * thinStrokePct
        val mainWidth = size * mainStrokePct
        val outerWidth = size * outerStrokePct

        paint.style = Paint.Style.STROKE

        // Inner Thin Circle
        paint.color = "#B4A9A9A9".toColorInt()
        paint.strokeWidth = thinWidth
        val r1 = holeRadius + (thinWidth / 2f)
        canvas.drawCircle(cx, cy, r1, paint)

        // Inner Main Circle
        paint.color = "#C8535353".toColorInt()
        paint.strokeWidth = mainWidth
        val r2 = holeRadius + thinWidth + (mainWidth / 2f)
        canvas.drawCircle(cx, cy, r2, paint)

        // Outer Medium Circle
        paint.color = "#E6A9A9A9".toColorInt()
        paint.strokeWidth = outerWidth
        val r3 = holeRadius + thinWidth + mainWidth + (outerWidth / 2f)
        canvas.drawCircle(cx, cy, r3, paint)
    }
}