# TermKey IME — Linux Terminal Keyboard for Android

A full-featured Android Input Method Engine (IME) designed specifically for Linux terminal emulators like **Termux**, **ConnectBot**, **JuiceSSH**, and **Blink Shell**.

---

## ✨ Features

| Feature | Details |
|---|---|
| **Sticky Ctrl / Alt / Shift** | Tap once to activate, tap again to deactivate. Color-coded highlight (amber=Ctrl, cyan=Alt, green=Shift) |
| **True control characters** | Ctrl+C sends `0x03`, Ctrl+D sends `0x04`, etc. — exactly what terminal apps expect |
| **Alt/Meta prefix** | Alt+key sends `ESC + key` (the standard terminal meta convention) |
| **F1–F12 row** | Toggle-able function key row at the top |
| **Navigation cluster** | Arrow keys, PgUp/PgDn, Home/End, Delete, Insert |
| **Macro bar** | Horizontally scrollable quick-insert snippets: `sudo`, `grep -r`, `\| less`, `2>&1`, `chmod +x`, and 15+ more. Fully customizable. |
| **Long-press alternate** | Long-press any key to type the symbol shown in the corner (e.g. long-press `1` → `!`) |
| **Swipe-up alternate** | Swipe up on any key to type the alternate symbol |
| **Haptic feedback** | Short vibration on each keypress (configurable) |
| **Settings screen** | Control key height, show/hide rows, sticky modifiers, macro editing |

---

## 🏗️ Project Structure

```
TermKeyIME/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/termkey/ime/
│       │   ├── TermKeyIMEService.kt   ← Core IME logic
│       │   ├── MacroManager.kt        ← Macro storage & defaults
│       │   ├── SetupActivity.kt       ← Guided setup screen
│       │   └── SettingsActivity.kt    ← Settings / preferences
│       └── res/
│           ├── layout/
│           │   ├── keyboard_view.xml  ← Full keyboard layout
│           │   ├── macro_button.xml
│           │   └── key_fn.xml
│           ├── xml/
│           │   ├── method.xml         ← IME declaration
│           │   └── preferences.xml    ← Settings screen
│           ├── drawable/              ← Key background states
│           └── values/
│               ├── strings.xml
│               ├── colors.xml
│               ├── themes.xml
│               ├── styles.xml
│               ├── dimens.xml
│               └── arrays.xml
├── build.gradle
└── settings.gradle
```

---

## 🔨 Build Instructions

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34, min SDK 26 (Android 8.0+)

### Steps

1. **Open the project** in Android Studio:
   - `File → Open` → select the `TermKeyIME` folder

2. **Sync Gradle** — Android Studio will prompt automatically

3. **Build & install**:
   ```bash
   ./gradlew installDebug
   ```
   Or use `Run → Run 'app'` in Android Studio.

4. **Enable the keyboard** on your device:
   - Launch the **TermKey** app
   - Tap **"Open Keyboard Settings"** and enable TermKey
   - Tap **"Switch to TermKey"** to make it active

---

## 📱 How to Use

### Modifier keys (sticky)
- Tap **CTRL** once → it highlights amber. Next key you press sends a control combo.
  - `CTRL` then `C` → sends `0x03` (SIGINT in Termux)
  - `CTRL` then `D` → sends `0x04` (EOF)
  - `CTRL` then `Z` → sends `0x1A` (SIGTSTP)
- Tap **ALT** once → it highlights cyan. Next key sends `ESC + key`.
- Tap **ESC** → sends a literal `0x1B` escape character.

### Macro bar
- Scroll horizontally to see all macros
- Tap any macro to insert that text at the cursor
- To customize: Settings → Edit Macros

### Key alternates
- **Swipe up** on a key to type the symbol shown in its top-right corner
- **Long-press** a key to type the alternate symbol

---

## ⚙️ Settings

| Setting | Default | Description |
|---|---|---|
| Show F1–F12 row | On | Toggle function key row |
| Show macro bar | On | Toggle quick-insert bar |
| Key height | 42dp | Small / Normal / Large / XL |
| Vibrate on keypress | On | Haptic feedback |
| Sound on keypress | Off | Click sound |
| Sticky modifiers | On | Tap-to-activate Ctrl/Alt |
| Swipe for symbols | On | Swipe up for alternate |
| Long-press for symbol | On | Long-press for alternate |

---

## 🔧 Extending / Customizing

### Adding more macros programmatically
Edit `MacroManager.kt` → `DEFAULT_MACROS` list:
```kotlin
Macro("label", "text to insert"),
```

### Key height from preferences
The `TermKeyIMEService` reads the `key_height` preference on `onCreateInputView()`.
Override `applyPreferences()` to dynamically set `layout_height` on each key row.

### Adding a numpad layer
Add a second layout `keyboard_numpad.xml` and swap it in `onCreateInputView()` based on a mode flag, similar to how `fnRow.visibility` is toggled.

---

## 🤝 Compatible apps

Tested and verified to work with:
- **Termux** — full Ctrl/Alt/F-key support
- **ConnectBot** — SSH sessions
- **JuiceSSH** — SSH with custom key support
- **Blink Shell** — mosh/SSH
- Any app that accepts standard Android keyboard input

---

## 📄 License

MIT License — free to use, modify, and distribute.
