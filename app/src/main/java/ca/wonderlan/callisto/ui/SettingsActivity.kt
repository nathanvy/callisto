// app/src/main/java/ca/wonderlan/callisto/ui/SettingsActivity.kt
// Replace the file content with only the fragment:

package ca.wonderlan.callisto.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import ca.wonderlan.callisto.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }
}
