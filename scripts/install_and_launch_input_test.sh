#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
ADB_CONNECT_TARGET="${ADB_CONNECT_TARGET:-}"
ADB_SERIAL="${ADB_SERIAL:-}"
ATTACH_LOGCAT="${ATTACH_LOGCAT:-1}"
GRADLE_BIN="${GRADLE_BIN:-./gradlew}"
LAUNCH_COMPONENT="com.termkey.ime/.InputTestActivity"

if [[ -n "${ADB_CONNECT_TARGET}" ]]; then
  "${ADB_BIN}" connect "${ADB_CONNECT_TARGET}"
fi

"${ADB_BIN}" start-server >/dev/null

resolve_serial() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    printf '%s\n' "${ADB_SERIAL}"
    return 0
  fi

  local devices_raw
  devices_raw="$("${ADB_BIN}" devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [[ -z "${devices_raw}" ]]; then
    echo "No connected adb device found." >&2
    exit 1
  fi
  if [[ "$(printf '%s\n' "${devices_raw}" | wc -l | tr -d ' ')" -gt 1 ]]; then
    echo "Multiple devices found. Set ADB_SERIAL explicitly." >&2
    printf 'Devices:\n%s\n' "${devices_raw}" >&2
    exit 1
  fi
  printf '%s\n' "${devices_raw}"
}

SERIAL="$(resolve_serial)"
echo "Using device: ${SERIAL}"

"${GRADLE_BIN}" installDebug
"${ADB_BIN}" -s "${SERIAL}" shell am start -n "${LAUNCH_COMPONENT}"

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
