package io.github.codehasan.colorpicker.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
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

    private val darkBorderColor = "#666666".toColorInt()
    private val lightBorderColor = "#999999".toColorInt()

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isForceDarkAllowed = false
        }
    }

    fun updateContent(bitmap: Bitmap, color: String, x: Int, y: Int) {
        val oldBitmap = zoomBitmap
        zoomBitmap = bitmap
        oldBitmap?.recycle()

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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        zoomBitmap?.recycle()
        zoomBitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()

        val bezelThickness = size * 0.11f
        val textSizeNormal = size * 0.09f
        val textSizeLarge = size * 0.16f

        // Radii Configuration - leave ~4% margin to prevent clipping
        val maxRadius = (size / 2f) * 0.96f
        val rOuter = maxRadius
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

        // Inner border
        paint.color = darkBorderColor
        paint.strokeWidth = borderWidth * 2f
        canvas.drawCircle(cx, cy, rInner - borderWidth - borderInset, paint)

        paint.color = lightBorderColor
        paint.strokeWidth = borderWidth
        canvas.drawCircle(cx, cy, rInner - borderWidth / 2f - borderInset, paint)

        // Outer border
        paint.color = darkBorderColor
        paint.strokeWidth = borderWidth * 2f
        canvas.drawCircle(cx, cy, rOuter + borderWidth, paint)

        paint.color = lightBorderColor
        paint.strokeWidth = borderWidth
        canvas.drawCircle(cx, cy, rOuter + borderWidth * 1.5f, paint)

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
        val rSq = rInner * rInner

        // Vertical Lines - calculate visible segment within circle
        var xPos = startX + pixelSize
        while (xPos < destRect.right - 0.1f) {
            // Calculate intersection with circle
            val dx = xPos - cx
            if (dx * dx < rSq) {
                val halfChord = kotlin.math.sqrt(rSq - dx * dx)
                // Only draw the visible segment within the circle
                canvas.drawLine(xPos, cy - halfChord, xPos, cy + halfChord, paint)
            }
            xPos += pixelSize
        }

        // Horizontal Lines - calculate visible segment within circle
        var yPos = startY + pixelSize
        while (yPos < destRect.bottom - 0.1f) {
            // Calculate intersection with circle
            val dy = yPos - cy
            if (dy * dy < rSq) {
                val halfChord = kotlin.math.sqrt(rSq - dy * dy)
                // Only draw the visible segment within the circle
                canvas.drawLine(cx - halfChord, yPos, cx + halfChord, yPos, paint)
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