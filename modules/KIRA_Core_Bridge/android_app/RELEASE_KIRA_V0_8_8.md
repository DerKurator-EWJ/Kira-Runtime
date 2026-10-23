# Kira v0.8.8 — Master Patch Surface

This release turns the top bar into a physical routing metaphor rather than a mode selector.

## Top surface

`IN⌄ | OUT⌄ | M | ✦ | ⚙`

### IN
Three source gates: text, microphone/audio, camera. These gates control whether a modality is
allowed to enter Kira's shared state and learning stream.

### OUT
Three destination gates: text, video/film and audio.

### MASTER
A 3×3 input-to-output patch grid. Each cell is an actual routing decision. Active connections
are drawn as cables only while enabled. Cable colour is destination based: red=text,
blue=video, yellow=audio.

The routing matrix is persisted and translated into Kira's existing stimulus masks:
- text destination -> WRITE/CODING/MATH
- video destination -> FILM/VIDEO
- audio destination -> MUSIC/ORCHESTRA

The memory stays shared; output views receive filtered working snapshots rather than separate
memories.

### FEATURE
A quick persistent overlay for visual/audio features and diagnostics: ID Matrix, ID Water,
Now Gate, relation trace, dialectic tiers, macro depth, Film, Orchestra and background media.

### Settings
The gear first opens nine compact right-aligned black-glass boxes. The selected item grows to
the left and reveals its text label. A second press enters that settings section. The keyboard
is hidden before the settings UI opens.
