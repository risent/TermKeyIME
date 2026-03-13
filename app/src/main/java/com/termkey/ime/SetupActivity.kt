package com.termkey.ime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService

class SetupActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnEnable: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnSettings: Button
    private lateinit var btnTestPage: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        statusText = findViewById(R.id.setup_status)
        btnEnable = findViewById(R.id.btn_enable_ime)
        btnSwitch = findViewById(R.id.btn_switch_ime)
        btnSettings = findViewById(R.id.btn_open_settings)
        btnTestPage = findViewById(R.id.btn_open_test_page)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSwitch.setOnClickListener {
            getSystemService<InputMethodManager>()?.showInputMethodPicker()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnTestPage.setOnClickListener {
            startActivity(Intent(this, InputTestActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val imm = getSystemService<InputMethodManager>() ?: return
        val packageName = packageName

        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val isSelected = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        )?.contains(packageName) == true

        when {
            isSelected -> {
                statusText.text = getString(R.string.setup_done)
                statusText.setTextColor(getColor(R.color.terminal_green))
            }
            isEnabled -> {
                statusText.text = "TermKey is enabled. Now switch to it as your active keyboard."
                statusText.setTextColor(getColor(R.color.terminal_amber))
            }
            else -> {
                statusText.text = "TermKey is not yet enabled. Complete the steps below."
                statusText.setTextColor(getColor(R.color.terminal_text_dim))
            }
        }

        btnEnable.isEnabled = !isEnabled
        btnSwitch.isEnabled = isEnabled
    }
}
