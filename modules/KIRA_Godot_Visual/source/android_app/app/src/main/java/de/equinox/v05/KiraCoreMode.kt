package de.equinox.v05

/**
 * v0.8.6: one persistent memory, several primary expression modes.
 * The mode does not own a separate database. It only selects the primary generator
 * and which live stimuli are allowed to participate in the current working state.
 */
enum class CoreOutputMode(val label: String, val symbol: String) {
    WRITE("SCHREIBEN", "✍"),
    VIDEO("VIDEO", "🎞"),
    MUSIC("MUSIK", "♫"),
    CODING("CODING", "⌘"),
    MATH("MATHE", "∑"),
}

data class CoreStimulusMask(
    val text: Boolean = true,
    val audio: Boolean = true,
    val camera: Boolean = true,
) {
    fun acceptsModality(modality: String): Boolean = when (KiraModalityClassifier.familyOf(modality)) {
        KiraModalityFamily.TEXT -> text
        KiraModalityFamily.AUDIO -> audio
        KiraModalityFamily.VISION -> camera
        KiraModalityFamily.CROSSMODAL -> audio && camera
        KiraModalityFamily.FORGE, KiraModalityFamily.OTHER -> true
    }
}

object TextMacroIdentity {
    /** Dedicated positive domain keeps text tokens away from the ordinary sensor ranges. */
    fun tokenId(token: String): Int {
        val raw = EquinoxId64.stableId("text_macro_token", ChatTextLogic.normalize(token))
        return (0x60000000 or ((raw xor (raw ushr 32)).toInt() and 0x0fffffff)).coerceAtLeast(1)
    }
}
