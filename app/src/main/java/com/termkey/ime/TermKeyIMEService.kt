package com.termkey.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
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
    private data class RowTouchState(
        val bounds: Rect,
        val keys: List<Pair<View, Rect>>,
    )

    companion object {
        private const val TAG = "TermKeyVoice"
        private const val DELETE_REPEAT_INITIAL_DELAY_MS = 350L
        private const val DELETE_REPEAT_INTERVAL_MS = 60L
        private const val CLEAR_TEXT_CHUNK_SIZE = 1024

        private val ALL_KEY_IDS = intArrayOf(
            R.id.key_grave,
            R.id.key_1,
            R.id.key_2,
            R.id.key_3,
            R.id.key_4,
            R.id.key_5,
            R.id.key_6,
            R.id.key_7,
            R.id.key_8,
            R.id.key_9,
            R.id.key_0,
            R.id.key_minus,
            R.id.key_equals,
            R.id.key_backspace,
            R.id.key_tab,
            R.id.key_lbracket,
            R.id.key_q,
            R.id.key_w,
            R.id.key_e,
            R.id.key_r,
            R.id.key_t,
            R.id.key_y,
            R.id.key_u,
            R.id.key_i,
            R.id.key_o,
            R.id.key_p,
            R.id.key_rbracket,
            R.id.key_backslash,
            R.id.key_ctrl,
            R.id.key_semicolon,
            R.id.key_a,
            R.id.key_s,
            R.id.key_d,
            R.id.key_f_key,
            R.id.key_g,
            R.id.key_h,
            R.id.key_j,
            R.id.key_k,
            R.id.key_l,
            R.id.key_quote,
            R.id.key_enter,
            R.id.key_esc,
            R.id.key_alt,
            R.id.key_comma,
            R.id.key_z,
            R.id.key_x,
            R.id.key_c,
            R.id.key_v,
            R.id.key_b,
            R.id.key_n,
            R.id.key_m,
            R.id.key_period,
            R.id.key_slash,
            R.id.key_arrow_up,
            R.id.key_shift,
            R.id.key_lang,
            R.id.key_page_up,
            R.id.key_page_down,
            R.id.key_home,
            R.id.key_end,
            R.id.key_space,
            R.id.key_delete,
            R.id.key_mic,
            R.id.key_arrow_left,
            R.id.key_arrow_down,
            R.id.key_arrow_right,
        )

        private val COMPACT_ZH_KEY_IDS = setOf(
            R.id.key_q,
            R.id.key_w,
            R.id.key_e,
            R.id.key_r,
            R.id.key_t,
            R.id.key_y,
            R.id.key_u,
            R.id.key_i,
            R.id.key_o,
            R.id.key_p,
            R.id.key_a,
            R.id.key_s,
            R.id.key_d,
            R.id.key_f_key,
            R.id.key_g,
            R.id.key_h,
            R.id.key_j,
            R.id.key_k,
            R.id.key_l,
            R.id.key_esc,
            R.id.key_z,
            R.id.key_x,
            R.id.key_c,
            R.id.key_v,
            R.id.key_b,
            R.id.key_n,
            R.id.key_m,
            R.id.key_arrow_up,
            R.id.key_shift,
            R.id.key_lang,
            R.id.key_home,
            R.id.key_end,
            R.id.key_space,
            R.id.key_delete,
            R.id.key_mic,
            R.id.key_arrow_right,
        )

        private val COMPACT_EN_KEY_IDS = setOf(
            R.id.key_1,
            R.id.key_2,
            R.id.key_3,
            R.id.key_4,
            R.id.key_5,
            R.id.key_6,
            R.id.key_7,
            R.id.key_8,
            R.id.key_9,
            R.id.key_0,
            R.id.key_minus,
            R.id.key_equals,
            R.id.key_backspace,
            R.id.key_q,
            R.id.key_w,
            R.id.key_e,
            R.id.key_r,
            R.id.key_t,
            R.id.key_y,
            R.id.key_u,
            R.id.key_i,
            R.id.key_o,
            R.id.key_p,
            R.id.key_a,
            R.id.key_s,
            R.id.key_d,
            R.id.key_f_key,
            R.id.key_g,
            R.id.key_h,
            R.id.key_j,
            R.id.key_k,
            R.id.key_l,
            R.id.key_esc,
            R.id.key_z,
            R.id.key_x,
            R.id.key_c,
            R.id.key_v,
            R.id.key_b,
            R.id.key_n,
            R.id.key_m,
            R.id.key_arrow_up,
            R.id.key_shift,
            R.id.key_lang,
            R.id.key_home,
            R.id.key_end,
            R.id.key_space,
            R.id.key_delete,
            R.id.key_mic,
            R.id.key_arrow_right,
        )

        private val COMPACT_SYMBOL_KEY_IDS = setOf(
            R.id.key_grave,
            R.id.key_1,
            R.id.key_2,
            R.id.key_3,
            R.id.key_4,
            R.id.key_5,
            R.id.key_6,
            R.id.key_7,
            R.id.key_8,
            R.id.key_9,
            R.id.key_0,
            R.id.key_minus,
            R.id.key_equals,
            R.id.key_backspace,
            R.id.key_q,
            R.id.key_w,
            R.id.key_e,
            R.id.key_r,
            R.id.key_t,
            R.id.key_y,
            R.id.key_u,
            R.id.key_i,
            R.id.key_o,
            R.id.key_p,
            R.id.key_lbracket,
            R.id.key_rbracket,
            R.id.key_backslash,
            R.id.key_a,
            R.id.key_s,
            R.id.key_d,
            R.id.key_f_key,
            R.id.key_g,
            R.id.key_h,
            R.id.key_j,
            R.id.key_k,
            R.id.key_l,
            R.id.key_semicolon,
            R.id.key_quote,
            R.id.key_enter,
            R.id.key_z,
            R.id.key_x,
            R.id.key_c,
            R.id.key_v,
            R.id.key_b,
            R.id.key_n,
            R.id.key_m,
            R.id.key_comma,
            R.id.key_period,
            R.id.key_slash,
            R.id.key_lang,
            R.id.key_home,
            R.id.key_space,
            R.id.key_mic,
            R.id.key_arrow_right,
        )

        private val SYMBOL_KEY_LABELS = mapOf(
            R.id.key_grave to "~",
            R.id.key_1 to "!",
            R.id.key_2 to "@",
            R.id.key_3 to "#",
            R.id.key_4 to "￥",
            R.id.key_5 to "%",
            R.id.key_6 to "…",
            R.id.key_7 to "&",
            R.id.key_8 to "*",
            R.id.key_9 to "(",
            R.id.key_0 to ")",
            R.id.key_minus to "_",
            R.id.key_equals to "+",
            R.id.key_q to "[",
            R.id.key_w to "]",
            R.id.key_e to "{",
            R.id.key_r to "}",
            R.id.key_t to "^",
            R.id.key_y to "|",
            R.id.key_u to "<",
            R.id.key_i to ">",
            R.id.key_o to "《",
            R.id.key_p to "》",
            R.id.key_lbracket to "「",
            R.id.key_rbracket to "」",
            R.id.key_backslash to "·",
            R.id.key_a to ":",
            R.id.key_s to ";",
            R.id.key_d to "\"",
            R.id.key_f_key to "'",
            R.id.key_g to "/",
            R.id.key_h to "\\",
            R.id.key_j to "=",
            R.id.key_k to "±",
            R.id.key_l to "×",
            R.id.key_semicolon to "÷",
            R.id.key_quote to "~",
            R.id.key_z to "，",
            R.id.key_x to "。",
            R.id.key_c to "？",
            R.id.key_v to "！",
            R.id.key_b to "：",
            R.id.key_n to "；",
            R.id.key_m to "、",
            R.id.key_comma to "《",
            R.id.key_period to "》",
            R.id.key_slash to "·",
        )
    }

    private enum class KeyboardLayoutMode {
        FULL,
        COMPACT_ZH,
        COMPACT_EN,
        COMPACT_SYMBOL,
    }

    // ── Modifier state ───────────────────────────────────────────────────────
    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var rootView: View
    private lateinit var macroScrollView: HorizontalScrollView
    private lateinit var macroContainer: LinearLayout
    private lateinit var macroSeparator: View
    private lateinit var candidateRawCodeView: TextView
    private lateinit var candidateScrollView: HorizontalScrollView
    private lateinit var candidateContainer: LinearLayout
    private lateinit var fnRow: LinearLayout
    private lateinit var keyRows: List<LinearLayout>
    private lateinit var languageKey: TextView
    private lateinit var voiceKey: TextView
    private lateinit var llmPanel: View
    private lateinit var llmPanelTitle: TextView
    private lateinit var llmPanelStatus: TextView
    private lateinit var llmPanelSource: TextView
    private lateinit var llmOptionScroll: HorizontalScrollView
    private lateinit var llmOptionContainer: LinearLayout
    private lateinit var llmPanelResult: TextView
    private lateinit var llmActionReplace: TextView
    private lateinit var llmActionRetry: TextView
    private lateinit var llmActionCancel: TextView
    private lateinit var adaptiveTouchDelegate: AdaptiveTouchDelegateGroup

    // ── Prefs ────────────────────────────────────────────────────────────────
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val chineseEngine by lazy { NaturalShuangpinEngine(ChineseLexiconStore(applicationContext)) }
    private var latestChineseState: ChineseInputState? = null
    private var chineseMode = false
    private var layoutMode = KeyboardLayoutMode.COMPACT_EN
    private var previousCompactLayoutMode = KeyboardLayoutMode.COMPACT_EN
    private var voiceClient: VolcengineVoiceInputClient? = null
    private var voiceListening = false
    private var voiceStarting = false
    private var voiceStopping = false
    private var voicePreviewText = ""
    private var voicePreviewUsesComposing = false
    private var voiceBlinkAnimation: AlphaAnimation? = null
    private var llmClient: OpenAiCompatLlmClient? = null
    private var llmPreviewState: LlmPreviewState? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val adaptiveTouchRefreshRunnable = Runnable { rebuildAdaptiveTouchTargets() }

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
        macroSeparator = rootView.findViewById(R.id.macro_separator)
        candidateRawCodeView = rootView.findViewById(R.id.candidate_raw_code)
        candidateScrollView = rootView.findViewById(R.id.candidate_scroll)
        candidateContainer = rootView.findViewById(R.id.candidate_container)
        fnRow = rootView.findViewById(R.id.fn_row)
        keyRows = listOf(
            rootView.findViewById(R.id.key_row_1),
            rootView.findViewById(R.id.key_row_2),
            rootView.findViewById(R.id.key_row_3),
            rootView.findViewById(R.id.key_row_4),
            rootView.findViewById(R.id.key_row_5),
        )
        languageKey = rootView.findViewById(R.id.key_lang)
        voiceKey = rootView.findViewById(R.id.key_mic)
        llmPanel = rootView.findViewById(R.id.llm_panel)
        llmPanelTitle = rootView.findViewById(R.id.llm_panel_title)
        llmPanelStatus = rootView.findViewById(R.id.llm_panel_status)
        llmPanelSource = rootView.findViewById(R.id.llm_panel_source)
        llmOptionScroll = rootView.findViewById(R.id.llm_option_scroll)
        llmOptionContainer = rootView.findViewById(R.id.llm_option_container)
        llmPanelResult = rootView.findViewById(R.id.llm_panel_result)
        llmActionReplace = rootView.findViewById(R.id.llm_action_replace)
        llmActionRetry = rootView.findViewById(R.id.llm_action_retry)
        llmActionCancel = rootView.findViewById(R.id.llm_action_cancel)

        initializeKeyLabels()
        applyPreferences()
        buildMacroBar()
        wireKeys()
        wireLlmPanel()
        adaptiveTouchDelegate = AdaptiveTouchDelegateGroup(rootView)
        rootView.touchDelegate = adaptiveTouchDelegate
        scheduleAdaptiveTouchTargetsUpdate()

        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Reset modifier state on each new input field
        resetModifiers()
        clearChineseInput(commitCurrent = false)
        dismissLlmPanel(cancelRequest = true)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopDeleteRepeats()
        cancelVoiceInput()
        dismissLlmPanel(cancelRequest = true)
        clearChineseInput(commitCurrent = false)
    }

    override fun onDestroy() {
        stopDeleteRepeats()
        cancelVoiceInput()
        dismissLlmPanel(cancelRequest = true)
        clearChineseInput(commitCurrent = false)
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshLlmPreviewValidity()
        refreshCandidateArea()
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    private fun applyPreferences() {
        refreshCandidateArea()
        updateKeyboardLayoutUi()
        updateVoiceKeyUI()
        updateLlmPanelUi()
        scheduleAdaptiveTouchTargetsUpdate()
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

    private fun baseCharacterKeyLabels() = mapOf(
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
            R.id.key_lbracket to "[",
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
            R.id.key_rbracket to "]",
            R.id.key_backslash to "\\",
            R.id.key_semicolon to ";",
            R.id.key_a to "A",
            R.id.key_s to "S",
            R.id.key_d to "D",
            R.id.key_f_key to "F",
            R.id.key_g to "G",
            R.id.key_h to "H",
            R.id.key_j to "J",
            R.id.key_k to "K",
            R.id.key_l to "L",
            R.id.key_quote to "'",
            R.id.key_comma to ",",
            R.id.key_z to "Z",
            R.id.key_x to "X",
            R.id.key_c to "C",
            R.id.key_v to "V",
            R.id.key_b to "B",
            R.id.key_n to "N",
            R.id.key_m to "M",
            R.id.key_period to ".",
            R.id.key_slash to "/",
        )

    private fun initializeKeyLabels() {
        val labels = baseCharacterKeyLabels() + mapOf(
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
            R.id.key_lang to getString(R.string.key_lang_en),
            R.id.key_mic to getString(R.string.key_mic_idle),
        )

        labels.forEach { (viewId, label) ->
            rootView.findViewById<TextView>(viewId)?.text = label
        }
    }

    // ── Key wiring ────────────────────────────────────────────────────────────

    private fun wireKeys() {
        // ── Modifier keys ──
        rootView.findViewById<View>(R.id.key_ctrl)?.setOnClickListener {
            if (handleCompactPunctuationKey(R.id.key_ctrl)) {
                feedbackVibrate(16)
                feedbackSound()
                return@setOnClickListener
            }
            feedbackVibrate(15)
            ctrlActive = !ctrlActive
            updateModifierUI()
        }
        rootView.findViewById<View>(R.id.key_alt)?.setOnClickListener {
            if (handleCompactPunctuationKey(R.id.key_alt)) {
                feedbackVibrate(16)
                feedbackSound()
                return@setOnClickListener
            }
            feedbackVibrate(15)
            altActive = !altActive
            updateModifierUI()
        }
        wireModifierKey(R.id.key_shift) {
            shiftActive = !shiftActive
            updateModifierUI()
        }
        rootView.findViewById<View>(R.id.key_lang)?.apply {
            setOnClickListener {
                feedbackVibrate(15)
                toggleLanguageMode()
            }
            setOnLongClickListener {
                feedbackVibrate(20)
                toggleFullLayoutMode()
                true
            }
        }

        // ── Special keys ──
        wireKey(R.id.key_esc)       {
            if (!handleCompactPunctuationKey(R.id.key_esc)) {
                sendEscape()
            }
        }
        wireKey(R.id.key_tab)       {
            if (!handleCompactPunctuationKey(R.id.key_tab)) {
                sendTab()
            }
        }
        wireKey(R.id.key_enter)     { sendEnter() }
        wireDeleteKey(R.id.key_backspace, ::performBackspace, ::clearAllBeforeCursor)
        wireKey(R.id.key_space)     { sendSpace() }
        wireDeleteKey(R.id.key_delete, ::performForwardDelete, ::clearAllAfterCursor)
        rootView.findViewById<View>(R.id.key_mic)?.setOnClickListener {
            feedbackVibrate(24)
            feedbackSound()
            toggleVoiceInput()
        }

        // ── Navigation ──
        wireCompactBackspaceSwapKey(R.id.key_arrow_up)
        wireKey(R.id.key_arrow_down)  { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        wireKey(R.id.key_arrow_left)  { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        wireKey(R.id.key_arrow_right) {
            if (!handleCompactBottomEnterKey()) {
                sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }
        wireKey(R.id.key_page_up)     { sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
        wireKey(R.id.key_page_down)   { sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        wireKey(R.id.key_home)        {
            if (!handleSymbolModeToggleKey()) {
                sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME)
            }
        }
        wireKey(R.id.key_end)         {
            if (!handleCompactPunctuationKey(R.id.key_end)) {
                sendKeyCode(KeyEvent.KEYCODE_MOVE_END)
            }
        }

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
            R.id.key_lbracket to Pair('[', '{'),
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
            R.id.key_rbracket to Pair(']', '}'),
            R.id.key_backslash to Pair('\\', '|'),
            R.id.key_semicolon to Pair(';', ':'),
            R.id.key_a      to Pair('a', 'A'),
            R.id.key_s      to Pair('s', 'S'),
            R.id.key_d      to Pair('d', 'D'),
            R.id.key_f_key  to Pair('f', 'F'),
            R.id.key_g      to Pair('g', 'G'),
            R.id.key_h      to Pair('h', 'H'),
            R.id.key_j      to Pair('j', 'J'),
            R.id.key_k      to Pair('k', 'K'),
            R.id.key_l      to Pair('l', 'L'),
            R.id.key_quote  to Pair('\'', '"'),
            R.id.key_comma to Pair(',', '<'),
            R.id.key_z      to Pair('z', 'Z'),
            R.id.key_x      to Pair('x', 'X'),
            R.id.key_c      to Pair('c', 'C'),
            R.id.key_v      to Pair('v', 'V'),
            R.id.key_b      to Pair('b', 'B'),
            R.id.key_n      to Pair('n', 'N'),
            R.id.key_m      to Pair('m', 'M'),
            R.id.key_period to Pair('.', '>'),
            R.id.key_slash  to Pair('/', '?'),
        )

        charKeys.forEach { (viewId, chars) ->
            val view = rootView.findViewById<View>(viewId) ?: return@forEach
            // Long-press for alternate symbol (if enabled)
            if (prefs.getBoolean("long_press_extra", true)) {
                view.setOnLongClickListener {
                    if (handleSymbolModeCharacterKey(viewId)) {
                        feedbackVibrate(20)
                        feedbackSound()
                        return@setOnLongClickListener true
                    }
                    if (handleCompactPunctuationKey(viewId)) {
                        feedbackVibrate(20)
                        feedbackSound()
                        return@setOnLongClickListener true
                    }
                    feedbackVibrate(30)
                    handleCharacterInput(chars.first, chars.second, useAlternate = true)
                    true
                }
            }
            // Swipe-up gesture for alternate
            if (prefs.getBoolean("swipe_for_symbols", true)) {
                var startY = 0f
                view.setOnTouchListener { _, event ->
                    if (layoutMode == KeyboardLayoutMode.COMPACT_SYMBOL) {
                        return@setOnTouchListener false
                    }
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { startY = event.y; false }
                        MotionEvent.ACTION_UP -> {
                            if (handleCompactPunctuationKey(viewId)) {
                                feedbackVibrate(16)
                                feedbackSound()
                                return@setOnTouchListener true
                            }
                            val dy = startY - event.y
                            if (dy > 30) { // swipe up
                                feedbackVibrate(20)
                                handleCharacterInput(chars.first, chars.second, useAlternate = true)
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
                if (handleSymbolModeCharacterKey(viewId)) {
                    feedbackVibrate(16)
                    feedbackSound()
                    if (shiftActive) {
                        shiftActive = false
                        updateModifierUI()
                    }
                    return@setOnClickListener
                }
                if (handleCompactPunctuationKey(viewId)) {
                    feedbackVibrate(16)
                    feedbackSound()
                    if (shiftActive) {
                        shiftActive = false
                        updateModifierUI()
                    }
                    return@setOnClickListener
                }
                feedbackVibrate()
                feedbackSound()
                handleCharacterInput(chars.first, chars.second, useAlternate = shiftActive)
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
        if (chineseMode && chineseEngine.hasPending() && !ch.isLetter()) {
            commitChineseSelection()
        }

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
        if (chineseMode && chineseEngine.hasPending()) {
            clearChineseInput(commitCurrent = false)
        }
        feedbackVibrate()
        feedbackSound()
        currentInputConnection?.commitText("\u001b", 1)
        resetModifiers()
    }

    private fun sendTab() {
        if (chineseMode && chineseEngine.hasPending()) {
            commitChineseSelection()
        }
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
        if (chineseMode && chineseEngine.hasPending()) {
            val state = chineseEngine.currentState(currentChineseContextBefore())
            if (state.hasPendingTail) {
                commitRawChineseCode()
            } else {
                commitChineseSelection()
            }
            return
        }
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
        performBackspace()
    }

    private fun performBackspace() {
        if (chineseMode && chineseEngine.hasPending()) {
            updateChineseState(chineseEngine.backspace(currentChineseContextBefore()))
            return
        }
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun sendDelete() {
        feedbackVibrate()
        feedbackSound()
        performForwardDelete()
    }

    private fun performForwardDelete() {
        if (chineseMode && chineseEngine.hasPending()) {
            updateChineseState(chineseEngine.backspace(currentChineseContextBefore()))
            return
        }
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        when {
            !selectedText.isNullOrEmpty() -> ic.commitText("", 1)
            !ic.getTextAfterCursor(1, 0).isNullOrEmpty() -> ic.deleteSurroundingText(0, 1)
            else -> sendKeyCodeInternal(KeyEvent.KEYCODE_FORWARD_DEL, playFeedback = false)
        }
    }

    private fun sendSpace() {
        if (chineseMode && chineseEngine.hasPending()) {
            feedbackVibrate()
            feedbackSound()
            val state = chineseEngine.currentState(currentChineseContextBefore())
            if (state.canCommitOnSpace) {
                commitChineseSelection(state.primaryCandidate)
            } else {
                clearChineseInput(commitCurrent = false)
                currentInputConnection?.commitText(" ", 1)
            }
            return
        }
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

    private fun commitRawChineseCode() {
        val rawCode = chineseEngine.currentState(currentChineseContextBefore()).rawCode
        chineseEngine.clear(currentChineseContextBefore())
        currentInputConnection?.apply {
            finishComposingText()
            if (rawCode.isNotBlank()) {
                commitText(rawCode, 1)
            }
        }
        rebuildCandidateBar(emptyList())
        updateKeyboardLayoutUi()
    }

    private fun sendKeyCode(keyCode: Int) {
        sendKeyCodeInternal(keyCode, playFeedback = true)
    }

    private fun sendKeyCodeInternal(keyCode: Int, playFeedback: Boolean) {
        if (chineseMode && chineseEngine.hasPending()) {
            commitChineseSelection()
        }
        if (playFeedback) {
            feedbackVibrate()
            feedbackSound()
        }
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
        updateKeyboardLayoutUi()
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

    private fun wireDeleteKey(viewId: Int, repeatAction: () -> Unit, clearAction: () -> Unit) {
        val view = rootView.findViewById<View>(viewId) ?: return
        val swipeThresholdPx = 30f * resources.displayMetrics.density
        var startY = 0f
        var repeatStarted = false
        var swipeArmed = false
        var swipeCanceled = false
        var repeatRunnable: Runnable? = null
        var compactPunctuationMode = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    compactPunctuationMode = handleCompactPunctuationKey(viewId)
                    if (compactPunctuationMode) {
                        feedbackVibrate(16)
                        feedbackSound()
                        return@setOnTouchListener true
                    }
                    feedbackVibrate()
                    feedbackSound()
                    startY = event.y
                    repeatStarted = false
                    swipeArmed = false
                    swipeCanceled = false
                    repeatRunnable = object : Runnable {
                        override fun run() {
                            repeatStarted = true
                            feedbackVibrate(8)
                            repeatAction()
                            repeatHandler.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
                        }
                    }
                    repeatHandler.postDelayed(repeatRunnable!!, DELETE_REPEAT_INITIAL_DELAY_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (compactPunctuationMode) {
                        return@setOnTouchListener true
                    }
                    val shouldArmClear = startY - event.y > swipeThresholdPx
                    if (shouldArmClear && !swipeArmed) {
                        swipeArmed = true
                        swipeCanceled = false
                        repeatRunnable?.let(repeatHandler::removeCallbacks)
                        showToast(R.string.delete_clear_release_hint)
                    } else if (!shouldArmClear && swipeArmed) {
                        swipeArmed = false
                        swipeCanceled = true
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (compactPunctuationMode) {
                        compactPunctuationMode = false
                        return@setOnTouchListener true
                    }
                    repeatRunnable?.let(repeatHandler::removeCallbacks)
                    if (swipeArmed) {
                        clearAction()
                    } else if (swipeCanceled) {
                        // Swiped up then back down: consume release without delete.
                    } else if (!repeatStarted) {
                        repeatAction()
                    }
                    repeatRunnable = null
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    compactPunctuationMode = false
                    swipeArmed = false
                    swipeCanceled = false
                    repeatRunnable?.let(repeatHandler::removeCallbacks)
                    repeatRunnable = null
                    true
                }

                else -> false
            }
        }
    }

    private fun wireCompactBackspaceSwapKey(viewId: Int) {
        val view = rootView.findViewById<View>(viewId) ?: return
        val swipeThresholdPx = 30f * resources.displayMetrics.density
        var startY = 0f
        var repeatStarted = false
        var swipeArmed = false
        var swipeCanceled = false
        var repeatRunnable: Runnable? = null

        view.setOnTouchListener { _, event ->
            if (layoutMode == KeyboardLayoutMode.FULL) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    feedbackVibrate()
                    feedbackSound()
                    startY = event.y
                    repeatStarted = false
                    swipeArmed = false
                    swipeCanceled = false
                    repeatRunnable = object : Runnable {
                        override fun run() {
                            repeatStarted = true
                            feedbackVibrate(8)
                            performBackspace()
                            repeatHandler.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
                        }
                    }
                    repeatHandler.postDelayed(repeatRunnable!!, DELETE_REPEAT_INITIAL_DELAY_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val shouldArmClear = startY - event.y > swipeThresholdPx
                    if (shouldArmClear && !swipeArmed) {
                        swipeArmed = true
                        swipeCanceled = false
                        repeatRunnable?.let(repeatHandler::removeCallbacks)
                        showToast(R.string.delete_clear_release_hint)
                    } else if (!shouldArmClear && swipeArmed) {
                        swipeArmed = false
                        swipeCanceled = true
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    repeatRunnable?.let(repeatHandler::removeCallbacks)
                    if (swipeArmed) {
                        clearAllBeforeCursor()
                    } else if (swipeCanceled) {
                        // Swiped up then back down: consume release without delete.
                    } else if (!repeatStarted) {
                        performBackspace()
                    }
                    repeatRunnable = null
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    swipeArmed = false
                    swipeCanceled = false
                    repeatRunnable?.let(repeatHandler::removeCallbacks)
                    repeatRunnable = null
                    true
                }

                else -> false
            }
        }

        view.setOnClickListener {
            if (layoutMode == KeyboardLayoutMode.FULL) {
                sendKeyCode(KeyEvent.KEYCODE_DPAD_UP)
            }
        }
    }

    private fun stopDeleteRepeats() {
        repeatHandler.removeCallbacksAndMessages(null)
    }

    private fun handleCompactPunctuationKey(viewId: Int): Boolean {
        val output = compactPunctuationOutput(viewId) ?: return false
        sendLiteralText(output)
        return true
    }

    private fun handleCompactBottomEnterKey(): Boolean {
        if (layoutMode == KeyboardLayoutMode.FULL) return false
        performEnterWithoutFeedback()
        return true
    }

    private fun performEnterWithoutFeedback() {
        if (chineseMode && chineseEngine.hasPending()) {
            val state = chineseEngine.currentState(currentChineseContextBefore())
            if (state.hasPendingTail) {
                commitRawChineseCode()
            } else {
                commitChineseSelection()
            }
            return
        }
        val ic = currentInputConnection ?: return
        if (ctrlActive) {
            ic.commitText("\r", 1)
            ctrlActive = false
            updateModifierUI()
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun compactPunctuationOutput(viewId: Int): String? {
        if (layoutMode != KeyboardLayoutMode.COMPACT_ZH && layoutMode != KeyboardLayoutMode.COMPACT_EN) return null
        return when (viewId) {
            R.id.key_esc -> if (chineseMode) "！" else "!"
            R.id.key_backspace -> if (chineseMode) "？" else "?"
            R.id.key_end -> if (chineseMode) "，" else ","
            R.id.key_delete -> if (chineseMode) "。" else "."
            else -> null
        }
    }

    private fun sendLiteralText(text: String) {
        if (chineseMode && chineseEngine.hasPending()) {
            commitChineseSelection()
        }
        currentInputConnection?.commitText(text, 1)
    }

    private fun clearAllBeforeCursor() {
        if (chineseMode && chineseEngine.hasPending()) {
            clearChineseInput(commitCurrent = false)
            return
        }

        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            val selectedText = ic.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                ic.commitText("", 1)
            }
            while (true) {
                val before = ic.getTextBeforeCursor(CLEAR_TEXT_CHUNK_SIZE, 0)
                if (before.isNullOrEmpty()) break
                ic.deleteSurroundingText(before.length, 0)
                if (before.length < CLEAR_TEXT_CHUNK_SIZE) break
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun clearAllAfterCursor() {
        if (chineseMode && chineseEngine.hasPending()) {
            clearChineseInput(commitCurrent = false)
            return
        }

        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            val selectedText = ic.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                ic.commitText("", 1)
            }
            while (true) {
                val after = ic.getTextAfterCursor(CLEAR_TEXT_CHUNK_SIZE, 0)
                if (after.isNullOrEmpty()) break
                ic.deleteSurroundingText(0, after.length)
                if (after.length < CLEAR_TEXT_CHUNK_SIZE) break
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun handleCharacterInput(primary: Char, alternate: Char, useAlternate: Boolean) {
        if (layoutMode == KeyboardLayoutMode.COMPACT_SYMBOL) {
            return
        }
        val isLetter = primary in 'a'..'z'
        if (chineseMode && isLetter && !ctrlActive && !altActive) {
            updateChineseState(chineseEngine.append(primary, currentChineseContextBefore()))
            return
        }

        if (chineseMode && chineseEngine.hasPending() && !isLetter) {
            commitChineseSelection()
        }

        sendChar(if (useAlternate) alternate else primary)
    }

    private fun toggleLanguageMode() {
        chineseMode = !chineseMode
        layoutMode = defaultLayoutModeForLanguage()
        clearChineseInput(commitCurrent = false)
        updateKeyboardLayoutUi()
    }

    private fun handleSymbolModeToggleKey(): Boolean {
        if (layoutMode == KeyboardLayoutMode.FULL) return false
        toggleSymbolMode()
        return true
    }

    private fun toggleSymbolMode() {
        if (layoutMode == KeyboardLayoutMode.COMPACT_SYMBOL) {
            layoutMode = previousCompactLayoutMode.takeIf {
                it == KeyboardLayoutMode.COMPACT_ZH || it == KeyboardLayoutMode.COMPACT_EN
            } ?: defaultLayoutModeForLanguage()
            updateKeyboardLayoutUi()
            return
        }

        if (chineseEngine.hasPending()) {
            commitChineseSelection()
        }
        previousCompactLayoutMode = defaultLayoutModeForLanguage()
        layoutMode = KeyboardLayoutMode.COMPACT_SYMBOL
        updateKeyboardLayoutUi()
    }

    private fun handleSymbolModeCharacterKey(viewId: Int): Boolean {
        if (layoutMode != KeyboardLayoutMode.COMPACT_SYMBOL) return false
        val output = SYMBOL_KEY_LABELS[viewId] ?: return false
        commitSymbolAndReturn(output)
        return true
    }

    private fun commitSymbolAndReturn(symbol: String) {
        currentInputConnection?.commitText(symbol, 1)
        layoutMode = previousCompactLayoutMode.takeIf {
            it == KeyboardLayoutMode.COMPACT_ZH || it == KeyboardLayoutMode.COMPACT_EN
        } ?: defaultLayoutModeForLanguage()
        updateKeyboardLayoutUi()
    }

    private fun toggleFullLayoutMode() {
        layoutMode = if (layoutMode == KeyboardLayoutMode.FULL) {
            defaultLayoutModeForLanguage()
        } else {
            KeyboardLayoutMode.FULL
        }
        clearChineseInput(commitCurrent = false)
        updateKeyboardLayoutUi()
    }

    private fun defaultLayoutModeForLanguage(): KeyboardLayoutMode {
        return if (chineseMode) KeyboardLayoutMode.COMPACT_ZH else KeyboardLayoutMode.COMPACT_EN
    }

    private fun currentChineseContextBefore(): String {
        return currentInputConnection
            ?.getTextBeforeCursor(16, 0)
            ?.toString()
            .orEmpty()
    }

    private fun updateChineseState(state: ChineseInputState) {
        latestChineseState = state
        val ic = currentInputConnection
        if (state.rawCode.isEmpty()) {
            ic?.finishComposingText()
        } else if (state.primaryCandidate != null) {
            ic?.setComposingText(state.primaryCandidate.text, 1)
        } else if (state.previewText.isNotEmpty()) {
            ic?.setComposingText(state.previewText, 1)
        }
        updateCandidateRawCode(state)
        refreshCandidateArea()
        updateKeyboardLayoutUi()
    }

    private fun updateCandidateRawCode(state: ChineseInputState) {
        if (!::candidateRawCodeView.isInitialized) return
        candidateRawCodeView.text = state.groupedRawCode.ifBlank { " " }
        candidateRawCodeView.visibility = if (
            chineseMode &&
            layoutMode != KeyboardLayoutMode.COMPACT_SYMBOL
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun rebuildCandidateBar(candidates: List<ChineseCandidate>) {
        if (!::candidateContainer.isInitialized) return
        candidateContainer.removeAllViews()
        candidates.forEachIndexed { index, candidate ->
            val candidateView = layoutInflater.inflate(R.layout.macro_button, candidateContainer, false) as TextView
            candidateView.text = candidate.text
            candidateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            candidateView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (index == 0) R.color.terminal_green else R.color.terminal_text,
                ),
            )
            candidateView.setOnClickListener {
                feedbackVibrate(15)
                feedbackSound()
                commitChineseSelection(candidate)
            }
            candidateContainer.addView(candidateView)
        }
        candidateScrollView.visibility = if (candidates.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun rebuildLlmToolBar() {
        if (!::candidateContainer.isInitialized) return
        candidateContainer.removeAllViews()
        LlmTextTool.entries.forEach { tool ->
            val toolView = layoutInflater.inflate(R.layout.macro_button, candidateContainer, false) as TextView
            toolView.text = tool.title
            toolView.setTextColor(ContextCompat.getColor(this, R.color.terminal_cyan))
            toolView.setOnClickListener {
                feedbackVibrate(18)
                feedbackSound()
                startLlmTool(tool)
            }
            candidateContainer.addView(toolView)
        }
        candidateScrollView.visibility = View.VISIBLE
    }

    private fun refreshCandidateArea() {
        if (!::candidateScrollView.isInitialized) return
        when {
            chineseMode && layoutMode != KeyboardLayoutMode.COMPACT_SYMBOL -> {
                val candidates = latestChineseState?.candidates.orEmpty()
                if (candidates.isNotEmpty()) {
                    rebuildCandidateBar(candidates)
                } else {
                    candidateContainer.removeAllViews()
                    candidateScrollView.visibility = View.VISIBLE
                }
            }
            shouldShowLlmToolBar() -> {
                rebuildLlmToolBar()
            }
            else -> {
                candidateContainer.removeAllViews()
                candidateScrollView.visibility = View.GONE
            }
        }
    }

    private fun shouldShowLlmToolBar(): Boolean {
        if (!prefs.getBoolean("llm_enabled", false)) return false
        if (buildLlmConfig() == null) return false
        if (layoutMode == KeyboardLayoutMode.COMPACT_SYMBOL) return false
        if (chineseMode && latestChineseState?.rawCode?.isNotBlank() == true) return false
        return currentLlmSource() != null
    }

    private fun commitChineseSelection(candidate: ChineseCandidate? = null) {
        if (!chineseEngine.hasPending()) {
            latestChineseState = null
            updateCandidateRawCode(chineseEngine.currentState(currentChineseContextBefore()))
            refreshCandidateArea()
            return
        }

        val contextBefore = currentChineseContextBefore()
        val state = chineseEngine.currentState(contextBefore)
        val resolvedCandidate = candidate
            ?: state.primaryCandidate
        val resolvedText = resolvedCandidate?.text
            ?: state.previewText.takeIf { it.isNotBlank() }

        currentInputConnection?.apply {
            if (!resolvedText.isNullOrBlank()) {
                commitText(resolvedText, 1)
                if (resolvedCandidate != null) {
                    chineseEngine.recordSelection(state, resolvedCandidate, contextBefore)
                    chineseEngine.consumeCandidate(resolvedCandidate)
                } else {
                    chineseEngine.clear(currentChineseContextBefore())
                }
            } else {
                finishComposingText()
            }
        }

        val nextState = chineseEngine.currentState(currentChineseContextBefore())
        if (nextState.rawCode.isEmpty()) {
            latestChineseState = nextState
            updateCandidateRawCode(nextState)
            refreshCandidateArea()
            currentInputConnection?.finishComposingText()
            updateKeyboardLayoutUi()
        } else {
            updateChineseState(nextState)
        }
    }

    private fun clearChineseInput(commitCurrent: Boolean) {
        if (commitCurrent) {
            commitChineseSelection()
        } else {
            val hadPending = chineseEngine.hasPending()
            val clearedState = chineseEngine.clear(currentChineseContextBefore())
            latestChineseState = clearedState
            if (hadPending) {
                currentInputConnection?.finishComposingText()
            }
            updateCandidateRawCode(clearedState)
            refreshCandidateArea()
            updateKeyboardLayoutUi()
        }
    }

    private fun updateKeyboardLayoutUi() {
        if (!::languageKey.isInitialized || !::candidateScrollView.isInitialized) return
        val showVoice = prefs.getBoolean("show_voice_key", true)
        val showMacro = prefs.getBoolean("show_macro_bar", true)
        val showFn = prefs.getBoolean("show_fn_row", true)
        val compactInvisibleKeys = setOf(
            R.id.key_ctrl,
            R.id.key_quote,
        )
        val visibleCompactKeys = when (layoutMode) {
            KeyboardLayoutMode.COMPACT_ZH -> COMPACT_ZH_KEY_IDS
            KeyboardLayoutMode.COMPACT_EN -> COMPACT_EN_KEY_IDS
            KeyboardLayoutMode.COMPACT_SYMBOL -> COMPACT_SYMBOL_KEY_IDS
            KeyboardLayoutMode.FULL -> emptySet()
        }

        languageKey.isActivated = chineseMode
        languageKey.text = getString(if (chineseMode) R.string.key_lang_zh else R.string.key_lang_en)
        applyKeyboardRowHeights()
        applyDynamicKeyLabels()
        applyDynamicKeyTextSizes()
        applyDynamicKeyWeights()
        when (layoutMode) {
            KeyboardLayoutMode.FULL -> {
                ALL_KEY_IDS.forEach { keyId ->
                    rootView.findViewById<View>(keyId)?.visibility = View.VISIBLE
                }
                rootView.findViewById<View>(R.id.key_lbracket)?.visibility = View.GONE
                rootView.findViewById<View>(R.id.key_semicolon)?.visibility = View.GONE
                rootView.findViewById<View>(R.id.key_comma)?.visibility = View.GONE
                macroScrollView.visibility = if (showMacro) View.VISIBLE else View.GONE
                macroSeparator.visibility = if (showMacro) View.VISIBLE else View.GONE
                fnRow.visibility = if (showFn) View.VISIBLE else View.GONE
            }

            KeyboardLayoutMode.COMPACT_ZH,
            KeyboardLayoutMode.COMPACT_EN,
            KeyboardLayoutMode.COMPACT_SYMBOL -> {
                ALL_KEY_IDS.forEach { keyId ->
                    rootView.findViewById<View>(keyId)?.visibility =
                        when {
                            visibleCompactKeys.contains(keyId) -> View.VISIBLE
                            compactInvisibleKeys.contains(keyId) -> View.INVISIBLE
                            else -> View.GONE
                        }
                }
                macroScrollView.visibility = View.GONE
                macroSeparator.visibility = View.GONE
                fnRow.visibility = View.GONE
            }
        }

        rootView.findViewById<View>(R.id.key_row_1)?.visibility = if (layoutMode == KeyboardLayoutMode.COMPACT_ZH) {
            View.GONE
        } else {
            View.VISIBLE
        }

        voiceKey.visibility = if (showVoice && (layoutMode == KeyboardLayoutMode.FULL || visibleCompactKeys.contains(R.id.key_mic))) {
            View.VISIBLE
        } else {
            View.GONE
        }

        candidateRawCodeView.visibility = if (
            chineseMode &&
            layoutMode != KeyboardLayoutMode.COMPACT_SYMBOL
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        refreshCandidateArea()
        updateVoiceKeyUI()
        updateLlmPanelUi()
        scheduleAdaptiveTouchTargetsUpdate()
    }

    private fun scheduleAdaptiveTouchTargetsUpdate() {
        if (!::rootView.isInitialized) return
        rootView.removeCallbacks(adaptiveTouchRefreshRunnable)
        rootView.post(adaptiveTouchRefreshRunnable)
    }

    private fun rebuildAdaptiveTouchTargets() {
        val host = rootView as? ViewGroup ?: return
        if (!::adaptiveTouchDelegate.isInitialized) return

        val visibleRows = buildList {
            if (::fnRow.isInitialized && fnRow.visibility == View.VISIBLE) add(fnRow as ViewGroup)
            if (::keyRows.isInitialized) {
                keyRows.filterTo(this) { it.visibility == View.VISIBLE }
            }
        }
        if (visibleRows.isEmpty()) {
            adaptiveTouchDelegate.updateTargets(emptyList())
            host.touchDelegate = adaptiveTouchDelegate
            return
        }

        val rowStates = visibleRows.mapNotNull { row ->
            val keyBounds = buildList {
                for (index in 0 until row.childCount) {
                    val child = row.getChildAt(index)
                    if (child.visibility != View.VISIBLE || !child.isClickable || child.width <= 0 || child.height <= 0) continue
                    add(child to getBoundsRelativeToHost(host, child))
                }
            }.sortedBy { it.second.left }
            if (keyBounds.isEmpty()) {
                null
            } else {
                RowTouchState(
                    bounds = getBoundsRelativeToHost(host, row),
                    keys = keyBounds,
                )
            }
        }

        if (rowStates.isEmpty()) {
            adaptiveTouchDelegate.updateTargets(emptyList())
            host.touchDelegate = adaptiveTouchDelegate
            return
        }

        val extraSlopPx = maxOf(
            ViewConfiguration.get(this).scaledTouchSlop * 2,
            (48f * resources.displayMetrics.density).toInt(),
        )

        val targets = buildList {
            rowStates.forEachIndexed { rowIndex, rowState ->
                val bandTop = if (rowIndex == 0) {
                    rowState.bounds.top
                } else {
                    (rowStates[rowIndex - 1].bounds.bottom + rowState.bounds.top) / 2
                }
                val bandBottom = if (rowIndex == rowStates.lastIndex) {
                    rowState.bounds.bottom
                } else {
                    (rowState.bounds.bottom + rowStates[rowIndex + 1].bounds.top) / 2
                }

                rowState.keys.forEachIndexed { keyIndex, (view, actualBounds) ->
                    val left = if (keyIndex == 0) {
                        rowState.bounds.left
                    } else {
                        (rowState.keys[keyIndex - 1].second.centerX() + actualBounds.centerX()) / 2
                    }
                    val right = if (keyIndex == rowState.keys.lastIndex) {
                        rowState.bounds.right
                    } else {
                        (actualBounds.centerX() + rowState.keys[keyIndex + 1].second.centerX()) / 2
                    }
                    val delegateBounds = Rect(left, bandTop, right, bandBottom)
                    val slopBounds = Rect(delegateBounds).apply {
                        inset(-extraSlopPx, -extraSlopPx)
                    }
                    add(
                        AdaptiveTouchTarget(
                            view = view,
                            actualBounds = actualBounds,
                            delegateBounds = delegateBounds,
                            slopBounds = slopBounds,
                        ),
                    )
                }
            }
        }

        adaptiveTouchDelegate.updateTargets(targets)
        host.touchDelegate = adaptiveTouchDelegate
    }

    private fun getBoundsRelativeToHost(host: ViewGroup, view: View): Rect {
        return Rect(0, 0, view.width, view.height).also { rect ->
            host.offsetDescendantRectToMyCoords(view, rect)
        }
    }

    private fun applyDynamicKeyLabels() {
        val compactLabels = if (layoutMode == KeyboardLayoutMode.FULL) {
            emptyMap()
        } else if (layoutMode == KeyboardLayoutMode.COMPACT_SYMBOL) {
            SYMBOL_KEY_LABELS + mapOf(
                R.id.key_home to "ABC",
                R.id.key_arrow_right to "↩",
            )
        } else if (chineseMode) {
            mapOf(
                R.id.key_esc to "！",
                R.id.key_backspace to "？",
                R.id.key_home to "#+=",
                R.id.key_end to "，",
                R.id.key_delete to "。",
                R.id.key_arrow_up to "⌫",
                R.id.key_arrow_right to "↩",
            )
        } else {
            mapOf(
                R.id.key_esc to "!",
                R.id.key_backspace to "?",
                R.id.key_home to "#+=",
                R.id.key_end to ",",
                R.id.key_delete to ".",
                R.id.key_arrow_up to "⌫",
                R.id.key_arrow_right to "↩",
            )
        }

        val defaultLabels = baseCharacterKeyLabels() + mapOf(
            R.id.key_tab to "TAB⇥",
            R.id.key_backslash to "\\",
            R.id.key_ctrl to "CTRL",
            R.id.key_alt to "ALT",
            R.id.key_quote to "'",
            R.id.key_esc to "ESC",
            R.id.key_backspace to "⌫",
            R.id.key_comma to ",",
            R.id.key_period to ".",
            R.id.key_home to "Home",
            R.id.key_end to "End",
            R.id.key_slash to "/",
            R.id.key_arrow_up to "↑",
            R.id.key_arrow_right to "→",
            R.id.key_delete to "Del",
        )

        defaultLabels.forEach { (viewId, defaultLabel) ->
            rootView.findViewById<TextView>(viewId)?.text = defaultLabel
        }
        compactLabels.forEach { (viewId, label) ->
            rootView.findViewById<TextView>(viewId)?.text = label
        }
    }

    private fun applyDynamicKeyWeights() {
        updateKeyWeight(R.id.key_enter, 2.0f)
        updateKeyWeight(R.id.key_arrow_right, if (layoutMode == KeyboardLayoutMode.FULL) 1.0f else 1.4f)
        updateKeyWeight(R.id.key_ctrl, if (layoutMode == KeyboardLayoutMode.FULL) 1.5f else 0.5f)
        updateKeyWeight(R.id.key_quote, if (layoutMode == KeyboardLayoutMode.FULL) 1.0f else 0.5f)
        updateKeyWeight(R.id.key_esc, if (layoutMode == KeyboardLayoutMode.FULL) 1.3f else 1.0f)
        updateKeyWeight(R.id.key_alt, if (layoutMode == KeyboardLayoutMode.FULL) 1.3f else 1.0f)
        updateKeyWeight(R.id.key_end, if (layoutMode == KeyboardLayoutMode.FULL) 1.1f else 1.0f)
        updateKeyWeight(R.id.key_delete, if (layoutMode == KeyboardLayoutMode.FULL) 1.1f else 1.0f)
        updateKeyWeight(R.id.key_space, if (layoutMode == KeyboardLayoutMode.FULL) 2.94f else 4.2f)
    }

    private fun applyDynamicKeyTextSizes() {
        val compactMode = layoutMode != KeyboardLayoutMode.FULL
        val baseSize = if (compactMode) 15f else 13f
        val wideSize = if (compactMode) 18f else 16f
        val utilitySize = if (compactMode) 12.5f else 10f
        val modifierSize = if (compactMode) 13f else 11f
        val enterSize = if (compactMode) 13.5f else 11f
        val arrowSize = if (compactMode) 18f else 16f

        val baseKeys = intArrayOf(
            R.id.key_grave,
            R.id.key_1,
            R.id.key_2,
            R.id.key_3,
            R.id.key_4,
            R.id.key_5,
            R.id.key_6,
            R.id.key_7,
            R.id.key_8,
            R.id.key_9,
            R.id.key_0,
            R.id.key_minus,
            R.id.key_equals,
            R.id.key_lbracket,
            R.id.key_q,
            R.id.key_w,
            R.id.key_e,
            R.id.key_r,
            R.id.key_t,
            R.id.key_y,
            R.id.key_u,
            R.id.key_i,
            R.id.key_o,
            R.id.key_p,
            R.id.key_rbracket,
            R.id.key_backslash,
            R.id.key_semicolon,
            R.id.key_a,
            R.id.key_s,
            R.id.key_d,
            R.id.key_f_key,
            R.id.key_g,
            R.id.key_h,
            R.id.key_j,
            R.id.key_k,
            R.id.key_l,
            R.id.key_quote,
            R.id.key_comma,
            R.id.key_z,
            R.id.key_x,
            R.id.key_c,
            R.id.key_v,
            R.id.key_b,
            R.id.key_n,
            R.id.key_m,
            R.id.key_period,
            R.id.key_slash,
        )
        baseKeys.forEach { setKeyTextSize(it, baseSize) }

        intArrayOf(R.id.key_backspace).forEach { setKeyTextSize(it, wideSize) }
        intArrayOf(R.id.key_tab, R.id.key_space, R.id.key_page_up, R.id.key_page_down, R.id.key_home, R.id.key_end, R.id.key_delete, R.id.key_mic, R.id.key_shift, R.id.key_lang).forEach {
            setKeyTextSize(it, utilitySize)
        }
        intArrayOf(R.id.key_ctrl, R.id.key_alt, R.id.key_esc).forEach { setKeyTextSize(it, modifierSize) }
        intArrayOf(R.id.key_enter).forEach { setKeyTextSize(it, enterSize) }
        intArrayOf(R.id.key_arrow_up, R.id.key_arrow_down, R.id.key_arrow_left, R.id.key_arrow_right).forEach {
            setKeyTextSize(it, arrowSize)
        }
    }

    private fun applyKeyboardRowHeights() {
        if (!::keyRows.isInitialized) return
        val baseHeightDp = prefs.getString("key_height", "42")?.toIntOrNull() ?: 42
        val effectiveHeightDp = when (layoutMode) {
            KeyboardLayoutMode.FULL -> baseHeightDp
            KeyboardLayoutMode.COMPACT_ZH,
            KeyboardLayoutMode.COMPACT_EN,
            KeyboardLayoutMode.COMPACT_SYMBOL -> nextCompactHeightDp(baseHeightDp)
        }
        val rowHeightPx = (effectiveHeightDp * resources.displayMetrics.density).toInt()
        keyRows.forEach { row ->
            row.updateLayoutHeight(rowHeightPx)
        }
    }

    private fun nextCompactHeightDp(baseHeightDp: Int): Int {
        return when {
            baseHeightDp < 42 -> 42
            baseHeightDp < 50 -> 50
            baseHeightDp < 58 -> 58
            else -> 58
        }
    }

    private fun View.updateLayoutHeight(heightPx: Int) {
        val params = layoutParams ?: return
        if (params.height == heightPx) return
        params.height = heightPx
        layoutParams = params
    }

    private fun updateKeyWeight(viewId: Int, weight: Float) {
        val view = rootView.findViewById<View>(viewId) ?: return
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.weight == weight) return
        params.weight = weight
        view.layoutParams = params
    }

    private fun setKeyTextSize(viewId: Int, textSizeSp: Float) {
        rootView.findViewById<TextView>(viewId)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun wireLlmPanel() {
        llmActionCancel.setOnClickListener {
            feedbackVibrate(14)
            dismissLlmPanel(cancelRequest = true)
        }
        llmActionRetry.setOnClickListener {
            feedbackVibrate(14)
            retryLlmRequest()
        }
        llmActionReplace.setOnClickListener {
            feedbackVibrate(16)
            applyLlmResult()
        }
    }

    private fun startLlmTool(tool: LlmTextTool) {
        if (chineseEngine.hasPending()) {
            commitChineseSelection()
        }

        val source = currentLlmSource()
        if (source == null) {
            showToast(R.string.llm_no_source)
            refreshCandidateArea()
            return
        }
        val config = buildLlmConfig()
        if (config == null) {
            showToast(R.string.llm_missing_config)
            refreshCandidateArea()
            return
        }

        llmClient?.cancel()
        val request = LlmToolRequest(tool, source)
        llmPreviewState = LlmPreviewState(request = request, status = LlmPreviewStatus.LOADING)
        updateLlmPanelUi()

        llmClient = OpenAiCompatLlmClient(
            config = config,
            listener = object : OpenAiCompatLlmClient.Listener {
                override fun onSuccess(texts: List<String>) {
                    val current = llmPreviewState ?: return
                    if (current.request != request) return
                    val options = LlmToolSupport.extractResultOptions(request, texts)
                    val valid = isCurrentLlmSourceStillValid(request.source)
                    llmPreviewState = current.copy(
                        status = if (valid) LlmPreviewStatus.SUCCESS else LlmPreviewStatus.INVALIDATED,
                        resultOptions = options.ifEmpty { listOf(getString(R.string.llm_empty_result)) },
                        selectedResultIndex = 0,
                        errorMessage = "",
                    )
                    updateLlmPanelUi()
                }

                override fun onError(message: String) {
                    val current = llmPreviewState ?: return
                    if (current.request != request) return
                    llmPreviewState = current.copy(
                        status = LlmPreviewStatus.ERROR,
                        errorMessage = message,
                    )
                    updateLlmPanelUi()
                    showToast(message, Toast.LENGTH_LONG)
                }
            },
        )
        llmClient?.run(messages = LlmToolSupport.buildMessages(request))
    }

    private fun retryLlmRequest() {
        val request = llmPreviewState?.request ?: return
        startLlmTool(request.tool)
    }

    private fun applyLlmResult() {
        val preview = llmPreviewState ?: return
        if (preview.status != LlmPreviewStatus.SUCCESS) return
        val result = preview.selectedResultText.trim()
        if (result.isEmpty()) {
            showToast(R.string.llm_empty_result)
            return
        }

        val ic = currentInputConnection ?: return
        if (!isCurrentLlmSourceStillValid(preview.request.source)) {
            llmPreviewState = preview.copy(status = LlmPreviewStatus.INVALIDATED)
            updateLlmPanelUi()
            return
        }

        ic.beginBatchEdit()
        try {
            when (preview.request.source.kind) {
                LlmToolSource.Kind.SELECTION -> {
                    if (currentSelectedText()?.toString() != preview.request.source.rawText) {
                        llmPreviewState = preview.copy(status = LlmPreviewStatus.INVALIDATED)
                        updateLlmPanelUi()
                        return
                    }
                    ic.commitText(result, 1)
                }
                LlmToolSource.Kind.BEFORE_CURSOR -> {
                    val beforeCursor = currentBeforeCursorTextForLlm()?.toString().orEmpty()
                    if (!beforeCursor.endsWith(preview.request.source.rawText)) {
                        llmPreviewState = preview.copy(status = LlmPreviewStatus.INVALIDATED)
                        updateLlmPanelUi()
                        return
                    }
                    ic.deleteSurroundingText(preview.request.source.rawText.length, 0)
                    ic.commitText(result, 1)
                }
            }
        } finally {
            ic.endBatchEdit()
        }

        dismissLlmPanel(cancelRequest = false)
        refreshCandidateArea()
    }

    private fun dismissLlmPanel(cancelRequest: Boolean) {
        if (cancelRequest) {
            llmClient?.cancel()
        }
        llmClient = null
        llmPreviewState = null
        updateLlmPanelUi()
        refreshCandidateArea()
    }

    private fun updateLlmPanelUi() {
        if (!::llmPanel.isInitialized) return
        val preview = llmPreviewState
        if (preview == null) {
            llmPanel.visibility = View.GONE
            return
        }

        llmPanel.visibility = View.VISIBLE
        llmPanelTitle.text = "${getString(R.string.llm_panel_title)} - ${preview.request.tool.title}"
        llmPanelSource.text = preview.request.source.displayText
        rebuildLlmOptionChips(preview)
        llmPanelResult.text = when (preview.status) {
            LlmPreviewStatus.SUCCESS,
            LlmPreviewStatus.INVALIDATED -> preview.selectedResultText
            LlmPreviewStatus.ERROR -> preview.errorMessage
            else -> ""
        }
        llmPanelStatus.text = when (preview.status) {
            LlmPreviewStatus.LOADING -> getString(R.string.llm_loading)
            LlmPreviewStatus.ERROR -> preview.errorMessage
            LlmPreviewStatus.INVALIDATED -> getString(R.string.llm_invalidated)
            else -> ""
        }

        setActionEnabled(llmActionCancel, true)
        setActionEnabled(llmActionRetry, preview.status == LlmPreviewStatus.ERROR || preview.status == LlmPreviewStatus.INVALIDATED)
        setActionEnabled(llmActionReplace, preview.status == LlmPreviewStatus.SUCCESS)
    }

    private fun rebuildLlmOptionChips(preview: LlmPreviewState) {
        if (!::llmOptionContainer.isInitialized) return
        llmOptionContainer.removeAllViews()
        val options = preview.resultOptions
        if (preview.request.tool != LlmTextTool.POLISH || options.size <= 1) {
            llmOptionScroll.visibility = View.GONE
            return
        }

        options.forEachIndexed { index, _ ->
            val optionView = layoutInflater.inflate(R.layout.macro_button, llmOptionContainer, false) as TextView
            optionView.text = "选项${index + 1}"
            optionView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (preview.selectedResultIndex == index) R.color.terminal_green else R.color.terminal_text,
                ),
            )
            optionView.setOnClickListener {
                feedbackVibrate(12)
                llmPreviewState = llmPreviewState?.copy(selectedResultIndex = index)
                updateLlmPanelUi()
            }
            llmOptionContainer.addView(optionView)
        }
        llmOptionScroll.visibility = View.VISIBLE
    }

    private fun setActionEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.isClickable = enabled
        view.alpha = if (enabled) 1f else 0.42f
    }

    private fun refreshLlmPreviewValidity() {
        val preview = llmPreviewState ?: return
        if (preview.status == LlmPreviewStatus.LOADING || preview.status == LlmPreviewStatus.ERROR) return
        val nextStatus = if (isCurrentLlmSourceStillValid(preview.request.source)) {
            LlmPreviewStatus.SUCCESS
        } else {
            LlmPreviewStatus.INVALIDATED
        }
        if (nextStatus != preview.status) {
            llmPreviewState = preview.copy(status = nextStatus)
            updateLlmPanelUi()
        }
    }

    private fun currentLlmSource(): LlmToolSource? {
        return LlmToolSupport.resolveSource(
            selectedText = currentSelectedText(),
            beforeCursorText = currentBeforeCursorTextForLlm(),
        )
    }

    private fun currentSelectedText(): CharSequence? = currentInputConnection?.getSelectedText(0)

    private fun currentBeforeCursorTextForLlm(): CharSequence? {
        return currentInputConnection?.getTextBeforeCursor(LlmToolSupport.MAX_SOURCE_CHARS, 0)
    }

    private fun isCurrentLlmSourceStillValid(source: LlmToolSource): Boolean {
        return LlmToolSupport.isSourceStillValid(
            source = source,
            selectedText = currentSelectedText(),
            beforeCursorText = currentBeforeCursorTextForLlm(),
        )
    }

    private fun buildLlmConfig(): OpenAiCompatLlmClient.Config? {
        val baseUrl = prefs.getString("llm_base_url", "https://api.openai.com/v1")?.trim().orEmpty()
        val apiKey = prefs.getString("llm_api_key", null)?.trim().orEmpty()
        val model = prefs.getString("llm_model", null)?.trim().orEmpty()
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return null
        }
        return OpenAiCompatLlmClient.Config(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
        )
    }

    private fun toggleVoiceInput() {
        Log.d(TAG, "MIC tapped voiceStarting=$voiceStarting voiceListening=$voiceListening voiceStopping=$voiceStopping")
        if (voiceStarting || voiceListening || voiceStopping) {
            showToast(R.string.voice_stopping)
            requestVoiceStop()
            return
        }

        if (chineseEngine.hasPending()) {
            commitChineseSelection()
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
                    feedbackVibrate(if (isListening) 30 else 18)
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
        voiceKey.alpha = if (active) 1.0f else 0.9f
        voiceKey.translationZ = if (voiceListening) 6f else 0f
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
                voiceBlinkAnimation = AlphaAnimation(1.0f, 0.12f).apply {
                    duration = 420
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
