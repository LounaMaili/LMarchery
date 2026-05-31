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

echo
echo "== Diff whitespace check =="
git diff --check

echo
echo "== Tests =="
./gradlew test

echo
echo "== Diff summary =="
git diff --stat
