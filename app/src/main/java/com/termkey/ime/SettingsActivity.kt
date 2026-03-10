package com.termkey.ime

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("edit_macros")?.setOnPreferenceClickListener {
                // In a real app: open a RecyclerView-based macro editor activity
                Toast.makeText(context, "Macro editor coming in v1.1", Toast.LENGTH_SHORT).show()
                true
            }

            findPreference<Preference>("reset_macros")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Reset Macros")
                    .setMessage("Restore all macros to their defaults? This cannot be undone.")
                    .setPositiveButton("Reset") { _, _ ->
                        MacroManager.resetToDefaults(requireContext())
                        Toast.makeText(context, "Macros reset to defaults.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
    }
}
