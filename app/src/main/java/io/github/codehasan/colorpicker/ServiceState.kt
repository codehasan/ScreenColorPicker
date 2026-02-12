/*
 * Copyright (c) 2026 Ratul Hasan
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 */
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