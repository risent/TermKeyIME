package com.termkey.ime

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager

class InputTestActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FORCE_CHINESE = "force_chinese"
        const val PREF_FORCE_CHINESE_FOR_INPUT_TEST = "debug_input_test_force_chinese"
    }

    private lateinit var inputField: EditText
    private lateinit var showPickerButton: Button
    private lateinit var refocusButton: Button
    private var pendingKeyboardShow = false
    private var wroteForceChinesePref = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_test)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        handleIntent(intent)

        inputField = findViewById(R.id.test_input_field)
        showPickerButton = findViewById(R.id.btn_show_ime_picker)
        refocusButton = findViewById(R.id.btn_refocus_input)

        showPickerButton.setOnClickListener {
            getSystemService<InputMethodManager>()?.showInputMethodPicker()
        }

        refocusButton.setOnClickListener {
            focusInputField()
        }
    }

    override fun onResume() {
        super.onResume()
        pendingKeyboardShow = true
        focusInputField(showKeyboard = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingKeyboardShow) {
            pendingKeyboardShow = false
            focusInputField(showKeyboard = true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (wroteForceChinesePref) {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .remove(PREF_FORCE_CHINESE_FOR_INPUT_TEST)
                .apply()
        }
        super.onDestroy()
    }

    private fun focusInputField(showKeyboard: Boolean = true) {
        inputField.requestFocus()
        inputField.post {
            if (showKeyboard) {
                getSystemService<InputMethodManager>()?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.hasExtra(EXTRA_FORCE_CHINESE) != true) return
        wroteForceChinesePref = true
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(PREF_FORCE_CHINESE_FOR_INPUT_TEST, intent.getBooleanExtra(EXTRA_FORCE_CHINESE, false))
            .apply()
    }
}
