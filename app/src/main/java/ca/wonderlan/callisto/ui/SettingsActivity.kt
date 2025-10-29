
package ca.wonderlan.callisto.ui

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import ca.wonderlan.callisto.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Prompt threshold: format title with the number and force numeric input.
        val thresholdPref = findPreference<EditTextPreference>("prompt_threshold")
        fun applyTitle(value: String?) {
            val n = value?.toIntOrNull() ?: 200
            thresholdPref?.title = getString(R.string.pref_prompt_threshold_label, n)
        }
        thresholdPref?.let { pref ->
            applyTitle(pref.text)
            pref.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            pref.setOnPreferenceChangeListener { _, newValue ->
                applyTitle(newValue as? String)
                true
            }
        }
    }
}
