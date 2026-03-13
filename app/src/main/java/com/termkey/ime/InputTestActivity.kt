package com.termkey.ime

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService

class InputTestActivity : AppCompatActivity() {

    private lateinit var inputField: EditText
    private lateinit var showPickerButton: Button
    private lateinit var refocusButton: Button
    private var pendingKeyboardShow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_test)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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

    private fun focusInputField(showKeyboard: Boolean = true) {
        inputField.requestFocus()
        inputField.post {
            if (showKeyboard) {
                getSystemService<InputMethodManager>()?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
