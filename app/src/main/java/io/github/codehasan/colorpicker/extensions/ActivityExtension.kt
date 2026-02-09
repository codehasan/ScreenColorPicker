package io.github.codehasan.colorpicker.extensions

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

fun AppCompatActivity.canShowNotification(): Boolean =
    ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun AppCompatActivity.showMessage(@StringRes message: Int) = showMessage(getString(message))

fun AppCompatActivity.showMessage(message: String) = Snackbar.make(
    window.decorView.rootView, message,
    Snackbar.LENGTH_SHORT
)