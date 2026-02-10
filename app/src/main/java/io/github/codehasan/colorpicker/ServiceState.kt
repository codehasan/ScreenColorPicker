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

    fun setColorPickerRunning(isRunning: Boolean) {
        _isColorPickerRunning.value = isRunning
    }

    fun stopColorPickerService(context: Context) {
        context.stopService(Intent(context, ColorPickerService::class.java))
    }
}