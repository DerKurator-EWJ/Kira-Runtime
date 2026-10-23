# Kira v0.8.4 — Composable Output Workbench

## Product navigation

- The three top input controls remain simple on/off switches: Text, Microphone, Camera.
- The fourth top control opens one persistent Output panel.
- Output panels no longer dismiss after each toggle. They close only via `EXIT` or an outside tap.
- The old three-button bottom view selector is removed from the product surface.

## Output hierarchy

```
AUSGABEN
├─ TEXT-CHAT
│  ├─ Text-Chat
│  ├─ Schreiben
│  ├─ Coding
│  └─ Mathe
├─ HINTERGRUND
│  ├─ BILD / VIDEO
│  │  ├─ medium visible on/off
│  │  ├─ choose/replace image or video
│  │  └─ remove medium
│  ├─ EFFEKTE
│  │  ├─ ID-Matrix
│  │  ├─ ID-Wasser
│  │  ├─ Tor zum Jetzt
│  │  ├─ Matrix pulse
│  │  ├─ flow speed
│  │  └─ water strength
│  ├─ Film-Generierung
│  └─ Freie Leinwand
└─ KLANG
   ├─ Klang
   └─ Orchestra
```

`Schreiben`, `Coding`, `Mathe`, `Film-Generierung` and `Freie Leinwand` are persistent capability-routing flags in this release. They are deliberately independent and combinable; specialized generation engines can bind to these flags later without changing the UI architecture. `Orchestra` already activates the existing relational sound bus.

## Media vs effects

Image/video selection is a separate branch from effects. ID-Water, Matrix and Now Gate never pretend to be the selected medium. Effects can remain armed independently and become visible whenever a compatible background is present.

## System bars

The Android status/navigation bars are transparent so the Kira stage continues visually behind the system controls while Kira's content still respects insets.

## Data continuity

- Application ID remains `de.equinox.v05`.
- Normal install-over/update preserves Android `filesDir` and SharedPreferences.
- Chat history and the local language triple store now also maintain redundant continuity copies under `filesDir/kira_continuity/` and self-heal the primary copy on startup if necessary.
- Uninstalling the app or using Android "Clear storage" still removes app-private data; no app can preserve those files across a deliberate storage wipe without an external/exported backup.

## Version

- versionCode: 84
- versionName: `0.8.4-kira-composable-output-workbench`
