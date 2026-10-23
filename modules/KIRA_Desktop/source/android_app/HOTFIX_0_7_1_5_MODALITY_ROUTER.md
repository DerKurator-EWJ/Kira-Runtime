# Equinox v0.7.1.5 — Modality Router / UI Architecture

This build restructures the app around three product-level channels while keeping the existing Kira terminal intact.

## Main surface

The main surface now exposes only three persistent channel controls plus Settings:

- `TEXT` — Kira terminal output.
- `VIDEO` — HYPNO / generative visual output.
- `MUSIK` — Unified Audio output.

Interaction is symmetric:

- **single tap**: output channel ON/OFF;
- **double tap**: additional cross-modal input ON/OFF.

The compact channel labels show their routing state:

- `TEXT +AV` = text output can consume analysed Audio + Video context;
- `VIDEO +TA` = video output can consume Text + Audio context;
- `MUSIK +TV` = music output can consume Text + Video context.

All six states are persisted in `equinox_modality_router` preferences.

## Real routing, not UI-only flags

- **Text**: when `+AV` is enabled, the current real `MemoryModel` audio/vision working nodes are captured as `ChatContextSignal`s when a turn is submitted. `EquinoxKernelRepository.commitChatContext()` stores persistent `chat_context` relations from the user turn to those analysed sensor IDs. Kira's structural echo reports the context counts as `ctx=audio:n+video:n`.
- **Video**: when `+TA` is enabled, HYPNO receives a merged read-only WorkingSet of the sensor MemoryModel and the text MIND WorkingSet.
- **Music**: when `+TV` is enabled, Unified Audio receives the merged WorkingSet while retaining the existing sonification engine.
- Physical microphone/camera feeds are started only when an enabled output currently requires them. Combined permission handling was added for TEXT `+AV`.

## Output composition

TEXT and VIDEO may be active simultaneously. In that case the terminal becomes a translucent layer over HYPNO instead of blanking the video surface. MATRIX rain is reduced in opacity when a video layer is active.

## Settings hierarchy

The old flat diagnostic center is no longer the settings root.

Normal path:

- `Einstellungen`
  - `TEXT`
    - output ON/OFF
    - Audio+Video context
    - MATRIX / CLEAN / RAW
    - clear chat history
  - `VIDEO`
    - output ON/OFF
    - Text+Audio context
    - HYPNO description/routing
  - `MUSIK`
    - output ON/OFF
    - Text+Video context
    - feedback/self-output protection
  - `SYSTEM`
    - `ARCHIV`
    - `MIND`
    - `KURATOR`
      - `KURATOR-MODUS`
        - LEARN / PLAY / DREAM
        - `LABOR`

`LABOR` contains the existing deep engineering pages as a separate developer surface:
`INTAKE / HYBRID / ARCHIV / MIND / CONTROL`.

This preserves the old diagnostics and controls without letting them dominate the normal product UI.

## Compatibility

- Existing Kira / Kurator prefixes remain unchanged.
- Existing chat history format remains unchanged.
- Existing local MIND triples and Forge BASE are not migrated or deleted.
- Existing technical pages are retained and moved behind the settings hierarchy rather than removed.

## Validation in this environment

- `MindCoreSelfTest OK`
- `EquinoxChatLogic.kt` compiles together with the pure Kotlin MIND/ID core.
- Android project paths remain short; a full Android Gradle/APK build still requires Android Studio / Android SDK.
