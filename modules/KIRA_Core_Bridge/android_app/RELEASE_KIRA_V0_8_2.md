# Kira v0.8.2 — Layered Output Stage

## Product model

Kira no longer treats text, ID-flow and the Now Gate as mutually exclusive view modes. The product surface is one compositing stage:

1. **INPUT** — text, microphone and camera are controlled by the three top switches.
2. **STATE** — Equinox/MIND/FABRIC forms the shared relational working state.
3. **OUTPUT LAYERS** — Kira chat, background media, ID-flow, Now Gate and relational sound can be enabled independently.

The fourth top button opens the output menu.

## Output menu

- `✎ TEXT-CHAT`
- `▧ HINTERGRUND`
  - choose an image or video from Android Storage Access Framework
  - enable/disable the chosen medium
  - enable/disable the ID matrix
  - `EFFEKTE`
    - Matrix lens / water-drop refraction
    - green pulse
    - flow speed
    - refraction strength
- `🚪 TOR ZUM JETZT`
- `♫ KLANG`

The selected image/video URI is stored with a persistable read permission when the provider supports it.

## Layered chat

Kira and Kurator messages now remain exclusively on translucent black glass slabs. The background stage remains visible around the slabs and is heavily attenuated beneath them, allowing ID motion or a media background to remain perceptible without destroying readability.

The lower three shortcuts are not modes:

- `✎` toggles Kira text chat,
- `▧` opens background controls,
- `🚪` toggles the Now Gate.

## Media background

Images and videos can be selected as the background output. Images use local circular lens transforms driven by the active ID-flow positions, producing actual local magnification/refraction. Video uses the same ID-driven moving lens highlight/shadow field over the looping TextureView; full per-pixel GPU video refraction is intentionally left for a later shader pass.

Uploaded media is an output canvas only. Selecting a background does not silently feed that file into MIND or the camera-learning path.

## Persistent ID-flow

ID columns are no longer reconstructed from the top on each text turn. When IDs change, existing physical lanes are recycled in place: position, vertical flow and speed survive while the glyph identity changes. The matrix therefore appears continuous across questions.

Animation is tied to actual work:

- chat submission,
- active microphone analysis,
- active camera analysis,
- memory/dream/hybrid processing.

When Equinox is idle, the last frame freezes. Presentation speed never changes compute speed.

## Now Gate

The Now Gate remains the originless tree-matrix renderer introduced in v0.8.1, but its canvas is transparent in v0.8.2 so it can participate in the same output-stage composition instead of replacing the entire screen.

## Audio

Relational audio output is restored as a first-class independent output (`♫ KLANG`). Microphone remains an input and is not coupled to audio output implicitly.
