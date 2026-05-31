#!/usr/bin/env bash
set -euo pipefail

PROMPT_FILE="${1:-prompts/review-current-work.md}"

if [ ! -f "$PROMPT_FILE" ]; then
  echo "Prompt file not found: $PROMPT_FILE"
  exit 1
fi

if [ ! -f AGENTS.md ]; then
  echo "ERROR: AGENTS.md missing"
  exit 1
fi

echo "== Running local checks first =="
git diff --check

if [ -x ./gradlew ]; then
  ./gradlew test
else
  echo "ERROR: ./gradlew missing"
  exit 1
fi

echo
echo "== Running Codex review =="
codex exec "$(cat "$PROMPT_FILE")"
