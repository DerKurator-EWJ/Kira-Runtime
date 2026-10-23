# Equinox v0.7.1 Matrix Chat — Hotfix 1

Fixed Android/Kotlin compile blocker in `MainActivity.showPage()`:

- `AppPage.MIND` is now handled explicitly in the exhaustive `when (page)`.
- Every page branch explicitly hides `mindPage` when another page is shown.
- Android versionCode: 66
- Android versionName: `0.7.1-matrix-chat-hotfix1`

Validation performed in this package:

- AppPage enum values: LIVE, HYBRID, MEMORY, MIND, LAB
- showPage branches: LIVE, HYBRID, MEMORY, MIND, LAB
- Missing branches: none
- `host_tests/run_mind_core_test.sh`: `MindCoreSelfTest OK`

A full Android Gradle/APK build was not executed in the packaging environment because the Android SDK/Gradle toolchain is not installed there.
