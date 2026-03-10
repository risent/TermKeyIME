# TermKey IME

Android terminal keyboard with compact Chinese/English layouts, Natural Shuangpin input, editable macros, and streaming voice input.

## Features

- Terminal-first key behavior: sticky `Ctrl` / `Alt` / `Shift`, real control characters, `Alt` as `ESC + key`, F1-F12, arrows, PgUp/PgDn, Home/End.
- Three keyboard layouts at runtime:
  - `FULL`: full terminal keyboard with function row and control keys.
  - `COMPACT_ZH`: Chinese compact layout for double-pinyin input.
  - `COMPACT_EN`: English compact layout with letters, numbers, and common punctuation.
- Dedicated compact symbol mode:
  - `#+=` enters a symbol-only page.
  - tap a symbol to insert it and return to the previous compact layout.
- Natural Shuangpin Chinese input:
  - offline lexicon-backed candidate lookup
  - phrase and sentence-first candidate ordering
  - local user frequency learning
  - candidate bar click-to-commit
  - `Space` confirms the first Chinese candidate by default
- Streaming voice input using Volcengine WebSocket ASR.
- Editable macro bar with persistent custom macros.
- Compact-mode touch target expansion, hold-to-delete, and swipe-up clear for delete keys.

## Current Input Behavior

### Chinese mode

- Tap `中/EN` to switch into Chinese mode.
- Letter keys enter Natural Shuangpin code.
- The candidate bar stays visible in Chinese mode.
- The first candidate is used as composing text.
- `Space` confirms the first candidate and clears the current input buffer.
- `Backspace` deletes Shuangpin code first; when the buffer is empty it deletes text normally.
- The engine supports phrase and sentence candidates, not just single characters.

### English mode

- Compact English keeps letters, numbers, and common punctuation visible.
- `FULL` mode restores the full terminal layout.

### Symbol mode

- In compact Chinese or English, tap `#+=` to enter symbol mode.
- Symbol mode shows symbol keys only, plus the necessary control keys such as `ABC`, `Space`, `Backspace`, `Enter`, and `MIC`.
- Tapping any symbol inserts it and returns to the previous compact layout.

### Voice input

- Tap `MIC` to start streaming recognition.
- Recognition text appears live in the current input field.
- Tap `MIC` again to stop and finalize.
- Requires microphone permission plus Volcengine `APP ID`, `Access Token`, and `Resource ID` in settings.

## Build

### Requirements

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 34
- minSdk 26

### Command

```bash
./gradlew installDebug
```

If Gradle fails with `Unsupported class file major version 69`, switch back to JDK 17 before building.

## Setup

1. Install the debug build.
2. Open the `TermKey` app.
3. Enable the `TermKey` input method in Android keyboard settings.
4. Switch the active keyboard to `TermKey`.
5. Open `Settings` inside the app to configure macros or voice input.

## Settings

Key settings currently exposed:

- show/hide macro bar
- show/hide F-key row
- key height
- vibrate on keypress
- sound on keypress
- sticky modifiers
- swipe-up alternate symbols
- long-press alternate symbols
- show/hide voice key
- microphone permission request
- Volcengine voice configuration
- macro editing and reset

## Project Structure

```text
app/src/main/java/com/termkey/ime/
├── TermKeyIMEService.kt
├── NaturalShuangpinEngine.kt
├── ChineseLexiconStore.kt
├── VolcengineVoiceInputClient.kt
├── MacroManager.kt
├── MacroEditorActivity.kt
├── SettingsActivity.kt
└── SetupActivity.kt
```

Important resources:

- `app/src/main/res/layout/keyboard_view.xml`: keyboard layout
- `app/src/main/res/xml/preferences.xml`: settings screen
- `app/src/main/assets/chinese_lexicon.db`: offline Chinese lexicon

## Notes

- The Chinese lexicon is bundled locally, so APK size is larger than a plain keyboard app.
- User word frequency is stored on-device.
- Voice input depends on external Volcengine credentials; the rest of the keyboard works offline.

## Compatible Apps

Best suited for terminal and SSH apps such as:

- Termux
- ConnectBot
- JuiceSSH
- Blink Shell

It also works in ordinary Android text fields, especially for compact Chinese and voice input.

## License

MIT
