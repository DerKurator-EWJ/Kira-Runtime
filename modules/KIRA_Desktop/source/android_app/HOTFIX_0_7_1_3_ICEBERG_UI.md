# Equinox v0.7.1.3 — Iceberg Terminal UI

Primary surface redesign without changing the MIND/Forge data model.

- Terminal/chat is now the always-present primary product surface.
- No chat bubbles. Free monospace text with fixed identities:
  - `kurator@equinox:~#`
  - `equinox@mind:~#`
- MATRIX uses exact `#00FF00` foreground text.
- Flat bottom composer: only the terminal `>` prompt plus the text field.
- IME Send / hardware Enter submits; no large SEND control is shown.
- `/render matrix`, `/render clean`, `/render raw` switch the live display surface without writing a fake knowledge fact.
- MATRIX activity rain is data-driven from real Equinox BLAKE2b-63 IDs used by the chat/MIND ingest path. No random semantic payload is generated.
- Activity animation schedules frames only while the submit path is working. Completion freezes the last ID-derived frame; there is no idle animation loop.
- Normal settings expose only display selection and the version line.
- Holding `Equinox v0.7.1` for 5 seconds reveals the existing INTAKE / ARCHIVE / MIND / CONTROL machine room.
- Legacy HYPNO canvas is hidden under the primary terminal by default instead of rendering invisibly behind it.

Validation performed in this environment:
- `host_tests/run_mind_core_test.sh` -> `MindCoreSelfTest OK`
- Pure Kotlin compile of `EquinoxId64.kt`, `EquinoxMindCore.kt`, and `EquinoxChatLogic.kt` -> OK

A complete Android APK build is not available in this container because the Android SDK/Gradle toolchain is not installed here; Android Studio should perform the final app compile.
