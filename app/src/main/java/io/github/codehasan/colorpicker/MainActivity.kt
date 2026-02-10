package io.github.codehasan.colorpicker

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.codehasan.colorpicker.extensions.canShowNotification
import io.github.codehasan.colorpicker.extensions.showMessage
import io.github.codehasan.colorpicker.services.ColorPickerService
import io.github.codehasan.colorpicker.services.ServiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_NOTIFICATION = 262345
        const val EXTRA_FROM_TILE = "from_tile"
        private const val GITHUB_REPO_URL = "https://github.com/codehasan/ScreenColorPicker"
    }

    private var fromTile = false
    private var stateObserverJob: Job? = null

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startColorPickerService(result.resultCode, result.data!!)
            if (fromTile) {
                moveTaskToBack(true)
                fromTile = false
            }
        } else {
            showMessage(R.string.screen_capture_permission_denied)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            if (fromTile) {
                handleTileClick()
            } else {
                startColorPickerFlow()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val fab = findViewById<FloatingActionButton>(R.id.fab_start)
        fab.setOnClickListener {
            handleFabClick()
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_github -> {
                    openGitHubRepo()
                    true
                }

                else -> false
            }
        }

        observeServiceState()
        handleIntent(intent)
    }

    private fun openGitHubRepo() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    private fun observeServiceState() {
        stateObserverJob = ServiceState.isColorPickerRunning
            .onEach { isRunning -> updateFabIcon(isRunning) }
            .launchIn(lifecycleScope)
    }

    private fun updateFabIcon(isRunning: Boolean) {
        val fab = findViewById<FloatingActionButton>(R.id.fab_start)
        fab.setImageResource(
            if (isRunning) R.drawable.ic_stop else R.drawable.ic_start
        )
    }

    private fun handleIntent(intent: Intent) {
        fromTile = intent.getBooleanExtra(EXTRA_FROM_TILE, false)
        if (fromTile) {
            handleTileClick()
        }
    }

    private fun handleFabClick() {
        val isRunning = ServiceState.isColorPickerRunning.value

        if (isRunning) {
            ServiceState.stopColorPickerService(this)
        } else {
            startColorPickerFlow()
        }
    }

    private fun handleTileClick() {
        val isRunning = ServiceState.isColorPickerRunning.value

        if (isRunning) {
            // Service is already running, this should not happen since tile handles it
            // But just in case, move to back
            moveTaskToBack(true)
        } else {
            if (Settings.canDrawOverlays(this) && canShowNotification()) {
                launchMediaProjection()
            } else {
                startColorPickerFlow()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_NOTIFICATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (fromTile) {
                        handleTileClick()
                    } else {
                        startColorPickerFlow()
                    }
                } else {
                    showNotificationPermissionDeniedDialog()
                }
            }
        }
    }

    private fun startColorPickerFlow() {
        if (Settings.canDrawOverlays(this).not()) {
            requestOverlayPermission()
            return
        }

        if (canShowNotification().not()) {
            requestNotificationPermission()
            return
        }

        launchMediaProjection()
    }

    private fun launchMediaProjection() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startColorPickerService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ColorPickerService::class.java).apply {
            putExtra(ColorPickerService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ColorPickerService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun requestOverlayPermission() {
        MaterialAlertDialogBuilder(this)
            .apply {
                setTitle(R.string.overlay_permission)
                setMessage(R.string.overlay_permission_description)
                setPositiveButton(R.string.grant) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                }
                setNeutralButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                setCancelable(false)
            }
            .show()
    }

    private fun requestNotificationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(POST_NOTIFICATIONS),
            REQUEST_CODE_NOTIFICATION
        )
    }

    private fun showNotificationPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .apply {
                setTitle(R.string.notification_permission)
                setMessage(R.string.notification_permission_description)
                setPositiveButton(R.string.grant) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                setNeutralButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                setCancelable(false)
            }
            .show()
    }
}
