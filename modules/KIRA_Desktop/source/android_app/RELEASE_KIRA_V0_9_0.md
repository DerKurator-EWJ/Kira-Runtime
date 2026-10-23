# Kira v0.9.0 – Routing Surface / Media Reset

## Surface

`IN` admits stimuli. `MASTER` connects stimuli to output state. `FEATURE` overlays effects. `SETTINGS` owns outputs, module tuning and the active Core mode.

The settings quick menu is a black-glass mode switcher with WRITE, VIDEO, MUSIC, CODE and MATH plus OUTPUT, MIND and SYSTEM. First tap expands a row; the second selects its Core mode (when applicable) and opens its configuration.

## Media

Media is no longer a one-way choice. `FEATURE -> MEDIA` exposes image/video selection, `KEIN MEDIUM`, and a persistent stage color palette. Clearing media releases the current image/video while leaving the rest of Kira intact.

## Cross-modal routing

All outputs consume their own MASTER target state:
- TEXT <- any enabled master inputs
- VIDEO/FILM <- any enabled master inputs
- AUDIO/ORCHESTRA <- any enabled master inputs

Video output now activates the procedural film module even without learned data; Audio output activates Orchestra even without learned data. The shared memory remains single and unchanged.

## UI

IN, FEATURE and SETTINGS popups now sit on coherent black-glass plates rather than transparent gaps over the chat. Primary settings pages were shortened to switches, routing summary and actual tuning controls.
