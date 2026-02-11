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
import android.content.SharedPreferences
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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat.getParcelableExtra
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.preference.PreferenceManager
import androidx.window.layout.WindowMetricsCalculator
import io.github.codehasan.colorpicker.R
import io.github.codehasan.colorpicker.ServiceState
import io.github.codehasan.colorpicker.extensions.dp2px
import io.github.codehasan.colorpicker.views.MagnifierView
import io.github.codehasan.colorpicker.views.TargetView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ColorPickerService : Service(), MagnifierView.OnInteractionListener {

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private val clipboard: ClipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    }

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

    private var screenBitmap: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    // Logic Variables
    private var scanX = 0
    private var scanY = 0
    private var screenWidth = 0
    private var screenHeight = 0

    private val minGapBetweenEdges = 50
    private val maxGapBetweenEdges = 100

    // Cached preference values
    private val targetSizeDp = 40
    private var captureDelayMs = 50L

    // Preferences
    private lateinit var sharedPreferences: SharedPreferences
    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PREF_MAGNIFIER_SIZE -> {
                    if (::targetLayout.isInitialized && ::magnifierLayout.isInitialized) {
                        removeWindows()
                        setupWindows()
                    }
                }

                PREF_CAPTURE_SPEED -> {
                    captureDelayMs = getCaptureDelayMs()
                }

                PREF_SHOW_GRID_LINES -> {
                    magnifierView.setShowGridLines(getShowGridLines())
                }

                PREF_CAPTURE_RANGE -> {
                    if (::targetView.isInitialized) {
                        targetView.setCaptureRange(getCaptureRange())
                    }
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        captureDelayMs = getCaptureDelayMs()
    }

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
        val resultData = intent?.let {
            getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            setupWindows()
            startScreenCapture(resultCode, resultData)
            // Set state to true AFTER service is actually initialized
            ServiceState.setColorPickerRunning(true)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun setupWindows() {
        displayMetrics = DisplayMetrics().also {
            val metrics = WindowMetricsCalculator.getOrCreate()
                .computeMaximumWindowMetrics(this)
            it.widthPixels = metrics.bounds.width()
            it.heightPixels = metrics.bounds.height()
        }

        val (w, h) = getLogicalFullScreenSize()
        screenWidth = w
        screenHeight = h

        val targetSizePx = dp2px(targetSizeDp.toFloat())
        val magnifierSizePx = dp2px(getMagnifierSizeDp().toFloat())

        // Create Target View
        targetLayout = FrameLayout(this)
        targetView = TargetView(this)
        targetView.setCaptureRange(getCaptureRange())
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
        magnifierView.setShowGridLines(getShowGridLines())

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

    private fun removeWindows() {
        if (::targetLayout.isInitialized) windowManager.removeView(targetLayout)
        if (::magnifierLayout.isInitialized) windowManager.removeView(magnifierLayout)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTargetDragListener() {
        var initX = 0
        var initY = 0
        var touchX = 0f
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

                    val magChanged = checkDistanceAndRules()

                    windowManager.updateViewLayout(targetLayout, targetParams)
                    if (magChanged) {
                        windowManager.updateViewLayout(magnifierLayout, magnifierParams)
                    }

                    updateScanCoordinates()
                    true
                }

                else -> false
            }
        }
    }

    private fun checkDistanceAndRules(): Boolean {
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

        val oldMagX = magnifierParams.x
        val oldMagY = magnifierParams.y

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

        return magnifierParams.x != oldMagX || magnifierParams.y != oldMagY
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
        var touchX = 0f
        var touchY = 0f
        // Accumulate fractional movement for sub-pixel precision
        var accumulatedX = 0f
        var accumulatedY = 0f

        magnifierView.setOnTouchListener { view, event ->
            view.onTouchEvent(event) // Allow clicking buttons

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX
                    touchY = event.rawY
                    accumulatedX = 0f
                    accumulatedY = 0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY

                    // Accumulate fractional movement
                    accumulatedX += dx * 0.1f
                    accumulatedY += dy * 0.1f

                    // Apply integer portion, keep fractional part
                    val moveX = accumulatedX.toInt()
                    val moveY = accumulatedY.toInt()
                    accumulatedX -= moveX
                    accumulatedY -= moveY

                    targetParams.x += moveX
                    targetParams.y += moveY

                    // Check rules even during fine tuning to keep constraints valid
                    val magChanged = checkDistanceAndRules()

                    windowManager.updateViewLayout(targetLayout, targetParams)
                    // Only update magnifier if its position actually changed
                    if (magChanged) {
                        windowManager.updateViewLayout(
                            magnifierLayout,
                            magnifierParams
                        ) // Update mag if towed
                    }
                    updateScanCoordinates()

                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                else -> true
            }
        }
    }

    @Suppress("DEPRECATION")
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

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

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
        try {
            imageReader?.acquireLatestImage()?.let { processImage(it) }
        } catch (e: Exception) {
            Log.e("ColorPickerService", "Failed to process image", e)
        }
        handler.postDelayed({ captureLoop() }, captureDelayMs)
    }

    private fun processImage(image: Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val requiredWidth = image.width + rowPadding / pixelStride
            val requiredHeight = image.height

            var bitmap = screenBitmap
            if (bitmap == null || bitmap.width != requiredWidth || bitmap.height != requiredHeight) {
                bitmap = createBitmap(requiredWidth, requiredHeight).also {
                    screenBitmap?.recycle()
                    screenBitmap = it
                    bitmapWidth = requiredWidth
                    bitmapHeight = requiredHeight
                }
            }

            bitmap.copyPixelsFromBuffer(buffer)

            val safeX = scanX.coerceIn(0, bitmap.width - 1)
            val safeY = scanY.coerceIn(0, bitmap.height - 1)

            val pixelColor = bitmap[safeX, safeY]
            val hexColor = String.format("#%06X", (0xFFFFFF and pixelColor))

            val cropSize = targetView.getSafeCropSize()

            val cropX = (safeX - cropSize / 2).coerceIn(0, bitmap.width - cropSize)
            val cropY = (safeY - cropSize / 2).coerceIn(0, bitmap.height - cropSize)

            val crop = createBitmap(cropSize, cropSize)
            val pixels = IntArray(cropSize * cropSize)
            bitmap.getPixels(pixels, 0, cropSize, cropX, cropY, cropSize, cropSize)
            crop.setPixels(pixels, 0, cropSize, 0, 0, cropSize, cropSize)

            handler.post {
                magnifierView.updateContent(crop, hexColor, safeX, safeY)
            }
        } finally {
            image.close()
        }
    }

    override fun onCloseClicked() = stopSelf()

    override fun onHexClicked(hex: String) =
        copyToClipboard(getString(R.string.color), hex)

    override fun onCoordsClicked(coords: String) =
        copyToClipboard(getString(R.string.coordinates), coords)

    private fun copyToClipboard(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            this,
            getString(R.string.copied, label),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        virtualDisplay?.release()
        mediaProjection?.stop()

        screenBitmap?.recycle()
        screenBitmap = null

        if (::targetLayout.isInitialized) windowManager.removeView(targetLayout)
        if (::magnifierLayout.isInitialized) windowManager.removeView(magnifierLayout)

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        ServiceState.setColorPickerRunning(false)
    }

    private fun createNotification(): Notification {
        val channelId = "ColorPickerChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(chan)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.color_picker_active))
            .setSmallIcon(R.drawable.ic_logo)
            .build()
    }

    // Preference getters
    private fun getMagnifierSizeDp(): Int {
        val size = sharedPreferences.getString(PREF_MAGNIFIER_SIZE, "small") ?: "small"
        return when (size) {
            "small" -> 150
            "medium" -> 200
            "large" -> 250
            else -> 150
        }
    }

    private fun getCaptureDelayMs(): Long {
        val speed = sharedPreferences.getString(PREF_CAPTURE_SPEED, "normal") ?: "normal"
        return when (speed) {
            "fast" -> 25L
            "normal" -> 50L
            "slow" -> 100L
            else -> 50L
        }
    }

    private fun getShowGridLines(): Boolean {
        return sharedPreferences.getBoolean(PREF_SHOW_GRID_LINES, true)
    }

    private fun getCaptureRange(): String {
        return sharedPreferences.getString(PREF_CAPTURE_RANGE, "small") ?: "small"
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 1

        const val PREF_MAGNIFIER_SIZE = "magnifier_size"
        const val PREF_CAPTURE_SPEED = "capture_speed"
        const val PREF_SHOW_GRID_LINES = "show_grid_lines"
        const val PREF_CAPTURE_RANGE = "capture_range"
    }
}
