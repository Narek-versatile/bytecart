#!/usr/bin/env bash
# Install ByteCart on every Android device connected over adb (USB or wireless debugging).
#   scripts/install-android.sh              # newest GitHub release
#   scripts/install-android.sh my.apk       # a local APK
#   scripts/install-android.sh --local      # app/build/outputs/apk/debug/app-debug.apk (after ./gradlew :app:assembleDebug)
set -euo pipefail
REPO="Narek-versatile/bytecart"
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools" >&2; exit 1; }

if [[ "${1:-}" == "--local" ]]; then
  apk="$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk"
elif [[ -n "${1:-}" ]]; then
  apk="$1"
else
  apk="$(mktemp -d)/bytecart.apk"
  echo "Downloading the latest release of $REPO ..."
  curl -fL --progress-bar -o "$apk" "https://github.com/$REPO/releases/latest/download/bytecart.apk"
fi
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }

devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$devices" ]]; then
  echo "No authorized device. Enable USB debugging, plug the phone in and accept the prompt on it." >&2
  adb devices >&2
  exit 1
fi
fail=0
for s in $devices; do
  model=$(adb -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  echo "== $s ($model)"
  adb -s "$s" install -r "$apk" || { echo "   install failed (if it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, uninstall the old build first: adb -s $s uninstall dev.bytecart)"; fail=1; }
done
exit $fail
