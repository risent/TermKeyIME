#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
ADB_SERIAL="${ADB_SERIAL:-emulator-5554}"
GRADLE_BIN="${GRADLE_BIN:-./gradlew}"
INSTALL_APP="${INSTALL_APP:-1}"
LAUNCH_COMPONENT="com.termkey.ime/.InputTestActivity"
IME_ID="com.termkey.ime/.TermKeyIMEService"

# Coordinates are tuned for the Pixel 3a API 34 emulator in portrait mode.
EDITOR_X=540
EDITOR_Y=950
LANG_X=150
LANG_Y=1980
SPACE_X=580
SPACE_Y=1980
DELETE_PUNCT_X=820
DELETE_PUNCT_Y=1980
CLEAR_START_X=1000
CLEAR_START_Y=1880
CLEAR_END_X=1000
CLEAR_END_Y=1760

SAMPLES=(
  "nihk:你好"
  "vsgo:中国"
  "uurufa:输入法"
  "udpn:双拼"
  "vege:这个"
  "ufme:什么"
  "vsdr:终端"
  "ceui:测试"
)

require_device() {
  "${ADB_BIN}" start-server >/dev/null
  local devices
  devices="$("${ADB_BIN}" devices | awk 'NR>1 && $2=="device" {print $1}')"
  if ! printf '%s\n' "${devices}" | rg -qx "${ADB_SERIAL}"; then
    echo "ADB device ${ADB_SERIAL} not found. Start the emulator first." >&2
    exit 1
  fi
}

wait_for_ime() {
  local attempts=30
  local methods
  while (( attempts > 0 )); do
    methods="$(device_shell ime list -a -s | tr -d '\r')"
    if printf '%s\n' "${methods}" | rg -qx "${IME_ID}"; then
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  echo "Input method ${IME_ID} did not appear in ime list." >&2
  exit 1
}

device_shell() {
  "${ADB_BIN}" -s "${ADB_SERIAL}" shell "$@"
}

focus_editor() {
  device_shell input tap "${EDITOR_X}" "${EDITOR_Y}" >/dev/null
  sleep 0.2
}

dump_field_text() {
  local xml
  xml="$(device_shell 'uiautomator dump /sdcard/emu_dump.xml >/dev/null; cat /sdcard/emu_dump.xml')"
  printf '%s\n' "${xml}" | sed -n 's/.*text="\([^"]*\)".*resource-id="com\.termkey\.ime:id\/test_input_field".*/\1/p'
}

clear_field() {
  focus_editor
  device_shell input swipe "${CLEAR_START_X}" "${CLEAR_START_Y}" "${CLEAR_END_X}" "${CLEAR_END_Y}" 150 >/dev/null
  sleep 0.2
}

tap_key() {
  local ch="$1"
  local x y
  case "${ch}" in
    q) x=100; y=1530 ;;
    w) x=200; y=1530 ;;
    e) x=300; y=1530 ;;
    r) x=400; y=1530 ;;
    t) x=500; y=1530 ;;
    y) x=600; y=1530 ;;
    u) x=700; y=1530 ;;
    i) x=800; y=1530 ;;
    o) x=900; y=1530 ;;
    p) x=1000; y=1530 ;;
    a) x=150; y=1700 ;;
    s) x=250; y=1700 ;;
    d) x=350; y=1700 ;;
    f) x=450; y=1700 ;;
    g) x=550; y=1700 ;;
    h) x=650; y=1700 ;;
    j) x=750; y=1700 ;;
    k) x=850; y=1700 ;;
    l) x=950; y=1700 ;;
    z) x=250; y=1810 ;;
    x) x=350; y=1810 ;;
    c) x=450; y=1810 ;;
    v) x=550; y=1810 ;;
    b) x=650; y=1810 ;;
    n) x=750; y=1810 ;;
    m) x=850; y=1810 ;;
    *) x=""; y="" ;;
  esac
  if [[ -z "${x}" || -z "${y}" ]]; then
    echo "No coordinate mapping for key '${ch}'" >&2
    exit 1
  fi
  device_shell input tap "${x}" "${y}" >/dev/null
  sleep 0.12
}

type_code() {
  local code="$1"
  local i ch
  for ((i = 0; i < ${#code}; i++)); do
    ch="${code:i:1}"
    tap_key "${ch}"
  done
}

press_space() {
  device_shell input tap "${SPACE_X}" "${SPACE_Y}" >/dev/null
  sleep 0.25
}

ensure_chinese_mode() {
  local text
  local attempts=5
  while (( attempts > 0 )); do
    focus_editor
    sleep 0.3
    clear_field
    device_shell input tap "${DELETE_PUNCT_X}" "${DELETE_PUNCT_Y}" >/dev/null
    sleep 0.2
    text="$(dump_field_text)"
    clear_field

    if [[ "${text}" == "。" ]]; then
      return 0
    fi
    if [[ "${text}" == "." ]]; then
      device_shell input tap "${LANG_X}" "${LANG_Y}" >/dev/null
      sleep 0.35
      focus_editor
      sleep 0.25
      clear_field
      device_shell input tap "${DELETE_PUNCT_X}" "${DELETE_PUNCT_Y}" >/dev/null
      sleep 0.2
      text="$(dump_field_text)"
      clear_field
      if [[ "${text}" == "。" ]]; then
        return 0
      fi
    fi
    attempts=$((attempts - 1))
    sleep 0.5
  done

  echo "Failed to switch to Chinese mode; punctuation probe returned '${text}'." >&2
  exit 1
}

prepare_app() {
  if [[ "${INSTALL_APP}" == "1" ]]; then
    "${GRADLE_BIN}" installDebug
  fi
  wait_for_ime
  device_shell ime enable "${IME_ID}" >/dev/null || true
  if ! device_shell ime set "${IME_ID}" >/dev/null 2>&1; then
    device_shell settings put secure default_input_method "${IME_ID}" >/dev/null
  fi
  device_shell am start -n "${LAUNCH_COMPONENT}" >/dev/null
  sleep 2
  focus_editor
  sleep 0.5
  ensure_chinese_mode
}

main() {
  require_device
  prepare_app

  local failures=0
  local sample code expected actual

  for sample in "${SAMPLES[@]}"; do
    code="${sample%%:*}"
    expected="${sample#*:}"
    clear_field
    focus_editor
    type_code "${code}"
    press_space
    actual="$(dump_field_text)"
    if [[ "${actual}" == "${expected}" ]]; then
      printf 'PASS  %s -> %s\n' "${code}" "${actual}"
    else
      printf 'FAIL  %s -> expected %s, got %s\n' "${code}" "${expected}" "${actual}" >&2
      failures=$((failures + 1))
    fi
  done

  if [[ "${failures}" -gt 0 ]]; then
    exit 1
  fi
}

main "$@"
