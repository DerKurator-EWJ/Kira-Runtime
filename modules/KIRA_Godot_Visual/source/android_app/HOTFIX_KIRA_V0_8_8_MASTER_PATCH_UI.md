# Kira v0.8.8 — IN / OUT / MASTER PATCH UI

- Top bar is now five fixed controls: `IN⌄`, `OUT⌄`, `M`, `✦`, `⚙`.
- IN opens three compact black-glass input gates: text, microphone/audio and camera.
- OUT opens three compact output gates: text chat, procedural video/film and audio.
- MASTER opens a persistent 3×3 patch bay. Rows are inputs, columns are outputs.
  - text destination cables: red
  - video destination cables: blue
  - audio destination cables: yellow
- Input permission and output routing are separated:
  - IN decides whether a modality enters the shared memory/state at all.
  - MASTER decides which live modalities are allowed to influence each output family.
- Film receives the VIDEO-routed WorkingSet; Orchestra receives the AUDIO-routed WorkingSet;
  text/coding/math receive the TEXT-routed WorkingSet.
- FEATURE is the persistent quick surface for ID Matrix, ID Water, Now Gate, relation trace,
  dialectic tiers, macro depth, film, orchestra and background media.
- Settings is no longer an immediate full sheet. It first opens nine vertically stacked
  black-glass icon boxes under the gear. One tap expands the selected row leftward to reveal
  its label; a second tap opens that settings section.
- Opening Settings always hides the Android keyboard and clears composer focus.
- Quick IN / OUT / FEATURE menus remain open while toggles are changed.
