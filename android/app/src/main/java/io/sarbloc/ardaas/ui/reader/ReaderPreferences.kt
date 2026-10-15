package io.sarbloc.ardaas.ui.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sarbloc.ardaas.composer.LayerToggles
import io.sarbloc.ardaas.content.LayerKind
import kotlin.math.roundToLong

/**
 * The Reader's text-size rule, ported from `ReaderView.swift`: a multiplier
 * on the reading styles, stepped by a tenth between [MIN] and [MAX].
 */
object FontScale {
    const val DEFAULT = 1.0
    const val MIN = 0.7
    const val MAX = 1.6
    const val STEP = 0.1

    /**
     * [current] moved by [delta], snapped to a tenth and kept in range — iOS's
     * `adjustFontScale(by:)`. Snapping stops repeated steps accumulating
     * binary drift (0.7 + 0.1 + 0.1 ≠ 0.9 in floating point).
     */
    fun adjusted(current: Double, delta: Double): Double = normalized(current + delta)

    /** The Larger Text control is offered until the top of the range. */
    fun canIncrease(scale: Double): Boolean = scale < MAX

    /** The Smaller Text control is offered until the bottom of the range. */
    fun canDecrease(scale: Double): Boolean = scale > MIN

    /**
     * Any stored value made safe to render: snapped to a tenth and clamped,
     * with a non-finite value treated as unset. iOS only ever writes in-range
     * tenths; this also absorbs the float round-trip of [ReaderPreferenceStore].
     */
    fun normalized(raw: Double): Double {
        if (!raw.isFinite()) return DEFAULT
        // Half-up, as Swift's `.rounded()` is for the positive values here.
        val snapped = (raw * 10).roundToLong() / 10.0
        return snapped.coerceIn(MIN, MAX)
    }
}

/** The Reader's display preferences as one value. Defaults match iOS's `@AppStorage`. */
data class ReaderPreferences(
    val toggles: LayerToggles = LayerToggles(gurmukhi = true, transliteration = true, english = true),
    val fontScale: Double = FontScale.DEFAULT,
)

/**
 * Persists [ReaderPreferences] across launches — the counterpart of iOS's
 * `@AppStorage`, under the same keys and defaults so the two apps mean the
 * same thing by them. SharedPreferences rather than DataStore: four scalars
 * read once per screen need no new dependency.
 */
class ReaderPreferenceStore(private val prefs: SharedPreferences) {

    fun load(): ReaderPreferences {
        val defaults = ReaderPreferences()
        return ReaderPreferences(
            toggles = LayerToggles(
                gurmukhi = prefs.getBoolean(KEY_SHOW_GURMUKHI, defaults.toggles.gurmukhi),
                transliteration = prefs.getBoolean(KEY_SHOW_TRANSLITERATION, defaults.toggles.transliteration),
                english = prefs.getBoolean(KEY_SHOW_ENGLISH, defaults.toggles.english),
            ),
            fontScale = FontScale.normalized(
                prefs.getFloat(KEY_FONT_SCALE, defaults.fontScale.toFloat()).toDouble(),
            ),
        )
    }

    fun saveLayer(kind: LayerKind, isOn: Boolean) {
        prefs.edit().putBoolean(keyFor(kind), isOn).apply()
    }

    /**
     * Stored as a float — SharedPreferences has no double. Lossless here:
     * every legal value is a tenth, which [FontScale.normalized] restores
     * exactly on load.
     */
    fun saveFontScale(scale: Double) {
        prefs.edit().putFloat(KEY_FONT_SCALE, scale.toFloat()).apply()
    }

    companion object {
        const val FILE_NAME = "reader"

        // The iOS `@AppStorage` keys, verbatim.
        const val KEY_SHOW_GURMUKHI = "reader.showGurmukhi"
        const val KEY_SHOW_TRANSLITERATION = "reader.showTransliteration"
        const val KEY_SHOW_ENGLISH = "reader.showEnglish"
        const val KEY_FONT_SCALE = "reader.fontScale"

        fun keyFor(kind: LayerKind): String = when (kind) {
            LayerKind.GURMUKHI -> KEY_SHOW_GURMUKHI
            LayerKind.TRANSLITERATION -> KEY_SHOW_TRANSLITERATION
            LayerKind.ENGLISH -> KEY_SHOW_ENGLISH
        }

        fun from(context: Context): ReaderPreferenceStore =
            ReaderPreferenceStore(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))
    }
}

/**
 * The preferences as Compose state, written through to [store] on every
 * change so a toggle survives the process being killed mid-recitation.
 */
class ReaderPreferencesState(private val store: ReaderPreferenceStore) {
    private val initial = store.load()

    var toggles: LayerToggles by mutableStateOf(initial.toggles)
        private set

    var fontScale: Double by mutableDoubleStateOf(initial.fontScale)
        private set

    fun setLayer(kind: LayerKind, isOn: Boolean) {
        toggles = toggles.setting(kind, isOn)
        store.saveLayer(kind, isOn)
    }

    fun adjustFontScale(delta: Double) {
        val next = FontScale.adjusted(fontScale, delta)
        if (next == fontScale) return
        fontScale = next
        store.saveFontScale(next)
    }
}
