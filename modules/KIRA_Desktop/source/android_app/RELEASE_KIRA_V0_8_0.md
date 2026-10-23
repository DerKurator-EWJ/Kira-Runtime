# Kira v0.8.0 — Unified Expression Interface / Das Tor zum Jetzt

This release consolidates the v0.7.1 UI work into one product-facing Kira build while keeping the Equinox core/package namespace intact.

## Product identity

- Android app label: **Kira**.
- Launcher icon: black infinity glyph on a white background, centered and intentionally classical/minimal.
- `kurator` and `kira` remain presentation identities only; terminal prefixes are not injected into learned semantic payloads.

## Main surface

The control surface is symbolic and separates active capabilities from the selected renderer.

Top capability row:

- chat bubble = Kira text output on/off
- door = **Das Tor zum Jetzt** on/off
- music mark = relational sound output on/off

Bottom renderer row above the composer:

- diamond = Kira mirror-text renderer
- vertical ID mark = ID-flow renderer
- door = **Das Tor zum Jetzt** renderer

The selected view never implicitly toggles a capability.

## ID flow

The old Matrix background is now treated as an independent output renderer, not as a compute-speed meter and not as a chat background.

- IDs come from real active BLAKE2b-63 MIND/FABRIC activity plus deterministic 63-bit IDs derived from the currently active WorkingSet.
- flow speed changes presentation only; it cannot slow down or accelerate core computation.
- optional green pulse is a visual effect only.
- Kira settings expose slow / medium / fast flow and pulse on/off.

## Das Tor zum Jetzt

The consumer-facing name **HYPNO** is removed from normal settings. Internally the older GPU HYPNO renderer remains available to LAB/legacy code, but the product view is a new read-only `NowGateView`.

The gate:

- consumes the same routed `WorkingSetSnapshot` as the rest of the output system;
- never displays raw camera pixels;
- turns active relational nodes into deterministic recursive trees;
- bends relation edges and branches into a shared vortex;
- derives colour, branch density, glow and motion from active state values and IDs;
- never writes presentation state back into MemoryModel.

Camera, audio and text are therefore inputs to the relational state. The gate is only one possible visual expression of that state.

## Settings

Normal settings are presented as:

- KIRA
- DAS TOR ZUM JETZT
- KLANG
- SYSTEM

Deep diagnostics stay under:

`System -> Kurator -> Kurator-Modus -> Labor`

## Compatibility

- package / applicationId remains `de.equinox.v05` so this is still an in-place evolution of the existing project;
- existing local MIND, archive, Forge BASE and chat persistence paths are preserved;
- legacy internal `UnifiedPresentationMode.HYPNO` stays intact for developer/LAB compatibility.
