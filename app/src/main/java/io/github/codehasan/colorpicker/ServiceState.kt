package io.github.codehasan.colorpicker

import android.content.Context
import android.content.Intent
import io.github.codehasan.colorpicker.services.ColorPickerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceState {
    private val _isColorPickerRunning = MutableStateFlow(false)
    val isColorPickerRunning: StateFlow<Boolean> = _isColorPickerRunning.asStateFlow()

    @Synchronized
    fun setColorPickerRunning(isRunning: Boolean) {
        _isColorPickerRunning.value = isRunning
    }

    @Synchronized
    fun stopColorPickerService(context: Context) {
        // Reset state immediately to prevent race conditions
        _isColorPickerRunning.value = false
        context.stopService(Intent(context, ColorPickerService::class.java))
    }
}