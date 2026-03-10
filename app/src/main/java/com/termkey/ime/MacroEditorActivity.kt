package com.termkey.ime

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MacroEditorActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var addButton: MaterialButton

    private val macros = mutableListOf<Macro>()
    private lateinit var adapter: MacroListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_macro_editor)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.macro_editor_title)

        listView = findViewById(R.id.macro_list)
        emptyView = findViewById(R.id.macro_empty)
        addButton = findViewById(R.id.button_add_macro)

        macros.clear()
        macros.addAll(MacroManager.getMacros(this))

        adapter = MacroListAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyView

        addButton.setOnClickListener {
            showMacroDialog()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun showMacroDialog(existingIndex: Int? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_macro_edit, null)
        val labelInput = dialogView.findViewById<EditText>(R.id.input_macro_label)
        val textInput = dialogView.findViewById<EditText>(R.id.input_macro_text)

        if (existingIndex != null) {
            val macro = macros[existingIndex]
            labelInput.setText(macro.label)
            textInput.setText(macro.text)
        }

        val titleRes = if (existingIndex == null) {
            R.string.macro_editor_add_title
        } else {
            R.string.macro_editor_edit_title
        }

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(dialogView)
            .setPositiveButton(R.string.macro_editor_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (existingIndex != null) {
                    setNeutralButton(R.string.macro_editor_delete, null)
                }
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val label = labelInput.text?.toString()?.trim().orEmpty()
                        val text = textInput.text?.toString().orEmpty()

                        var hasError = false
                        if (label.isEmpty()) {
                            labelInput.error = getString(R.string.macro_editor_error_label)
                            hasError = true
                        }
                        if (text.isBlank()) {
                            textInput.error = getString(R.string.macro_editor_error_text)
                            hasError = true
                        }
                        if (hasError) return@setOnClickListener

                        if (existingIndex == null) {
                            macros.add(Macro(label, text))
                            Toast.makeText(this, R.string.macro_editor_added, Toast.LENGTH_SHORT).show()
                        } else {
                            macros[existingIndex] = Macro(label, text)
                            Toast.makeText(this, R.string.macro_editor_updated, Toast.LENGTH_SHORT).show()
                        }
                        persistMacros()
                        dialog.dismiss()
                    }

                    if (existingIndex != null) {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            macros.removeAt(existingIndex)
                            persistMacros()
                            Toast.makeText(this, R.string.macro_editor_deleted, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun persistMacros() {
        MacroManager.saveMacros(this, macros)
        adapter.notifyDataSetChanged()
    }

    private inner class MacroListAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@MacroEditorActivity)

        override fun getCount(): Int = macros.size

        override fun getItem(position: Int): Macro = macros[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_macro, parent, false)
            val macro = getItem(position)

            view.findViewById<TextView>(R.id.macro_label).text = macro.label
            view.findViewById<TextView>(R.id.macro_text).text = macro.text
            view.findViewById<View>(R.id.button_edit_macro).setOnClickListener {
                showMacroDialog(position)
            }

            return view
        }
    }
}
