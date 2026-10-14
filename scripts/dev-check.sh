#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

is_jdk_21() {
  local version
  [ -n "$1" ] && [ -x "$1/bin/java" ] && [ -x "$1/bin/javac" ] || return 1
  version=$("$1/bin/java" -version 2>&1) || return 1
  [[ "$version" == *'version "21.'* || "$version" == *'version "21"'* ]]
}

# Respect an explicit JAVA_HOME; otherwise find an installed JDK 21 on Linux.
if [ -n "${JAVA_HOME:-}" ]; then
  is_jdk_21 "$JAVA_HOME" || fail "JAVA_HOME doit pointer vers un JDK 21 complet (java et javac)."
else
  java_on_path=""
  if command -v java >/dev/null 2>&1; then
    java_on_path=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
  fi
  for candidate in "${JAVA_HOME_21_X64:-}" "$java_on_path" /usr/lib/jvm/*21* /tmp/jdk-21; do
    if is_jdk_21 "$candidate"; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
  [ -n "${JAVA_HOME:-}" ] || fail "JDK 21 introuvable. Installer un JDK 21 puis définir JAVA_HOME."
fi
export PATH="$JAVA_HOME/bin:$PATH"

has_android=false
if [ -f app/build.gradle.kts ] || [ -f app/build.gradle ]; then
  has_android=true
fi

echo "== Branch =="
git branch --show-current

echo
echo "== Status =="
git status --short

echo
echo "== AGENTS.md =="
if [ -f AGENTS.md ]; then
  echo "OK: AGENTS.md present"
else
  echo "ERROR: AGENTS.md missing"
  exit 1
fi

echo
echo "== Gradle wrapper =="
if [ -x ./gradlew ]; then
  echo "OK: ./gradlew present"
else
  echo "ERROR: ./gradlew missing or not executable"
  exit 1
fi

echo
echo "== Java environment =="
echo "JAVA_HOME=$JAVA_HOME"
java -version
javac -version
echo "GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}"

if "$has_android"; then
  if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
      export ANDROID_HOME="$ANDROID_SDK_ROOT"
    else
      for candidate in "$HOME/Android/Sdk" /tmp/android-sdk; do
        if [ -d "$candidate" ]; then
          export ANDROID_HOME="$candidate"
          break
        fi
      done
    fi
  fi
  [ -d "${ANDROID_HOME:-}" ] || fail "SDK Android introuvable. Définir ANDROID_HOME (voir docs/environnement-et-ci.md)."
  export ANDROID_HOME
  ANDROID_HOME=$(cd "$ANDROID_HOME" && pwd -P)
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    [ -d "$ANDROID_SDK_ROOT" ] || fail "ANDROID_SDK_ROOT pointe vers un dossier inexistant."
    sdk_root=$(cd "$ANDROID_SDK_ROOT" && pwd -P)
    [ "$ANDROID_HOME" = "$sdk_root" ] || fail "ANDROID_HOME et ANDROID_SDK_ROOT doivent désigner le même SDK."
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
  fi

  [ -f "$ANDROID_HOME/platforms/android-35/android.jar" ] || fail "Installer platforms;android-35 avec sdkmanager."
  for tool in aapt2 d8; do
    [ -x "$ANDROID_HOME/build-tools/34.0.0/$tool" ] || fail "Installer build-tools;34.0.0 avec sdkmanager."
  done
  # ANDROID_USER_HOME is the preferences directory, not the SDK installation.
  # ANDROID_SDK_HOME is a legacy parent directory for .android.
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-${ANDROID_SDK_HOME:+$ANDROID_SDK_HOME/.android}}"
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-${TMPDIR:-/tmp}/lmarchery-android-home}"
  mkdir -p "$ANDROID_USER_HOME"
  [ -w "$ANDROID_USER_HOME" ] || fail "ANDROID_USER_HOME doit être accessible en écriture."

  echo
  echo "== Android environment =="
  echo "ANDROID_HOME=$ANDROID_HOME"
  echo "ANDROID_USER_HOME=$ANDROID_USER_HOME"
  echo "SDK platform: 35; Build Tools: 34.0.0 (AGP 8.7.x default)"

  echo
  echo "== Privacy guardrails =="
  [ -f app/src/main/AndroidManifest.xml ] || fail "AndroidManifest.xml missing."
  for permission in android.permission.INTERNET android.permission.CAMERA; do
    if grep -Fq "$permission" app/src/main/AndroidManifest.xml; then
      fail "$permission is not allowed in local/offline V1 without explicit decision"
    fi
  done
  echo "OK: no INTERNET or CAMERA permission declared"
else
  echo "Module :app absent : validation JVM seule, sans prérequis Android."
fi

echo
echo "== Diff whitespace check =="
git diff --check
git diff --cached --check

echo
echo "== Gradle environment =="
./gradlew --version "$@"

echo
echo "== JVM and Android unit tests =="
./gradlew test "$@"

if "$has_android"; then
  echo
  echo "== Android debug build =="
  ./gradlew :app:assembleDebug "$@"

  echo
  echo "== Android lint =="
  ./gradlew :app:lintDebug "$@"
fi

echo
echo "== Diff summary =="
git diff --stat
git diff --cached --stat
