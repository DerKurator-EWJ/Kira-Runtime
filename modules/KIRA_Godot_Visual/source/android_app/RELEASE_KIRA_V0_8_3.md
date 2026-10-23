# Kira v0.8.3 — Flexible Recall / Neural Backdrop

This release hardens the two areas that were still too mechanical in v0.8.2: Kira's text recall and the visual output stack.

## Kira: competitive recall instead of one frozen relation

- Recall is no longer winner-takes-all.
- Up to five relevant local relations may compete for one prompt.
- Earlier questions are strongly down-ranked when Kira is answering a question, so `Was ist X?` cannot become its own permanent answer.
- Declarative memories receive an answer bonus for question prompts.
- Equally relevant memories are rotated/combined across repeated queries instead of always returning the same first surface.
- Explicit positive/negative conflicts are surfaced as `◇ offen` rather than silently choosing one side.
- Visible Kira output remains restricted to letters/symbols; numeric IDs stay internal/LAB-only.

Example:

```
kurator  Toastbrot ist ein weiches geschnittenes Brot
kurator  Toastbrot wird oft getoastet
kurator  Was ist Toastbrot?

kira     ◆ antwort · toastbrot wird oft getoastet · toastbrot ist ein weiches geschnittenes brot
```

## ID water is now independent

The former Matrix lens was removed. Water no longer consumes falling Matrix glyph coordinates.

- ID water receives only the current 63-bit Equinox IDs.
- Organic droplet geometry is derived deterministically from those IDs.
- Uploaded images are actually deformed through a bitmap mesh under the water field.
- Highlights, wakes and shadows use irregular changing droplet paths rather than rigid falling circles.
- On Android 13 / API 33+ uploaded video can use a runtime pixel-warp shader; older Android versions keep the video pixels untouched and retain only the optical water surface fallback.
- ID water has its own enable and strength controls under `Ausgaben → Hintergrund → Effekte`.

## Das Tor zum Jetzt: neural deformation only

The previous visible tree matrix has been retired.

- No trunk, root, node circles or branch lines are drawn.
- Every active working node receives a deterministic position without a global centre.
- Actual active exchanges form temporary relation fields between those points.
- Relations become visible only because the underlying background pixels bend along the relation corridor.
- Connection pulses are narrow and intermittent, producing lightning-like kinks without drawing a lightning line.
- Local node pulses deform nearby pixels and can originate anywhere in the image.
- If an active working set temporarily has no explicit exchange, sparse deterministic neighbour pairing keeps the field responsive without creating a persistent topology.
- The old `NowGateView` remains only as a transparent compatibility shell; product rendering lives in `MediaBackdropView`.

## Matrix continuity

The visible ID matrix remains a separate renderer.

- It retains physical lanes between prompts.
- New questions replace ID identities without resetting column positions.
- It moves continuously while Equinox is working and freezes when Equinox is idle.
- ID water and Now Gate no longer require the visible ID matrix to be enabled.

## Input / output separation

Top dock remains input-only:

- Text
- Microphone
- Camera
- Outputs

Outputs remain composable:

- Kira glass chat
- Media background
- ID matrix
- ID water
- Das Tor zum Jetzt
- Sound

## Validation

- `host_tests/run_mind_core_test.sh` -> `MindCoreSelfTest OK`
- Added coverage for multi-relation Toastbrot recall, question-self suppression and contradictory memories.
- Android source targets compileSdk 35 / minSdk 26 / Java-Kotlin 17.
- Full APK build is not claimed in the assembly environment because Android SDK/Gradle Android tooling is not installed there.
