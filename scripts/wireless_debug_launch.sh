#!/usr/bin/env bash
set -euo pipefail

APP_COMPONENT="com.termkey.ime/.SetupActivity"
INPUT_METHOD_SETTINGS_ACTION="android.settings.INPUT_METHOD_SETTINGS"

ADB_BIN="${ADB_BIN:-adb}"
ADB_CONNECT_TARGET="${ADB_CONNECT_TARGET:-}"
ADB_SERIAL="${ADB_SERIAL:-}"
ATTACH_LOGCAT="${ATTACH_LOGCAT:-1}"

if [[ -n "${ADB_CONNECT_TARGET}" ]]; then
  "${ADB_BIN}" connect "${ADB_CONNECT_TARGET}"
fi

"${ADB_BIN}" start-server >/dev/null

resolve_serial() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    printf '%s\n' "${ADB_SERIAL}"
    return 0
  fi

  mapfile -t devices < <("${ADB_BIN}" devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ "${#devices[@]}" -eq 0 ]]; then
    echo "No connected adb device found." >&2
    exit 1
  fi
  if [[ "${#devices[@]}" -gt 1 ]]; then
    echo "Multiple devices found. Set ADB_SERIAL explicitly." >&2
    printf 'Devices:\n%s\n' "${devices[*]}" >&2
    exit 1
  fi
  printf '%s\n' "${devices[0]}"
}

SERIAL="$(resolve_serial)"

echo "Using device: ${SERIAL}"
"${ADB_BIN}" -s "${SERIAL}" shell am start -n "${APP_COMPONENT}"
"${ADB_BIN}" -s "${SERIAL}" shell am start -a "${INPUT_METHOD_SETTINGS_ACTION}"

if [[ "${ATTACH_LOGCAT}" != "1" ]]; then
  exit 0
fi

PID="$("${ADB_BIN}" -s "${SERIAL}" shell pidof com.termkey.ime | tr -d '\r' || true)"
if [[ -z "${PID}" ]]; then
  echo "TermKey process is not running; skipping logcat attach." >&2
  exit 0
fi

echo "Attaching logcat for PID ${PID}"
exec "${ADB_BIN}" -s "${SERIAL}" logcat --pid="${PID}" -v time
