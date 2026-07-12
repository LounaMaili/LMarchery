#!/usr/bin/env bash
set -euo pipefail

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

if [ -z "${ANDROID_HOME:-}" ] && [ -d /tmp/android-sdk ]; then
  export ANDROID_HOME="/tmp/android-sdk"
fi

echo
echo "== Android environment =="
echo "ANDROID_HOME=${ANDROID_HOME:-<not set>}"
echo "ANDROID_USER_HOME=${ANDROID_USER_HOME:-<not set>}"

echo
echo "== Privacy guardrails =="
for permission in android.permission.INTERNET android.permission.CAMERA; do
  if grep -R "$permission" app/src/main/AndroidManifest.xml >/dev/null 2>&1; then
    echo "ERROR: $permission is not allowed in local/offline V1 without explicit decision"
    exit 1
  fi
done
echo "OK: no INTERNET or CAMERA permission declared"

echo
echo "== Diff whitespace check =="
git diff --check
git diff --cached --check

echo
echo "== JVM and Android unit tests =="
./gradlew test

echo
echo "== Android debug build =="
./gradlew :app:assembleDebug

echo
echo "== Android lint =="
./gradlew :app:lintDebug

echo
echo "== Diff summary =="
git diff --stat
git diff --cached --stat
