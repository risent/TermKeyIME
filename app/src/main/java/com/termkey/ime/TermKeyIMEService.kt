package com.termkey.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AlphaAnimation
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    companion object {
        private const val TAG = "TermKeyVoice"
    }

    // ── Modifier state ───────────────────────────────────────────────────────
    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var rootView: View
    private lateinit var macroScrollView: HorizontalScrollView
    private lateinit var macroContainer: LinearLayout
    private lateinit var fnRow: LinearLayout
    private lateinit var voiceKey: TextView

    // ── Prefs ────────────────────────────────────────────────────────────────
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var voiceClient: VolcengineVoiceInputClient? = null
    private var voiceListening = false
    private var voiceStarting = false
    private var voiceStopping = false
    private var voicePreviewText = ""
    private var voicePreviewUsesComposing = false
    private var voiceBlinkAnimation: AlphaAnimation? = null

    // ── Vibrator ─────────────────────────────────────────────────────────────
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── AudioManager ─────────────────────────────────────────────────────────
    private val audioManager: AudioManager? by lazy {
        try {
            getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (_: Exception) {
            null
        }
    }

    // ── IME lifecycle ────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_view, null)
        macroScrollView = rootView.findViewById(R.id.macro_scroll)
        macroContainer = rootView.findViewById(R.id.macro_container)
        fnRow = rootView.findViewById(R.id.fn_row)
        voiceKey = rootView.findViewById(R.id.key_mic)

        initializeKeyLabels()
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

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelVoiceInput()
    }

    override fun onDestroy() {
        cancelVoiceInput()
        super.onDestroy()
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    private fun applyPreferences() {
        val showFn = prefs.getBoolean("show_fn_row", true)
        fnRow.visibility = if (showFn) View.VISIBLE else View.GONE

        val showMacro = prefs.getBoolean("show_macro_bar", true)
        macroScrollView.visibility = if (showMacro) View.VISIBLE else View.GONE

        val showVoice = prefs.getBoolean("show_voice_key", true)
        voiceKey.visibility = if (showVoice) View.VISIBLE else View.GONE
        updateVoiceKeyUI()
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

    private fun initializeKeyLabels() {
        val labels = mapOf(
            R.id.key_grave to "`",
            R.id.key_1 to "1",
            R.id.key_2 to "2",
            R.id.key_3 to "3",
            R.id.key_4 to "4",
            R.id.key_5 to "5",
            R.id.key_6 to "6",
            R.id.key_7 to "7",
            R.id.key_8 to "8",
            R.id.key_9 to "9",
            R.id.key_0 to "0",
            R.id.key_minus to "-",
            R.id.key_equals to "=",
            R.id.key_q to "Q",
            R.id.key_w to "W",
            R.id.key_e to "E",
            R.id.key_r to "R",
            R.id.key_t to "T",
            R.id.key_y to "Y",
            R.id.key_u to "U",
            R.id.key_i to "I",
            R.id.key_o to "O",
            R.id.key_p to "P",
            R.id.key_lbracket to "[",
            R.id.key_rbracket to "]",
            R.id.key_backslash to "\\",
            R.id.key_a to "A",
            R.id.key_s to "S",
            R.id.key_d to "D",
            R.id.key_f_key to "F",
            R.id.key_g to "G",
            R.id.key_h to "H",
            R.id.key_j to "J",
            R.id.key_k to "K",
            R.id.key_l to "L",
            R.id.key_semicolon to ";",
            R.id.key_quote to "'",
            R.id.key_z to "Z",
            R.id.key_x to "X",
            R.id.key_c to "C",
            R.id.key_v to "V",
            R.id.key_b to "B",
            R.id.key_n to "N",
            R.id.key_m to "M",
            R.id.key_comma to ",",
            R.id.key_period to ".",
            R.id.key_slash to "/",
            R.id.key_f1 to "F1",
            R.id.key_f2 to "F2",
            R.id.key_f3 to "F3",
            R.id.key_f4 to "F4",
            R.id.key_f5 to "F5",
            R.id.key_f6 to "F6",
            R.id.key_f7 to "F7",
            R.id.key_f8 to "F8",
            R.id.key_f9 to "F9",
            R.id.key_f10 to "F10",
            R.id.key_f11 to "F11",
            R.id.key_f12 to "F12",
            R.id.key_mic to getString(R.string.key_mic_idle),
        )

        labels.forEach { (viewId, label) ->
            rootView.findViewById<TextView>(viewId)?.text = label
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
        rootView.findViewById<View>(R.id.key_mic)?.setOnClickListener {
            feedbackVibrate()
            feedbackSound()
            toggleVoiceInput()
        }

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
        updateVoiceKeyUI()
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

    private fun toggleVoiceInput() {
        Log.d(TAG, "MIC tapped voiceStarting=$voiceStarting voiceListening=$voiceListening voiceStopping=$voiceStopping")
        if (voiceStarting || voiceListening || voiceStopping) {
            showToast(R.string.voice_stopping)
            requestVoiceStop()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MIC tap blocked: RECORD_AUDIO permission missing")
            showToast(R.string.voice_missing_permission)
            return
        }

        val config = buildVoiceConfig()
        if (config == null) {
            Log.w(TAG, "MIC tap blocked: missing Volcengine config")
            showToast(R.string.voice_missing_config)
            return
        }

        voiceClient = VolcengineVoiceInputClient(
            config = config,
            listener = object : VolcengineVoiceInputClient.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    Log.d(TAG, "Voice state changed isListening=$isListening")
                    voiceStarting = false
                    voiceListening = isListening
                    if (!isListening) {
                        voiceStopping = false
                        voiceClient = null
                    }
                    updateVoiceKeyUI()
                    if (isListening) {
                        showToast(R.string.voice_listening)
                    }
                }

                override fun onPartialResult(text: String) {
                    renderVoicePreview(text)
                }

                override fun onFinalResult(text: String) {
                    Log.d(TAG, "Voice final result length=${text.length}")
                    voiceStarting = false
                    voiceStopping = false
                    voiceListening = false
                    voiceClient = null
                    commitVoicePreview(text)
                    updateVoiceKeyUI()
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Voice error callback: $message")
                    voiceStarting = false
                    voiceStopping = false
                    voiceListening = false
                    voiceClient = null
                    finishVoicePreview()
                    updateVoiceKeyUI()
                    showToast("${getString(R.string.voice_error_prefix)} $message", Toast.LENGTH_LONG)
                }
            },
        )
        voiceStarting = true
        voiceStopping = false
        updateVoiceKeyUI()
        voiceClient?.start()
    }

    private fun requestVoiceStop() {
        voiceStarting = false
        voiceStopping = true
        updateVoiceKeyUI()
        voiceClient?.stop()
    }

    private fun cancelVoiceInput() {
        voiceClient?.cancel()
        voiceClient = null
        voiceStarting = false
        voiceStopping = false
        voiceListening = false
        finishVoicePreview()
        updateVoiceKeyUI()
    }

    private fun buildVoiceConfig(): VolcengineVoiceInputClient.Config? {
        val appKey = prefs.getString("voice_volc_app_key", null)?.trim().orEmpty()
        val accessToken = prefs.getString("voice_volc_access_key", null)?.trim().orEmpty()
        val resourceId = prefs.getString("voice_volc_resource_id", null)?.trim().orEmpty()
        val language = prefs.getString("voice_language", "zh-CN")?.trim().orEmpty().ifBlank { "zh-CN" }

        if (appKey.isBlank() || accessToken.isBlank() || resourceId.isBlank()) {
            Log.w(
                TAG,
                "Incomplete voice config appKey=${appKey.isNotBlank()} accessToken=${accessToken.isNotBlank()} resourceId=${resourceId.isNotBlank()}",
            )
            return null
        }

        return VolcengineVoiceInputClient.Config(
            appKey = appKey,
            accessToken = accessToken,
            resourceId = resourceId,
            language = language,
        )
    }

    private fun updateVoiceKeyUI() {
        if (!::voiceKey.isInitialized) return
        val active = voiceStarting || voiceListening || voiceStopping
        voiceKey.isActivated = active
        voiceKey.alpha = if (active) 1.0f else 0.85f
        voiceKey.text = getString(
            when {
                voiceStopping -> R.string.key_mic_connecting
                voiceListening -> R.string.key_mic_recording
                voiceStarting -> R.string.key_mic_connecting
                else -> R.string.key_mic_idle
            },
        )
        syncVoiceBlinking()
    }

    private fun syncVoiceBlinking() {
        if (!::voiceKey.isInitialized) return
        if (voiceListening && !voiceStopping) {
            if (voiceBlinkAnimation == null) {
                voiceBlinkAnimation = AlphaAnimation(1.0f, 0.35f).apply {
                    duration = 700
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                    interpolator = LinearInterpolator()
                }
            }
            if (voiceKey.animation == null) {
                voiceKey.startAnimation(voiceBlinkAnimation)
            }
        } else {
            voiceKey.clearAnimation()
        }
    }

    private fun renderVoicePreview(text: String) {
        val preview = text.trim()
        if (preview.isEmpty() || preview == voicePreviewText) return

        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            if (!voicePreviewUsesComposing && voicePreviewText.isNotEmpty()) {
                ic.deleteSurroundingText(voicePreviewText.length, 0)
            }

            val composingApplied = runCatching { ic.setComposingText(preview, 1) }.getOrDefault(false)
            if (composingApplied) {
                voicePreviewUsesComposing = true
                voicePreviewText = preview
            } else {
                ic.commitText(preview, 1)
                voicePreviewUsesComposing = false
                voicePreviewText = preview
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun commitVoicePreview(finalText: String) {
        val resolvedText = finalText.trim()
        val ic = currentInputConnection
        if (ic == null) {
            resetVoicePreviewState()
            return
        }

        ic.beginBatchEdit()
        try {
            when {
                voicePreviewUsesComposing && resolvedText.isNotEmpty() -> ic.commitText(resolvedText, 1)
                voicePreviewUsesComposing && voicePreviewText.isNotEmpty() -> ic.finishComposingText()
                !voicePreviewUsesComposing && voicePreviewText.isNotEmpty() -> {
                    if (resolvedText.isNotEmpty() && resolvedText != voicePreviewText) {
                        ic.deleteSurroundingText(voicePreviewText.length, 0)
                        ic.commitText(resolvedText, 1)
                    }
                }
                resolvedText.isNotEmpty() -> ic.commitText(resolvedText, 1)
            }
        } finally {
            ic.endBatchEdit()
            resetVoicePreviewState()
        }
    }

    private fun finishVoicePreview() {
        val ic = currentInputConnection
        if (ic != null && voicePreviewUsesComposing && voicePreviewText.isNotEmpty()) {
            ic.beginBatchEdit()
            try {
                ic.finishComposingText()
            } finally {
                ic.endBatchEdit()
            }
        }
        resetVoicePreviewState()
    }

    private fun resetVoicePreviewState() {
        voicePreviewText = ""
        voicePreviewUsesComposing = false
    }

    // ── Haptic / Audio feedback ────────────────────────────────────────────────

    private fun feedbackVibrate(ms: Long = 10) {
        if (!prefs.getBoolean("vibrate_on_keypress", true)) return
        val vib = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: SecurityException) {
            // Ignore unavailable/blocked vibration instead of crashing the IME.
        } catch (_: Exception) {
            // Some devices expose inconsistent vibrator services.
        }
    }

    private fun feedbackSound() {
        if (!prefs.getBoolean("sound_on_keypress", false)) return
        try {
            audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
        } catch (_: Exception) {
            // Ignore sound-effect failures instead of crashing the IME.
        }
    }

    private fun showToast(text: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, text, duration).show()
    }

    private fun showToast(resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, resId, duration).show()
    }
}
