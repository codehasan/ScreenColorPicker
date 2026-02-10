package io.github.codehasan.colorpicker

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class MainPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }
}
