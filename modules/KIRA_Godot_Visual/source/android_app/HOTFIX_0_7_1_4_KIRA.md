# Equinox v0.7.1.4 — Kira Identity + Structural Echo

- Canonical terminal identities are now centralized and exact:
  - `kurator@equinox:~# `
  - `kira@mind:~# `
- The internal persisted role remains `EQUINOX` for backward compatibility with existing chat history; only the presentation identity is Kira.
- Identity prefixes are **not** injected into semantic learning payloads, so MIND learns the actual user text rather than terminal decoration.
- Every submitted prompt now receives an immediate structural Kira echo from real local routing data (`LEARN`, `REPEAT`, `ASSOC`, or `BASE`) instead of pretending to know a natural-language answer.
- The existing BLAKE2b-63 activity rain remains coupled to real input/FABRIC IDs and freezes when processing ends.
- The composer now shows `kurator> ` and has a minimal green `→` touch sender while Enter/IME Send still works.
- The 5-second hidden machine-room gate remains unchanged.
