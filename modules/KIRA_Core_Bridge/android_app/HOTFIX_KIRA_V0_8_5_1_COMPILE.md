# Kira v0.8.5.1 – Compile hotfix

- Fixes Android/Kotlin compile error in `MainActivity.kt` line 842.
- `performHapticFeedback(...)` is now correctly invoked on `settingsMenuButton`, which is the View receiving the interaction.
- Other haptic calls were reviewed; they already execute inside `TextView.apply { ... }` receiver scopes or explicitly on a View.
- `MindCoreSelfTest OK`.
