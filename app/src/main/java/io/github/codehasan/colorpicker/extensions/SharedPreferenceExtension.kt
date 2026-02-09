package io.github.codehasan.colorpicker.extensions

import android.content.SharedPreferences

fun SharedPreferences.putString(key: String, value: String?) {
    edit().putString(key, value).apply()
}

fun SharedPreferences.putStringSet(key: String, value: Set<String>?) {
    edit().putStringSet(key, value).apply()
}

fun SharedPreferences.putInt(key: String, value: Int) {
    edit().putInt(key, value).apply()
}

fun SharedPreferences.putBoolean(key: String, value: Boolean) {
    edit().putBoolean(key, value).apply()
}

fun SharedPreferences.putFloat(key: String, value: Float) {
    edit().putFloat(key, value).apply()
}

fun SharedPreferences.putLong(key: String, value: Long) {
    edit().putLong(key, value).apply()
}

fun SharedPreferences.putDouble(key: String, value: Double) {
    edit().putLong(key, java.lang.Double.doubleToRawLongBits(value)).apply()
}

fun SharedPreferences.removeKey(key: String) {
    edit().remove(key).apply()
}

fun SharedPreferences.getStringOrNull(key: String): String? =
    getString(key, null)

fun SharedPreferences.getNonNullString(key: String): String =
    getString(key, "") ?: ""

fun SharedPreferences.getDouble(key: String, defaultValue: Double): Double =
    java.lang.Double.longBitsToDouble(
        getLong(key, java.lang.Double.doubleToLongBits(defaultValue))
    )

inline fun SharedPreferences.editBatch(
    commit: Boolean = false,
    block: SharedPreferences.Editor.() -> Unit
) {
    val editor = edit()
    editor.block()
    if (commit) editor.commit() else editor.apply()
}
