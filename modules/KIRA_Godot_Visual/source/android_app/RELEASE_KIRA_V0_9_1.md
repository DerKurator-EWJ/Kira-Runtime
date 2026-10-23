# Kira v0.9.1 – DREAM World Runtime

DREAM is no longer a frame effect. It owns a persistent world-state which evolves continuously and can be rendered, sonified and modulated by Kira's shared state.

## Runtime

- `DreamWorldState` persists seed, tick, world nodes, links, camera, environment and world dynamics.
- `DreamRuntime.step(...)` advances the world independently from the renderer.
- The bootstrap world works with an empty learned database.
- The compact world state is persisted in `equinox_modality_router` and restored on the next app start.

## Control modes

- **AUTONOM** – no live external influence; the world evolves from bootstrap/native knowledge and optional learned memory traversal.
- **REAKTIV** – live MASTER-routed inputs modulate an otherwise self-running world.
- **INSTRUMENT** – stronger live influence; Text/Audio/Camera can be used to play the world.
- **FUSION** – combines strong learned-memory propagation with live routed input.

Text influence is accepted only when Text input is enabled and `TEXT -> VIDEO` is patched in MASTER. Audio and Camera follow their corresponding VIDEO-target MASTER routes.

## Visual output

`DreamWorldView` is a read-only 2.5D renderer for the world state. It renders an endless moving field, world entities, relations, environment and camera motion. The renderer can later be replaced by OpenGL/Vulkan without changing the world runtime.

## Orchestra coupling

`UnifiedAudioEngine` can consume the same `DreamWorldState`. Dream energy, novelty, tension and environment modulate pitch, voice amplitude and tempo, so visual and audio output can be two expressions of one world.

## Oven / external knowledge bridge

External Base macro headers and mounted Forge facts are normalized into `DreamKnowledgeAtom` values (shape, motion, color, relation and stability bias). DREAM therefore consumes Kira-native traits rather than depending directly on Hugging Face or teacher formats. The bridge refreshes after Base install, Forge mount and Forge unmount.

## Settings

`KIRA / VIDEO / DREAM` exposes:

- start / stop
- AUTONOM / REAKTIV / INSTRUMENT / FUSION
- input influence
- world speed
- Dream visual
- Dream -> Orchestra
- reset world
- current native Oven/Base trait count

## Validation

- `host_tests/run_mind_core_test.sh`: `MindCoreSelfTest OK`
- Dream runtime codec, long-running bounded world evolution and control modes are covered by pure-Kotlin host tests.
- Full Android APK compilation is not available in the build container because Android SDK/Gradle wrapper are unavailable.
