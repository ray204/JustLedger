#!/bin/sh
# Keep Android development tools and their writable state within this project.
set -eu
JUSTLEDGER_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JUSTLEDGER_ANDROID="$JUSTLEDGER_ROOT/.local/android"
export ANDROID_HOME="$JUSTLEDGER_ANDROID/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$JUSTLEDGER_ANDROID/user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$JUSTLEDGER_ANDROID/avd"
export ANDROID_CLI_BIN="$JUSTLEDGER_ANDROID/downloads/android-cli"
export GRADLE_USER_HOME="$JUSTLEDGER_ROOT/.local/gradle"
if [ -x "$JUSTLEDGER_ROOT/.local/java/jdk-21/Contents/Home/bin/java" ]; then
  export JAVA_HOME="$JUSTLEDGER_ROOT/.local/java/jdk-21/Contents/Home"
fi
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"

case "${1:-help}" in
  sdk)
    shift
    exec "$ANDROID_CLI_BIN" --no-metrics --sdk="$ANDROID_HOME" sdk "$@"
    ;;
  avdmanager)
    shift
    if [ -z "${JAVA_HOME:-}" ] && [ -x '/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home/bin/java' ]; then
      export JAVA_HOME='/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home'
    fi
    exec "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" "$@"
    ;;
  emulator)
    shift
    exec "$ANDROID_HOME/emulator/emulator" "$@"
    ;;
  adb)
    shift
    exec "$ANDROID_HOME/platform-tools/adb" "$@"
    ;;
  gradle)
    shift
    exec "$JUSTLEDGER_ROOT/.local/gradle-dist/gradle-8.11.1/bin/gradle" -p "$JUSTLEDGER_ROOT" "$@"
    ;;
  *)
    printf '%s\n' 'Usage: scripts/android-local.sh {sdk|avdmanager|emulator|adb|gradle} [arguments...]'
    printf '%s\n' "SDK: $ANDROID_HOME" "AVDs: $ANDROID_AVD_HOME"
    ;;
esac
