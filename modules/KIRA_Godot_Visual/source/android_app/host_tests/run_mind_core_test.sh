#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/equinox-mind-core-test.jar"
kotlinc \
  "$ROOT/app/src/main/java/de/equinox/v05/EquinoxId64.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/EquinoxMindCore.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/AbstractionTier.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/AdaptiveHierarchyPolicy.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/KnowledgeOrigin.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/UniversalMacroCompiler.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/ForgeIntermediate.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/KiraDecisionCore.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/DialecticDecision.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/KiraCrossModalRuntime.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/EquinoxChatLogic.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/KiraNativeTextEngine.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/KiraOperationalModules.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/DreamRuntime.kt" \
  "$ROOT/app/src/main/java/de/equinox/v05/KiraCoreMode.kt" \
  "$ROOT/host_tests/MindCoreSelfTest.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
