#!/usr/bin/env bash
# The phone app's ADB code and its whole setup (android/phone: Adb, AdbKey, Tv, Setup) against pretend TVs, with
# plain javac: no Android SDK. tests/fake_adbd.py is the TV's adbd; tests/fake_tv has its pm, settings, cmd, am …
#   tests/adb_test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'kill $(jobs -p) 2> /dev/null || true; rm -rf "$work"' EXIT
src=android/phone/src/main/java/sa/urtv/setup
javac -d "$work/classes" "$src/Adb.java" "$src/AdbKey.java" "$src/Tv.java" "$src/Setup.java" tests/AdbTest.java tests/SetupTest.java
mkdir -p "$work/tv" "$work/setup"
FAKE_TV_DIR="$work" python3 tests/fake_adbd.py 15555 > "$work/a.log" 2>&1 &
FAKE_TV_DIR="$work" python3 tests/fake_adbd.py 15556 --drop 7 --maxdata 4096 > "$work/b.log" 2>&1 &
FAKE_TV_DIR="$work/setup" FAKE_TV_BIN="$PWD/tests/fake_tv" python3 tests/fake_adbd.py 15557 > "$work/c.log" 2>&1 &
for _ in $(seq 50); do grep -q ready "$work/a.log" 2> /dev/null && grep -q ready "$work/b.log" 2> /dev/null \
  && grep -q ready "$work/c.log" 2> /dev/null && break; sleep 0.1; done
java -cp "$work/classes" sa.urtv.setup.AdbTest 15555 15556 "$work"
java -cp "$work/classes" sa.urtv.setup.SetupTest 15557 "$work/setup" "$PWD"
! grep -h "PROTOCOL ERROR" "$work"/*.log
