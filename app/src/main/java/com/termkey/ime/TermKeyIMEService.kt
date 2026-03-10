package com.termkey.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * TermKeyIMEService — the main InputMethodService.
 *
 * Architecture:
 *  - onCreateInputView() inflates keyboard_view.xml and wires all key listeners
 *  - Modifier state (Ctrl, Alt, Shift) is handled as sticky toggles
 *  - Each key sends proper KeyEvents via InputConnection so terminal emulators
 *    (Termux, ConnectBot, JuiceSSH, etc.) receive real escape sequences
 *  - Ctrl combos generate the correct control characters (e.g. Ctrl+C → 0x03)
 */
class TermKeyIMEService : InputMethodService() {

    // ── Modifier state ───────────────────────────────────────────────────────
    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var rootView: View
    private lateinit var macroScrollView: HorizontalScrollView
    private lateinit var macroContainer: LinearLayout
    private lateinit var fnRow: LinearLayout

    // ── Prefs ────────────────────────────────────────────────────────────────
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    // ── Vibrator ─────────────────────────────────────────────────────────────
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // ── AudioManager ─────────────────────────────────────────────────────────
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // ── IME lifecycle ────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_view, null)
        macroScrollView = rootView.findViewById(R.id.macro_scroll)
        macroContainer = rootView.findViewById(R.id.macro_container)
        fnRow = rootView.findViewById(R.id.fn_row)

        applyPreferences()
        buildMacroBar()
        wireKeys()

        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Reset modifier state on each new input field
        resetModifiers()
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    private fun applyPreferences() {
        val showFn = prefs.getBoolean("show_fn_row", true)
        fnRow.visibility = if (showFn) View.VISIBLE else View.GONE

        val showMacro = prefs.getBoolean("show_macro_bar", true)
        macroScrollView.visibility = if (showMacro) View.VISIBLE else View.GONE
    }

    // ── Macro bar ─────────────────────────────────────────────────────────────

    private fun buildMacroBar() {
        macroContainer.removeAllViews()
        val macros = MacroManager.getMacros(this)
        macros.forEach { macro ->
            val btn = layoutInflater.inflate(R.layout.macro_button, macroContainer, false) as TextView
            btn.text = macro.label
            btn.setOnClickListener {
                feedbackVibrate()
                feedbackSound()
                currentInputConnection?.commitText(macro.text, 1)
            }
            btn.setOnLongClickListener {
                // Long press shows edit dialog (only available outside IME context; open settings)
                true
            }
            macroContainer.addView(btn)
        }
    }

    // ── Key wiring ────────────────────────────────────────────────────────────

    private fun wireKeys() {
        // ── Modifier keys ──
        wireModifierKey(R.id.key_ctrl) {
            ctrlActive = !ctrlActive
            updateModifierUI()
        }
        wireModifierKey(R.id.key_alt) {
            altActive = !altActive
            updateModifierUI()
        }
        wireModifierKey(R.id.key_shift) {
            shiftActive = !shiftActive
            updateModifierUI()
        }

        // ── Special keys ──
        wireKey(R.id.key_esc)       { sendEscape() }
        wireKey(R.id.key_tab)       { sendTab() }
        wireKey(R.id.key_enter)     { sendEnter() }
        wireKey(R.id.key_backspace) { sendBackspace() }
        wireKey(R.id.key_space)     { sendSpace() }
        wireKey(R.id.key_delete)    { sendKeyCode(KeyEvent.KEYCODE_FORWARD_DEL) }

        // ── Navigation ──
        wireKey(R.id.key_arrow_up)    { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
        wireKey(R.id.key_arrow_down)  { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        wireKey(R.id.key_arrow_left)  { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        wireKey(R.id.key_arrow_right) { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
        wireKey(R.id.key_page_up)     { sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
        wireKey(R.id.key_page_down)   { sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        wireKey(R.id.key_home)        { sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME) }
        wireKey(R.id.key_end)         { sendKeyCode(KeyEvent.KEYCODE_MOVE_END) }

        // ── Function keys ──
        val fnKeyIds = listOf(
            R.id.key_f1  to KeyEvent.KEYCODE_F1,
            R.id.key_f2  to KeyEvent.KEYCODE_F2,
            R.id.key_f3  to KeyEvent.KEYCODE_F3,
            R.id.key_f4  to KeyEvent.KEYCODE_F4,
            R.id.key_f5  to KeyEvent.KEYCODE_F5,
            R.id.key_f6  to KeyEvent.KEYCODE_F6,
            R.id.key_f7  to KeyEvent.KEYCODE_F7,
            R.id.key_f8  to KeyEvent.KEYCODE_F8,
            R.id.key_f9  to KeyEvent.KEYCODE_F9,
            R.id.key_f10 to KeyEvent.KEYCODE_F10,
            R.id.key_f11 to KeyEvent.KEYCODE_F11,
            R.id.key_f12 to KeyEvent.KEYCODE_F12,
        )
        fnKeyIds.forEach { (viewId, keyCode) ->
            wireKey(viewId) { sendKeyCode(keyCode) }
        }

        // ── Character keys ──
        val charKeys = mapOf(
            R.id.key_grave  to Pair('`', '~'),
            R.id.key_1      to Pair('1', '!'),
            R.id.key_2      to Pair('2', '@'),
            R.id.key_3      to Pair('3', '#'),
            R.id.key_4      to Pair('4', '$'),
            R.id.key_5      to Pair('5', '%'),
            R.id.key_6      to Pair('6', '^'),
            R.id.key_7      to Pair('7', '&'),
            R.id.key_8      to Pair('8', '*'),
            R.id.key_9      to Pair('9', '('),
            R.id.key_0      to Pair('0', ')'),
            R.id.key_minus  to Pair('-', '_'),
            R.id.key_equals to Pair('=', '+'),
            R.id.key_q      to Pair('q', 'Q'),
            R.id.key_w      to Pair('w', 'W'),
            R.id.key_e      to Pair('e', 'E'),
            R.id.key_r      to Pair('r', 'R'),
            R.id.key_t      to Pair('t', 'T'),
            R.id.key_y      to Pair('y', 'Y'),
            R.id.key_u      to Pair('u', 'U'),
            R.id.key_i      to Pair('i', 'I'),
            R.id.key_o      to Pair('o', 'O'),
            R.id.key_p      to Pair('p', 'P'),
            R.id.key_lbracket to Pair('[', '{'),
            R.id.key_rbracket to Pair(']', '}'),
            R.id.key_backslash to Pair('\\', '|'),
            R.id.key_a      to Pair('a', 'A'),
            R.id.key_s      to Pair('s', 'S'),
            R.id.key_d      to Pair('d', 'D'),
            R.id.key_f_key  to Pair('f', 'F'),
            R.id.key_g      to Pair('g', 'G'),
            R.id.key_h      to Pair('h', 'H'),
            R.id.key_j      to Pair('j', 'J'),
            R.id.key_k      to Pair('k', 'K'),
            R.id.key_l      to Pair('l', 'L'),
            R.id.key_semicolon to Pair(';', ':'),
            R.id.key_quote  to Pair('\'', '"'),
            R.id.key_z      to Pair('z', 'Z'),
            R.id.key_x      to Pair('x', 'X'),
            R.id.key_c      to Pair('c', 'C'),
            R.id.key_v      to Pair('v', 'V'),
            R.id.key_b      to Pair('b', 'B'),
            R.id.key_n      to Pair('n', 'N'),
            R.id.key_m      to Pair('m', 'M'),
            R.id.key_comma  to Pair(',', '<'),
            R.id.key_period to Pair('.', '>'),
            R.id.key_slash  to Pair('/', '?'),
        )

        charKeys.forEach { (viewId, chars) ->
            val view = rootView.findViewById<View>(viewId) ?: return@forEach
            // Long-press for alternate symbol (if enabled)
            if (prefs.getBoolean("long_press_extra", true)) {
                view.setOnLongClickListener {
                    feedbackVibrate(30)
                    sendChar(chars.second)
                    true
                }
            }
            // Swipe-up gesture for alternate
            if (prefs.getBoolean("swipe_for_symbols", true)) {
                var startY = 0f
                view.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { startY = event.y; false }
                        MotionEvent.ACTION_UP -> {
                            val dy = startY - event.y
                            if (dy > 30) { // swipe up
                                feedbackVibrate(20)
                                sendChar(chars.second)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
            }
            view.setOnClickListener {
                feedbackVibrate()
                feedbackSound()
                sendChar(if (shiftActive) chars.second else chars.first)
                if (shiftActive) {
                    shiftActive = false
                    updateModifierUI()
                }
            }
        }
    }

    // ── Key sending logic ─────────────────────────────────────────────────────

    /**
     * Send a character, respecting Ctrl and Alt modifiers.
     * Ctrl+[a-z] → send control character (e.g. Ctrl+C = 0x03)
     * Alt+key    → send ESC prefix then key (standard terminal convention)
     */
    private fun sendChar(ch: Char) {
        val ic = currentInputConnection ?: return

        when {
            ctrlActive -> {
                val controlChar = when {
                    ch.lowercaseChar() in 'a'..'z' -> (ch.lowercaseChar() - 'a' + 1).toChar()
                    ch == '[' -> 0x1B.toChar()  // ESC
                    ch == '\\' -> 0x1C.toChar()
                    ch == ']' -> 0x1D.toChar()
                    ch == '^' -> 0x1E.toChar()
                    ch == '_' -> 0x1F.toChar()
                    else -> ch
                }
                ic.commitText(controlChar.toString(), 1)
                ctrlActive = false
                updateModifierUI()
            }
            altActive -> {
                // Send ESC + char (standard Meta/Alt convention for terminal)
                ic.commitText("\u001b${ch}", 1)
                altActive = false
                updateModifierUI()
            }
            else -> {
                ic.commitText(ch.toString(), 1)
            }
        }
    }

    private fun sendEscape() {
        feedbackVibrate()
        feedbackSound()
        currentInputConnection?.commitText("\u001b", 1)
        resetModifiers()
    }

    private fun sendTab() {
        feedbackVibrate()
        feedbackSound()
        val ic = currentInputConnection ?: return
        if (ctrlActive) {
            // Ctrl+Tab or just send tab
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
            ctrlActive = false
            updateModifierUI()
        } else {
            ic.commitText("\t", 1)
        }
    }

    private fun sendEnter() {
        feedbackVibrate()
        feedbackSound()
        val ic = currentInputConnection ?: return
        if (ctrlActive) {
            // Ctrl+Enter → send ctrl-m (carriage return)
            ic.commitText("\r", 1)
            ctrlActive = false
            updateModifierUI()
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun sendBackspace() {
        feedbackVibrate()
        feedbackSound()
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun sendSpace() {
        feedbackVibrate()
        feedbackSound()
        val ic = currentInputConnection ?: return
        if (ctrlActive) {
            // Ctrl+Space → NUL character (0x00), used in some apps
            ic.commitText("\u0000", 1)
            ctrlActive = false
            updateModifierUI()
        } else {
            ic.commitText(" ", 1)
        }
    }

    private fun sendKeyCode(keyCode: Int) {
        feedbackVibrate()
        feedbackSound()
        val ic = currentInputConnection ?: return
        var meta = 0
        if (ctrlActive) {
            meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            ctrlActive = false
        }
        if (altActive) {
            meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            altActive = false
        }
        if (shiftActive) {
            meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            shiftActive = false
        }
        val eventTime = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, meta))
        updateModifierUI()
    }

    // ── Modifier UI update ────────────────────────────────────────────────────

    private fun updateModifierUI() {
        rootView.findViewById<View>(R.id.key_ctrl)?.isActivated = ctrlActive
        rootView.findViewById<View>(R.id.key_alt)?.isActivated = altActive
        rootView.findViewById<View>(R.id.key_shift)?.isActivated = shiftActive

        // Update Shift key visual label if needed
        val shiftView = rootView.findViewById<TextView>(R.id.key_shift)
        shiftView?.alpha = if (shiftActive) 1.0f else 0.7f
    }

    private fun resetModifiers() {
        ctrlActive = false
        altActive = false
        shiftActive = false
        updateModifierUI()
    }

    // ── Helper: wire a click listener with feedback ───────────────────────────

    private fun wireKey(viewId: Int, action: () -> Unit) {
        rootView.findViewById<View>(viewId)?.setOnClickListener {
            feedbackVibrate()
            feedbackSound()
            action()
        }
    }

    private fun wireModifierKey(viewId: Int, action: () -> Unit) {
        rootView.findViewById<View>(viewId)?.setOnClickListener {
            feedbackVibrate(15)
            action()
        }
    }

    // ── Haptic / Audio feedback ────────────────────────────────────────────────

    private fun feedbackVibrate(ms: Long = 10) {
        if (!prefs.getBoolean("vibrate_on_keypress", true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    private fun feedbackSound() {
        if (!prefs.getBoolean("sound_on_keypress", false)) return
        audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }
}
