SHELL := /bin/bash

.PHONY: wireless-debug

wireless-debug:
	./scripts/wireless_debug_launch.sh

.PHONY: device-test

device-test:
	./scripts/install_and_launch_input_test.sh

.PHONY: wireless-debug-connect

wireless-debug-connect:
	@if [[ -z "$(ADB_CONNECT_TARGET)" ]]; then \
		echo "Usage: make wireless-debug-connect ADB_CONNECT_TARGET=host:port [ADB_SERIAL=serial] [ATTACH_LOGCAT=0|1]"; \
		exit 1; \
	fi
	ADB_CONNECT_TARGET="$(ADB_CONNECT_TARGET)" ADB_SERIAL="$(ADB_SERIAL)" ATTACH_LOGCAT="$${ATTACH_LOGCAT:-1}" ./scripts/wireless_debug_launch.sh

.PHONY: device-test-connect

device-test-connect:
	@if [[ -z "$(ADB_CONNECT_TARGET)" ]]; then \
		echo "Usage: make device-test-connect ADB_CONNECT_TARGET=host:port [ADB_SERIAL=serial] [ATTACH_LOGCAT=0|1]"; \
		exit 1; \
	fi
	ADB_CONNECT_TARGET="$(ADB_CONNECT_TARGET)" ADB_SERIAL="$(ADB_SERIAL)" ATTACH_LOGCAT="$${ATTACH_LOGCAT:-1}" ./scripts/install_and_launch_input_test.sh

.PHONY: emulator-shuangpin-test

emulator-shuangpin-test:
	ADB_SERIAL="$${ADB_SERIAL:-emulator-5554}" INSTALL_APP="$${INSTALL_APP:-1}" ./scripts/run_emulator_shuangpin_samples.sh
