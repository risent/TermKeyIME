# Testing Guide

## Scope
Use this guide for manual regression of `TermKey IME`, especially Natural Shuangpin, delete gestures, and the dedicated input test page.

Run tests from `com.termkey.ime/.InputTestActivity` when possible:

```bash
make device-test
```

Or on the local emulator:

```bash
./gradlew installDebug
adb -s emulator-5554 shell am start -n com.termkey.ime/.InputTestActivity
```

## Shuangpin Regression
Switch to Chinese mode first.

Test these exact code samples:

- `nihk` -> `你好`
- `vsgo` -> `中国`
- `uurufa` -> `输入法`
- `udpn` -> `双拼`
- `vege` -> `这个`
- `ufme` -> `什么`
- `vsdr` -> `终端`
- `ceui` -> `测试`

For each sample:

1. Type the code slowly and confirm `rawCode` updates immediately.
2. Confirm the candidate bar stays visible and does not flicker when empty or populated.
3. Press `Space` and verify the first candidate commits.
4. Repeat and tap the first candidate instead of `Space`.
5. Repeat and use `Backspace` mid-code. It must delete Shuangpin code first and must not delete committed text until the code buffer is empty.

## Special Cases
Verify special initials:

- `vi` should produce `zh` candidates such as `之`
- `ui` should produce `sh` candidates such as `是`
- `ii` should produce `ch` candidates such as `吃`

Verify zero-initial finals:

- `ai`
- `ah`
- `ou`

## Delete Gesture Checks
Use either pending Shuangpin code or committed text.

- Swipe up on backspace and release: clear the active buffer/text range as designed.
- Swipe up past the threshold, slide back below the threshold, then release: do nothing.
- Long-press backspace: repeat deletion should continue until release.

## Device Split
- Emulator: verify logic, candidate updates, and gesture outcomes.
- Real device: verify haptics, gesture feel, and IME window behavior.
