package com.termkey.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
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
        companion object {
            private const val AUDIO_PERMISSION_REQUEST = 1001
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            configureVoicePreferences()

            findPreference<Preference>("edit_macros")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), MacroEditorActivity::class.java))
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

        override fun onResume() {
            super.onResume()
            updateVoicePermissionSummary()
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray,
        ) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            if (requestCode != AUDIO_PERMISSION_REQUEST) return

            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            updateVoicePermissionSummary()
            Toast.makeText(
                context,
                if (granted) R.string.voice_permission_granted else R.string.voice_permission_denied,
                Toast.LENGTH_SHORT,
            ).show()
        }

        private fun configureVoicePreferences() {
            findPreference<Preference>("voice_request_permission")?.setOnPreferenceClickListener {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
                true
            }

            findPreference<EditTextPreference>("voice_volc_app_key")?.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    pref.text?.takeIf { it.isNotBlank() } ?: getString(R.string.pref_value_not_set)
                }
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine(true)
                }
            }

            findPreference<EditTextPreference>("voice_volc_access_key")?.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    if (pref.text.isNullOrBlank()) {
                        getString(R.string.pref_value_not_set)
                    } else {
                        getString(R.string.pref_value_configured)
                    }
                }
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine(true)
                }
            }

            findPreference<EditTextPreference>("voice_volc_resource_id")?.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    pref.text?.takeIf { it.isNotBlank() } ?: getString(R.string.pref_value_not_set)
                }
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine(true)
                }
            }

            findPreference<EditTextPreference>("voice_language")?.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    pref.text?.takeIf { it.isNotBlank() } ?: "zh-CN"
                }
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine(true)
                }
            }
        }

        private fun updateVoicePermissionSummary() {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            findPreference<Preference>("voice_request_permission")?.summary =
                getString(
                    if (granted) R.string.pref_voice_permission_granted_summary
                    else R.string.pref_voice_permission_summary,
                )
        }
    }
}
