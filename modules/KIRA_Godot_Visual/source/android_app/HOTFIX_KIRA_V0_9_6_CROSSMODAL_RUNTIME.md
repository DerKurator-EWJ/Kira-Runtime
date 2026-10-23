# Kira v0.9.6 — Complete Cross-Modal Native Runtime

v0.9.6 turns the 3×3 MASTER patch from a routing UI into one explicit runtime contract.

## Runtime matrix

Every admitted input can now affect every native output:

| IN \ OUT | TEXT | VIDEO/DREAM | AUDIO/ORCHESTRA |
|---|---|---|---|
| Text | memory + native answer | film/dream structure | deterministic bridge voices |
| Audio | routed perception/context + rate-limited live text pulse | motion/pulse/tension | learned audio voices / synthesis |
| Camera | routed perception/context + rate-limited live text pulse | spatial motion/novelty | deterministic bridge voices |

`KiraCrossModalRuntime` is read-only. It owns no memory and does not create a new
learning system. IN remains admission, MASTER remains routing, and the Unified Memory
Core remains the only learned knowledge source.

## Text bridge

Raw sensor structure is never renamed as semantic knowledge. When AUDIO→TEXT or
CAMERA→TEXT is connected, Kira can report the presence/strength of the routed sensor
state and answer perception questions without inventing object/sound labels. Strong new
sensor states may produce a rate-limited autonomous native status line; those lines are
UI output only and are never learned back into memory.

## Video bridge

The procedural film directive now carries separate text/audio/camera drives. Text changes
structural ordering/seed, audio changes pulse/rhythm, and camera changes warp/motion.
DREAM receives the same projection through `DreamInfluence`.

## Audio bridge

Orchestra now receives the same cross-modal projection. If no learned audio macro is
available, routed text or camera IDs can create deterministic synthesized bridge voices.
No samples or external model are required. Learned audio macros still take precedence.

## Compatibility

No persistence schema change. v0.9.2/v0.9.3/v0.9.4/v0.9.5 learned memory remains compatible.

## Validation

- `host_tests/run_mind_core_test.sh` → `MindCoreSelfTest OK`
- `host_tests/run_store_v2_test.sh` → `IndexedStoreV2SelfTest OK`
- 3×3 route self-test covers all nine individual MASTER cells
- sensor→text perception smoke test passes
- `GenerativeOrchestraSynth.kt` compiled against minimal host stubs after bridge-voice changes
- full Android APK build is not available in this container because Android SDK/Gradle wrapper are absent

Version: `0.9.6-kira-crossmodal-native-runtime` / versionCode `98`.
