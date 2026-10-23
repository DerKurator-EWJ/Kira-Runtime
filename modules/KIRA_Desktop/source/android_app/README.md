# Kira v0.9.1 – DREAM World Runtime

DREAM is now a persistent world machine rather than a one-shot film effect. It evolves a `DreamWorldState` continuously, works from an empty bootstrap state, accepts MASTER-routed Text/Audio/Camera as modulation in reactive modes, and can drive both the visual world renderer and Orchestra from the same state. External Base/Forge knowledge is normalized into native Dream traits so a future Oven/Hugging-Face pipeline can teach the runtime without coupling it to a specific teacher model.

See `RELEASE_KIRA_V0_9_1.md`.

---

# Kira v0.9.0 – Routing Surface / Media Reset

- Top surface remains `IN · MASTER · FEATURE · SETTINGS`.
- `IN` only admits Text/Audio/Camera. `MASTER` only patches those three inputs into Text/Video/Audio targets.
- `SETTINGS` is now also the Core-mode switcher: WRITE, VIDEO, MUSIC, CODE and MATH. The selected mode changes the operative generator, not the shared memory.
- `FEATURE` is the only quick visual/effect layer. Matrix, ID-water, Now Gate, relation trace, tiers, depth and media live there.
- Media can now be reset to **KEIN MEDIUM** after an image/video was selected. A persistent stage background color can be chosen independently (black, graphite, white, green, blue, warm).
- Quick menus use one opaque black-glass panel so the chat/background does not bleed through between rows.
- Video and Audio outputs are operational by default when enabled: Video implies the procedural film generator; Audio implies Orchestra.
- Cross-modal routing is target-specific again: text generation consumes the TEXT patch, visual deformation/film consumes VIDEO, Orchestra consumes AUDIO.
- Low-latency camera packets are filtered through the AUDIO master patch before reaching audio output.

# Kira v0.8.9 – Settings-Owned Output Router

The old OUT button and legacy output overlay are gone. The product surface now separates responsibilities strictly: IN admits stimuli, MASTER routes them, FEATURE recalls effects, and Settings owns the visible/audible primary outputs (TEXT / VIDEO / AUDIO / FILM). Each output can be enabled independently or selected exclusively with a `NUR …` action.

See `RELEASE_KIRA_V0_8_9.md`.

---

# Kira v0.8.8.1 – Compile Hotfix

Fixes the Quick Settings row `LayoutParams` type mismatch from v0.8.8. No functional routing or learning behavior was changed.

# Kira v0.8.8

Top-level UI is now a five-point patch surface: IN / OUT / MASTER / FEATURE / SETTINGS.
See `RELEASE_KIRA_V0_8_8.md`.

# Kira v0.8.7

## Dialectic Runtime / Bootstrap Generators

- Product chat shows the result only; the mirrored relation path is a separate `RELATIONSWEG` output feature.
- One modality-independent thesis/antithesis decision projection evaluates the active WorkingSet and exposes F → … → SSS → Ω → Ω+n tiers.
- Two compact glass bands above the chat visualize only those tier symbols: light glass for thesis, dark glass for antithesis.
- Competing declarative chat memories are selected by branch stability instead of always exposing both sides.
- VIDEO has a procedural film surface even without selected media or learned memory.
- ORCHESTRA has a quiet procedural seed ensemble when no learned acoustic macros exist; learned voices replace it automatically.
- Existing shared macro memory, per-mode stimulus masks and operational math/coding modules remain intact.

See `RELEASE_KIRA_V0_8_7.md`.

---

# Kira v0.8.6

Kira now uses one shared persistent MIND/FABRIC memory behind five primary Core Output modes: Writing, Video, Music, Coding and Math. The first top control selects this core mode; Microphone and Camera remain physical inputs, followed by Output and Settings.

Text now feeds the same recursive `MemoryModel` macro hierarchy used by sensor learning. Each core mode stores only an input-influence mask (Text / Audio / Camera); it does **not** own a separate memory. A new black-glass ID-depth slab shows the active IDs, abstraction tiers and nesting of the current working state.

See `RELEASE_KIRA_V0_8_6.md`.

---

# Kira v0.8.4

Kira is the Android product surface of the Equinox relational MIND/FABRIC project.

The product now follows one stable composition model:

```
INPUT  ->  EQUINOX STATE  ->  COMPOSABLE OUTPUTS
```

The top dock controls only the three physical/user inputs: Text, Microphone and Camera. The fourth control opens the Output Workbench. Outputs and work capabilities are independent switches and can be combined instead of forcing the user into mutually exclusive app modes.

## Output Workbench

The panel remains open while switches are changed. Use `EXIT` or tap outside it to leave. The old bottom Kira/Background/Gate view selector is removed.

- Text-Chat: chat visibility plus Writing, Coding and Math capability flags.
- Background: a clean split between Image/Video and Effects.
- Effects: ID Matrix, ID Water, Das Tor zum Jetzt, pulse/speed/strength controls.
- Visual capabilities: Film Generation and Free Canvas flags.
- Sound: relational sound and Orchestra.

Image/video is never conflated with ID-Water or other effects. The selected medium is one layer; effects are separate layers over the same Equinox state.

## Data continuity

The Android application ID is still `de.equinox.v05`, so installing an update over the existing app should retain app-private data. v0.8.4 additionally keeps redundant copies of chat history and local language triples under `filesDir/kira_continuity/` and can restore the primary files from those copies on startup.

Uninstalling Kira or choosing Android "Clear storage" still deletes private app data. For development, install the new APK over the old one rather than uninstalling first.

See `RELEASE_KIRA_V0_8_4.md` for the exact release delta.

## Kira v0.8.5 — Operational Module Bus

Die vier Arbeitsfähigkeiten MATHE, CODING, FILM und ORCHESTRA laufen nun über `KiraOperationalRegistry` und ein gemeinsames read-only `KiraModuleContext`-Paket. Damit können gelernte Relationen und der aktive MIND/FABRIC-Zustand mit Generatoren interagieren, ohne direkte Kopplungen zwischen den Modulen zu erzeugen.

Oben: Text-Input · Audio-Input · Kamera-Input · Ausgaben · Einstellungen.

Details: `RELEASE_KIRA_V0_8_5.md`.

## v0.9.2 — Unified Memory Core

Kira besitzt ab v0.9.2 wieder einen eindeutigen lokalen Lernpfad: Pattern-Candidates, F→Ω+n-Makros, MIND/Loom-Relationen und persistente Text-Surfaces liegen im gemeinsamen Indexed-Store. Der Chatverlauf ist kein Gedächtnis mehr. Audio-/Vision-Codebooks bleiben separat, weil sie Encoderzustand und kein semantisches Wissen darstellen. DREAM bleibt Runtime-State.

Neue Host-Prüfung: `bash host_tests/run_store_v2_test.sh`.

## v0.9.2.1 — Identity + Curiosity

- Terminal identities are fixed and capitalized: `Kira` / `Kurator`.
- Identity protocol is not learned memory and cannot be renamed by ordinary chat learning.
- Open or ambiguous dialogue states can produce concise autonomous questions.
- Near-tied thesis/antithesis branches ask for clarification instead of forcing a winner.
- Strong simultaneous Audio/Vision context can trigger a cross-modal clarification question.
- Autonomous questions remain UI dialogue history and are not fed back as facts.
- `EquinoxChatEngine.askAutonomously()` exposes the same mechanism to future DREAM/sensor/planner triggers.

---

## Kira v0.9.3 — Universal Macro Compiler

v0.9.3 establishes one structural compiler and one sparse read API for all learned modalities.
Text, MIND relations, audio, vision, cross-modal nodes and future Forge/BASE knowledge now share
the same F→Ω+n tier contract. The persistent v0.9.2 store format remains compatible.

See `RELEASE_KIRA_V0_9_3.md` for details.

---

## Kira v0.9.4 — Unified Decision Core

One modality-neutral decision stage now evaluates the universal F→Ω+n working memory before TEXT, VIDEO/DREAM and AUDIO outputs. Thesis and Antithesis are competing structural paths, not separate memories and not true/false labels. Stable winners are emphasized, competing evidence remains available, and OPEN states preserve both paths for clarification or further observation.

See `RELEASE_KIRA_V0_9_4.md`.

## Kira v0.9.5 — Native Text Engine

A local deterministic German surface generator now sits after Unified Memory, the universal
F→Ω+n macro hierarchy and the Unified Decision Core. Normal chat displays concise answers rather
than Kira's internal mirror loop; the latter remains optional through Relationsweg. Native output
is not fed back as new evidence, preventing a self-reinforcing language loop.

See `RELEASE_KIRA_V0_9_5.md`.


## Kira v0.9.6 — Complete Cross-Modal Native Runtime

The 3×3 MASTER matrix is now consumed by a single `KiraCrossModalRuntime`; all nine IN→OUT paths have native runtime effects. See `RELEASE_KIRA_V0_9_6.md`.

## Kira v0.9.7 — Forge Intermediate Specification v1

External teachers now have one neutral contract: `kira-forge-intermediate-v1`. Hugging Face/PyTorch/
ONNX specifics stay in desktop adapters; Kira validates semantic atoms, relations, sequences, traits,
vectors and macro candidates, assigns deterministic BASE IDs and computes F→Ω+n depth itself.
Android exposes a non-destructive Forge-v1 dry-run. Atomic BASE materialisation follows in v0.9.8.

See `RELEASE_KIRA_V0_9_7.md` and `external_oven/FORGE_INTERMEDIATE_V1.md`.

## Kira v0.9.8 — BASE Package / Validator / Hot Mount

Forge Intermediate v1 is now compiled outside the app into a checksummed `.kirabase` runtime package. Android validates SHA-256 payloads, stages the indexed BASE, validates all F→Ω+n references, and swaps the complete package atomically. One previous BASE is retained for rollback. BASE can be enabled/disabled, updated, switched, rolled back or removed without touching LIVE/PERSONAL memory.

See `RELEASE_KIRA_V0_9_8.md` and `external_oven/BASE_PACKAGE_V1.md`.
