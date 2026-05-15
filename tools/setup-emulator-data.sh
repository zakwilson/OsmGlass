#!/usr/bin/env bash
#
# Sideloads BRouter + pushes Berlin Mapsforge + BRouter data files onto the phone emulator
# (clj-android). Re-run any time after `adb root` is restored or the emulator is wiped.
#
# Prerequisites:
#   • The phone emulator is booted and visible to `adb devices` as `emulator-5554`.
#     (If your Android emulator binary is too old to boot the API 30 system image, this
#      script can be run against any phone — emulator or real — with adb debugging enabled.)
#
# Hardware mode:
#   • Pair the Glass + the phone over Bluetooth Classic; pass --hardware to skip the emulator
#     port-forward step (which is only needed for the TCP transport stand-in on emulators).
#
set -euo pipefail

ADB="${ADB:-/home/node/Android/Sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="$ROOT/data"
PHONE_SERIAL="${PHONE_SERIAL:-emulator-5554}"
GLASS_SERIAL="${GLASS_SERIAL:-emulator-5556}"

echo "[1/5] Ensuring data directory + downloads..."
mkdir -p "$DATA"
[[ -f "$DATA/berlin.map" ]] || curl -sSL --fail \
    -o "$DATA/berlin.map" \
    "https://download.mapsforge.org/maps/v5/europe/germany/berlin.map"
[[ -f "$DATA/E10_N50.rd5" ]] || curl -sSL --fail \
    -o "$DATA/E10_N50.rd5" \
    "https://brouter.de/brouter/segments4/E10_N50.rd5"
[[ -f "$DATA/brouter.apk" ]] || curl -sSL --fail \
    -o "$DATA/brouter.apk" \
    "https://f-droid.org/repo/btools.routingapp_1790.apk"

echo "[2/5] Installing BRouter on phone (${PHONE_SERIAL})..."
$ADB -s "$PHONE_SERIAL" install -r "$DATA/brouter.apk"

echo "[3/5] Pushing berlin.map to phone..."
$ADB -s "$PHONE_SERIAL" shell mkdir -p /sdcard/Android/data/dev.glass.phone/files/maps
$ADB -s "$PHONE_SERIAL" push "$DATA/berlin.map" \
    /sdcard/Android/data/dev.glass.phone/files/maps/berlin.map

echo "[4/5] Pushing E10_N50.rd5 to BRouter segments dir on phone..."
$ADB -s "$PHONE_SERIAL" shell mkdir -p /sdcard/brouter/segments4
$ADB -s "$PHONE_SERIAL" push "$DATA/E10_N50.rd5" /sdcard/brouter/segments4/E10_N50.rd5

if [[ "${1:-}" != "--hardware" ]]; then
    echo "[5/5] Setting up adb forward 8765 → glass emulator..."
    $ADB -s "$GLASS_SERIAL" forward tcp:8765 tcp:8765
    echo "    Glass emulator listening at 127.0.0.1:8765 from the host."
    echo "    The phone emulator can reach it as 10.0.2.2:8765."
fi

echo "Done. Run the pair-emulator E2E test with:"
echo "    ./gradlew :protocol:test --tests \"*PairEmulatorE2eTest*\" -Dpair.e2e=1"
