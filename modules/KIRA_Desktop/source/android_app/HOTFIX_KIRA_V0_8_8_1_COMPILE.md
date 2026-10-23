# Kira v0.8.8.1 – Compile Hotfix

Fix for `MainActivity.kt` in `showSettingsQuickMenu()`.

`LinearLayout.LayoutParams(width, height, Gravity.END)` accidentally selected the three-argument constructor whose third parameter is `weight: Float`. `Gravity.END` is an `Int`, producing the Android Studio compiler error `Argument type mismatch`.

Corrected to:

```kotlin
LinearLayout.LayoutParams(dp(48), dp(46)).apply {
    gravity = Gravity.END
    topMargin = dp(3)
    bottomMargin = dp(3)
}
```

Also scanned the remaining `LinearLayout.LayoutParams` calls in `MainActivity.kt`; the other three-argument calls correctly use a `Float` weight.
