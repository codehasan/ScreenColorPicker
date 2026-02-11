package io.github.codehasan.colorpicker.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import io.github.codehasan.colorpicker.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class MagnifierView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OnInteractionListener {
        fun onCloseClicked()
        fun onHexClicked(hex: String)
        fun onCoordsClicked(coords: String)
    }

    var listener: OnInteractionListener? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val textPath = Path()
    private val destRect = RectF()

    private var zoomBitmap: Bitmap? = null

    private val gridShadowColor = "#40000000".toColorInt()
    private val gridMainColor = "#80FFFFFF".toColorInt()

    private val darkTextColor = "#0F0F10".toColorInt()
    private val lightTextColor = "#F2F2F4".toColorInt()

    private val darkBorderColor = "#404040".toColorInt()
    private val lightBorderColor = "#D0D0D0".toColorInt()
    private val borderShadowColor = "#30000000".toColorInt()

    // Properties (Dynamic)
    private var hexColor = "#000000"
    private var coords = "0, 0"
    private var showGridLines = true

    // Touch Handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isClickCandidate = false

    // Hit Boxes
    private val hexTouchRect = RectF()
    private val coordsTouchRect = RectF()
    private val closeTouchRect = RectF()

    private val typeface = ResourcesCompat.getFont(context, R.font.product_sans_regular)

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun updateContent(bitmap: Bitmap, color: String, x: Int, y: Int) {
        zoomBitmap = bitmap
        hexColor = color
        coords = "$x, $y"
        invalidate()
    }

    fun setShowGridLines(show: Boolean) {
        if (showGridLines != show) {
            showGridLines = show
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()

        val bezelThickness = size * 0.11f
        val textSizeNormal = size * 0.09f
        val textSizeLarge = size * 0.16f

        // Radii Configuration
        val rOuter = size / 2f
        val rInner = rOuter - bezelThickness
        val rCenter = rInner + (bezelThickness / 2f)

        // Calculate Adaptive Text Color
        val parsedColor = try {
            hexColor.toColorInt()
        } catch (_: IllegalArgumentException) {
            Color.BLACK
        }
        val luminance = (0.299 * Color.red(parsedColor) +
                0.587 * Color.green(parsedColor) +
                0.114 * Color.blue(parsedColor)) / 255
        val textColor = if (luminance > 0.6) darkTextColor else lightTextColor

        // Draw Image Area
        canvas.withSave {
            clipPath.reset()
            clipPath.addCircle(cx, cy, rInner, Path.Direction.CW)
            clipPath(clipPath)
            drawColor(Color.BLACK)

            zoomBitmap?.let { bmp ->
                paint.isFilterBitmap = false

                val visibleDiameter = rInner * 2
                val pixelSize = if (bmp.width > 1) {
                    visibleDiameter / (bmp.width - 1f)
                } else {
                    visibleDiameter
                }

                val totalDrawSize = pixelSize * bmp.width
                val centerOffset = if (bmp.width % 2 == 0) pixelSize / 2f else 0f

                val startX = cx - (totalDrawSize / 2f) - centerOffset
                val startY = cy - (totalDrawSize / 2f) - centerOffset

                destRect.set(
                    startX, startY,
                    startX + totalDrawSize,
                    startY + totalDrawSize
                )

                drawBitmap(bmp, null, destRect, paint)

                // Draw Dual-Layer Grid (Shadow + Main)
                if (showGridLines) {
                    paint.style = Paint.Style.STROKE
                    val gridBaseWidth = size * 0.005f

                    // Layer A: Grid Shadow (Thicker, Dark)
                    paint.color = gridShadowColor
                    paint.strokeWidth = gridBaseWidth * 1.5f
                    drawGridLines(canvas, startX, startY, pixelSize, destRect, cx, cy, rInner)

                    // Layer B: Main Grid (Thin, Light)
                    paint.color = gridMainColor
                    paint.strokeWidth = gridBaseWidth
                    drawGridLines(canvas, startX, startY, pixelSize, destRect, cx, cy, rInner)
                }

                // Highlight Center Pixel
                paint.style = Paint.Style.STROKE
                paint.color = textColor
                paint.strokeWidth = size * 0.01f
                val halfPixel = pixelSize / 2f
                drawRect(
                    cx - halfPixel,
                    cy - halfPixel,
                    cx + halfPixel,
                    cy + halfPixel,
                    paint
                )
            }
        }

        // Draw Main Bezel
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = bezelThickness
        paint.color = parsedColor
        canvas.drawCircle(cx, cy, rCenter, paint)

        // Draw Bezel Borders
        paint.style = Paint.Style.STROKE
        val borderWidth = size * 0.005f
        val borderInset = borderWidth * 0.5f  // Prevent anti-aliasing overflow

        // Dual-tone borders for visibility on any background
        paint.color = darkBorderColor
        paint.strokeWidth = borderWidth * 2f
        canvas.drawCircle(cx, cy, rInner, paint)
        canvas.drawCircle(cx, cy, rOuter - borderWidth - borderInset, paint)

        paint.color = lightBorderColor
        paint.strokeWidth = borderWidth
        canvas.drawCircle(cx, cy, rInner, paint)
        canvas.drawCircle(cx, cy, rOuter - borderWidth * 2f - borderInset, paint)

        // Add slight shadow for depth
        paint.color = borderShadowColor
        paint.strokeWidth = borderWidth * 2f
        canvas.drawCircle(cx, cy, rOuter - borderWidth * 2.5f - borderInset, paint)

        // Draw Text Buttons
        paint.style = Paint.Style.FILL
        paint.color = textColor
        paint.typeface = typeface
        paint.textAlign = Paint.Align.CENTER

        // Hex Color
        paint.textSize = textSizeNormal
        drawTextAtAngle(
            canvas, hexColor,
            cx, cy, rCenter,
            45.0, -145.0,
            hexTouchRect
        )

        // Coordinates
        paint.textSize = textSizeNormal
        drawTextAtAngle(
            canvas, coords,
            cx, cy, rCenter,
            135.0, -50.0,
            coordsTouchRect
        )

        // Close 'X'
        paint.textSize = textSizeLarge
        drawTextAtAngle(
            canvas, "×",
            cx, cy + (bezelThickness / 12), rCenter,
            -90.0, 90.0,
            closeTouchRect
        )
    }

    private fun drawGridLines(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        pixelSize: Float,
        destRect: RectF,
        cx: Float,
        cy: Float,
        rInner: Float
    ) {
        // Vertical Lines
        var xPos = startX + pixelSize
        while (xPos < destRect.right - 0.1f) {
            if (xPos > cx - rInner && xPos < cx + rInner) {
                canvas.drawLine(xPos, destRect.top, xPos, destRect.bottom, paint)
            }
            xPos += pixelSize
        }

        // Horizontal Lines
        var yPos = startY + pixelSize
        while (yPos < destRect.bottom - 0.1f) {
            if (yPos > cy - rInner && yPos < cy + rInner) {
                canvas.drawLine(destRect.left, yPos, destRect.right, yPos, paint)
            }
            yPos += pixelSize
        }
    }

    private fun drawTextAtAngle(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        radius: Float,
        textAngleDegrees: Double,
        touchAngleDegrees: Double,
        touchRect: RectF
    ) {
        textPath.reset()
        textPath.addCircle(cx, cy, radius, Path.Direction.CW)

        val textWidth = paint.measureText(text)
        val circumference = (2 * Math.PI * radius).toFloat()
        if (textWidth > circumference) return

        val startOffset =
            (textAngleDegrees / 360.0 * circumference).toFloat()

        val fm = paint.fontMetrics
        val verticalOffset = -(fm.ascent + fm.descent) / 2f

        canvas.drawTextOnPath(
            text,
            textPath,
            startOffset,
            verticalOffset,
            paint
        )

        val sweepAngleRad = textWidth / radius
        val midAngleRad =
            Math.toRadians(touchAngleDegrees) + sweepAngleRad / 2.0

        val textHeight = fm.descent - fm.ascent
        val padding = paint.textSize * 0.5f

        val minR = radius - textHeight / 2f - padding
        val maxR = radius + textHeight / 2f + padding

        val x1 = cx + (minR * cos(midAngleRad)).toFloat()
        val y1 = cy + (minR * sin(midAngleRad)).toFloat()
        val x2 = cx + (maxR * cos(midAngleRad)).toFloat()
        val y2 = cy + (maxR * sin(midAngleRad)).toFloat()

        val expandAmount = padding + (textWidth / 4f)

        touchRect.set(
            minOf(x1, x2) - expandAmount,
            minOf(y1, y2) - expandAmount,
            maxOf(x1, x2) + expandAmount,
            maxOf(y1, y2) + expandAmount
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isClickCandidate = true
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - lastTouchX, event.y - lastTouchY) > 10) {
                    isClickCandidate = false
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isClickCandidate) {
                    if (closeTouchRect.contains(event.x, event.y)) {
                        listener?.onCloseClicked()
                    } else if (hexTouchRect.contains(event.x, event.y)) {
                        listener?.onHexClicked(hexColor)
                    } else if (coordsTouchRect.contains(event.x, event.y)) {
                        listener?.onCoordsClicked(coords)
                    }
                }
            }
        }
        return true
    }
}