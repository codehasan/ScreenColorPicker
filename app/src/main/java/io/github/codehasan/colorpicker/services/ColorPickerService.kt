package io.github.codehasan.colorpicker.services

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import io.github.codehasan.colorpicker.R
import io.github.codehasan.colorpicker.views.MagnifierView
import io.github.codehasan.colorpicker.views.TargetView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ColorPickerService : Service(), MagnifierView.OnInteractionListener {

    private lateinit var windowManager: WindowManager
    private lateinit var displayMetrics: DisplayMetrics

    // Windows
    private lateinit var targetLayout: FrameLayout
    private lateinit var targetParams: WindowManager.LayoutParams
    private lateinit var targetView: TargetView
    private lateinit var magnifierLayout: FrameLayout
    private lateinit var magnifierParams: WindowManager.LayoutParams
    private lateinit var magnifierView: MagnifierView

    // Screen Capture
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isCapturing = false

    // Logic Variables
    private var scanX = 0
    private var scanY = 0
    private var screenWidth = 0
    private var screenHeight = 0

    private val minGapBetweenEdges = 50
    private val maxGapBetweenEdges = 100 // Maximum space allowed before "towing" starts

    private val magnifierSizeDp = 150
    private val targetSizeDp = 40

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            setupWindows()
            startScreenCapture(resultCode, resultData)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupWindows() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        val (w, h) = getLogicalFullScreenSize()
        screenWidth = w
        screenHeight = h

        val targetSizePx = (targetSizeDp * displayMetrics.density).toInt()
        val magnifierSizePx = (magnifierSizeDp * displayMetrics.density).toInt()

        // Create Target View
        targetLayout = FrameLayout(this)
        targetView = TargetView(this)
        targetLayout.addView(
            targetView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        targetParams = createWindowLayoutParams()
        targetParams.width = targetSizePx
        targetParams.height = targetSizePx

        targetParams.x = (screenWidth - targetSizePx) / 2
        targetParams.y = (screenHeight - targetSizePx) / 2

        // Create Magnifier View
        magnifierLayout = FrameLayout(this)
        magnifierView = MagnifierView(this)
        magnifierView.listener = this

        magnifierLayout.addView(
            magnifierView,
            FrameLayout.LayoutParams(magnifierSizePx, magnifierSizePx)
        )

        magnifierParams = createWindowLayoutParams()
        magnifierParams.x = (screenWidth - magnifierSizePx) / 2
        magnifierParams.y = targetParams.y - minGapBetweenEdges

        addTargetDragListener()
        addMagnifierFineTuneListener()

        windowManager.addView(targetLayout, targetParams)
        windowManager.addView(magnifierLayout, magnifierParams)

        targetView.post {
            updateScanCoordinates()

            val tRadius = targetSizePx / 2f
            repositionMagnifier(
                targetParams.x + tRadius,
                targetParams.y + tRadius,
                tRadius,
                magnifierSizePx
            )
            windowManager.updateViewLayout(magnifierLayout, magnifierParams)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTargetDragListener() {
        var initX = 0;
        var initY = 0;
        var touchX = 0f;
        var touchY = 0f

        targetLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = targetParams.x
                    initY = targetParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    targetParams.x = initX + (event.rawX - touchX).toInt()
                    targetParams.y = initY + (event.rawY - touchY).toInt()

                    checkDistanceAndRules()

                    windowManager.updateViewLayout(targetLayout, targetParams)
                    windowManager.updateViewLayout(magnifierLayout, magnifierParams)

                    updateScanCoordinates()
                    true
                }

                else -> false
            }
        }
    }

    private fun checkDistanceAndRules() {
        val tSize = targetLayout.width
        val mSize = magnifierLayout.width // Assuming square

        // Centers
        val tx = targetParams.x + tSize / 2f
        val ty = targetParams.y + tSize / 2f
        val mx = magnifierParams.x + mSize / 2f
        val my = magnifierParams.y + mSize / 2f

        // Calculate Distance between CENTERS
        val dx = mx - tx
        val dy = my - ty
        val centerDistance = sqrt(dx * dx + dy * dy)

        // Radii
        val tRadius = tSize / 2f
        val mRadius = mSize / 2f
        val currentGap = centerDistance - tRadius - mRadius

        // RULE 1: TOWING (If gap > max, pull magnifier closer)
        if (currentGap > maxGapBetweenEdges) {
            val angle = atan2(dy, dx)
            // Desired distance from center to center = radii + maxGap
            val allowedDist = tRadius + mRadius + maxGapBetweenEdges

            // New Magnifier Center
            val newMx = tx + cos(angle) * allowedDist
            val newMy = ty + sin(angle) * allowedDist

            // Convert back to Top-Left for Params
            magnifierParams.x = (newMx - mRadius).toInt()
            magnifierParams.y = (newMy - mRadius).toInt()
        }

        // RULE 2: COLLISION (If gap < min, reposition/jump)
        if (currentGap < minGapBetweenEdges) {
            repositionMagnifier(tx, ty, tRadius, mSize)
        }
    }

    private fun repositionMagnifier(tx: Float, ty: Float, tRadius: Float, mSize: Int) {
        val mRadius = mSize / 2f

        val directions = listOf(
            Pair(-1, 0), // Left
            Pair(0, -1), // Top
            Pair(1, 0),  // Right
            Pair(0, 1)   // Bottom
        )

        // Distances: Try MAX (ideal) first, then MIN (fallback)
        val gapsToCheck = listOf(maxGapBetweenEdges, minGapBetweenEdges)

        for (gap in gapsToCheck) {
            val distFromCenter = tRadius + gap + mRadius

            for (dir in directions) {
                var pLeft: Int
                var pTop: Int

                // Case 1: Moving Horizontally (Left or Right)
                if (dir.first != 0) {
                    // Calculate X: Target Center + Direction * Distance - Radius
                    val pCenterX = tx + (dir.first * distFromCenter)
                    pLeft = (pCenterX - mRadius).toInt()

                    // Calculate Y: Align with Target Y, but clamp to screen bounds
                    pTop = (ty - mRadius).toInt().coerceIn(0, screenHeight - mSize)
                }
                // Case 2: Moving Vertically (Top or Bottom)
                else {
                    // Calculate Y: Target Center + Direction * Distance - Radius
                    val pCenterY = ty + (dir.second * distFromCenter)
                    pTop = (pCenterY - mRadius).toInt()

                    // Calculate X: Align with Target X, but clamp to screen bounds
                    pLeft = (tx - mRadius).toInt().coerceIn(0, screenWidth - mSize)
                }

                // Ensure the ENTIRE magnifier is within the screen (0 to width/height)
                val fitsHorizontally = (pLeft >= 0) && ((pLeft + mSize) <= screenWidth)
                val fitsVertically = (pTop >= 0) && ((pTop + mSize) <= screenHeight)

                if (fitsHorizontally && fitsVertically) {
                    // Valid position found! Apply and return immediately.
                    magnifierParams.x = pLeft
                    magnifierParams.y = pTop
                    return
                }
            }
        }
        // If no position fits (e.g., target is deep in a corner),
        // the magnifier stays in its last known valid position.
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addMagnifierFineTuneListener() {
        var touchX = 0f;
        var touchY = 0f

        magnifierView.setOnTouchListener { view, event ->
            view.onTouchEvent(event) // Allow clicking buttons

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY

                    targetParams.x += (dx * 0.1f).toInt()
                    targetParams.y += (dy * 0.1f).toInt()

                    // Check rules even during fine tuning to keep constraints valid
                    checkDistanceAndRules()

                    windowManager.updateViewLayout(targetLayout, targetParams)
                    windowManager.updateViewLayout(
                        magnifierLayout,
                        magnifierParams
                    ) // Update mag if towed
                    updateScanCoordinates()

                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                else -> true
            }
        }
    }

    private fun createWindowLayoutParams(
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            width, width, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        return params
    }

    private fun getLogicalFullScreenSize(): Pair<Int, Int> {
        val logicalMetrics = resources.displayMetrics

        // If widths match, the device isn't scaling. Use real metrics to include system bars.
        if (displayMetrics.widthPixels == logicalMetrics.widthPixels) {
            return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }

        // If different, the device is scaling. We must scale the 'Real' height
        // down to match the 'Logical' width.
        val scaleFactor =
            logicalMetrics.widthPixels.toFloat() / displayMetrics.widthPixels.toFloat()
        val scaledHeight = (displayMetrics.heightPixels * scaleFactor).toInt()

        return Pair(logicalMetrics.widthPixels, scaledHeight)
    }

    private fun updateScanCoordinates() {
        val offset = targetView.getScanOffset()

        scanX = offset.x.toInt()
        scanY = offset.y.toInt()
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        val (width, height) = getLogicalFullScreenSize()
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        isCapturing = true
        captureLoop()
    }

    private fun captureLoop() {
        if (!isCapturing) return
        imageReader?.acquireLatestImage()?.let { processImage(it); it.close() }
        handler.postDelayed({ captureLoop() }, 50)
    }

    private fun processImage(image: Image) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val screenBitmap = createBitmap(image.width + rowPadding / pixelStride, image.height)
        screenBitmap.copyPixelsFromBuffer(buffer)

        val safeX = scanX.coerceIn(0, screenBitmap.width - 1)
        val safeY = scanY.coerceIn(0, screenBitmap.height - 1)

        val pixelColor = screenBitmap[safeX, safeY]
        val hexColor = String.format("#%06X", (0xFFFFFF and pixelColor))

        val cropSize = targetView.getSafeCropSize()

        val cropX = (safeX - cropSize / 2).coerceIn(0, screenBitmap.width - cropSize)
        val cropY = (safeY - cropSize / 2).coerceIn(0, screenBitmap.height - cropSize)

        val croppedBitmap = Bitmap.createBitmap(
            screenBitmap,
            cropX, cropY,
            cropSize, cropSize
        )

        handler.post {
            magnifierView.updateContent(croppedBitmap, hexColor, safeX, safeY)
        }
        screenBitmap.recycle()
    }

    override fun onCloseClicked() = stopSelf()

    override fun onHexClicked(hex: String) =
        copyToClipboard(getString(R.string.color), hex)

    override fun onCoordsClicked(coords: String) =
        copyToClipboard(getString(R.string.coordinates), coords)

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied, label), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::targetLayout.isInitialized) windowManager.removeView(targetLayout)
        if (::magnifierLayout.isInitialized) windowManager.removeView(magnifierLayout)
    }

    private fun createNotification(): Notification {
        val channelId = "ColorPickerChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.color_picker_active))
            .setSmallIcon(R.drawable.ic_logo)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 1
    }
}