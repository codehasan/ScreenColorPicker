package io.github.codehasan.colorpicker.services

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import io.github.codehasan.colorpicker.MainActivity
import io.github.codehasan.colorpicker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ColorPickerTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateObserverJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = ServiceState.isColorPickerRunning.value

        if (isRunning) {
            ServiceState.stopColorPickerService(this)
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_FROM_TILE, true)
            }
            val wrapper = PendingIntentActivityWrapper(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                false
            )
            TileServiceCompat.startActivityAndCollapse(this, wrapper)
        }
    }

    private fun updateTileState() {
        stateObserverJob = ServiceState.isColorPickerRunning
            .onEach { isRunning -> updateTile(isRunning) }
            .launchIn(serviceScope)
    }

    private fun updateTile(isRunning: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.stateDescription =
                if (isRunning) getString(R.string.running) else getString(R.string.stopped)
        }
        tile.updateTile()
    }
}
