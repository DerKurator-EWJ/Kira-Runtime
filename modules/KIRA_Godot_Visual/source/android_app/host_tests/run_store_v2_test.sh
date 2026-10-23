#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/kira-indexed-store-v2-test.jar"
kotlinc \
  "$ROOT/host_tests/stubs/android/content/Context.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/IndexedLongTermStore.kt" \
  "$ROOT/host_tests/IndexedStoreV2SelfTest.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
