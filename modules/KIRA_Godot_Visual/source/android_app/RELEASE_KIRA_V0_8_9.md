# Kira v0.8.9 – Settings-Owned Output Router

This release removes the old OUT control and its legacy output overlay from the product surface.

## Product surface

Top-level product controls are now:

- `IN⌄` – admits Text / Audio / Camera stimuli only.
- `M` – edits the 3×3 MASTER patch only.
- `✦` – quick feature/effect switches only.
- `⚙` – owns primary output selection and output tuning.

The old OUT popup and its submenu renderer were removed from `MainActivity`.

## Output ownership

Settings now controls four primary output surfaces independently:

- TEXT
- VIDEO
- AUDIO
- FILM

Each output page has its own AN/AUS control and a `NUR …` action which makes that output exclusive among the four primary outputs.

MASTER still does not create or destroy memory. It only decides which admitted inputs are routed into each output working-state. Every output settings page shows the currently connected MASTER inputs.

## Effect separation

`Tor zum Jetzt` is now an effect switch (`now_gate_effect`) separate from the VIDEO output switch. FEATURE can configure effects, but effects cannot bypass the primary output permission selected in Settings.

In AUDIO-only mode visual background/media/ID-flow layers are suppressed while their saved feature switches are preserved.

## Navigation

Settings no longer duplicates IN controls on its root page. Input admission stays under IN; routing stays under MASTER; output ownership stays under Settings.
