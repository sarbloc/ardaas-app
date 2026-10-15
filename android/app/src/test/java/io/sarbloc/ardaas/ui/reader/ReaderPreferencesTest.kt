package io.sarbloc.ardaas.ui.reader

import android.content.Context
import android.content.SharedPreferences
import io.sarbloc.ardaas.composer.toggles
import io.sarbloc.ardaas.content.LayerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Reader's persisted preferences, against real SharedPreferences: the iOS
 * `@AppStorage` keys and defaults, and that a change survives a fresh load —
 * what "persists across launches" means.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderPreferencesTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(ReaderPreferenceStore.FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun relaunch() = ReaderPreferenceStore(prefs).load()

    @Test
    fun keysAreTheIosAppStorageKeys() {
        assertEquals("reader.showGurmukhi", ReaderPreferenceStore.keyFor(LayerKind.GURMUKHI))
        assertEquals("reader.showTransliteration", ReaderPreferenceStore.keyFor(LayerKind.TRANSLITERATION))
        assertEquals("reader.showEnglish", ReaderPreferenceStore.keyFor(LayerKind.ENGLISH))
        assertEquals("reader.fontScale", ReaderPreferenceStore.KEY_FONT_SCALE)
    }

    /** A first launch shows every layer at the standard size, as iOS does. */
    @Test
    fun defaultsMatchIos() {
        assertEquals(ReaderPreferences(toggles(), 1.0), relaunch())
    }

    @Test
    fun layerTogglesPersistIndependently() {
        val store = ReaderPreferenceStore(prefs)
        store.saveLayer(LayerKind.TRANSLITERATION, false)
        assertEquals(toggles(transliteration = false), relaunch().toggles)
        store.saveLayer(LayerKind.ENGLISH, false)
        store.saveLayer(LayerKind.TRANSLITERATION, true)
        assertEquals(toggles(english = false), relaunch().toggles)
        assertFalse(prefs.getBoolean("reader.showEnglish", true))
    }

    @Test
    fun fontScalePersistsExactly() {
        ReaderPreferenceStore(prefs).saveFontScale(1.3)
        assertEquals(1.3, relaunch().fontScale, 0.0)
    }

    /** A stored value outside the range (another build, a corrupt file) still renders sanely. */
    @Test
    fun outOfRangeStoredScaleIsClamped() {
        prefs.edit().putFloat(ReaderPreferenceStore.KEY_FONT_SCALE, 4f).commit()
        assertEquals(FontScale.MAX, relaunch().fontScale, 0.0)
    }

    @Test
    fun stateWritesThroughOnEveryChange() {
        val state = ReaderPreferencesState(ReaderPreferenceStore(prefs))
        state.setLayer(LayerKind.GURMUKHI, false)
        state.adjustFontScale(FontScale.STEP)
        state.adjustFontScale(FontScale.STEP)

        assertEquals(toggles(gurmukhi = false), state.toggles)
        assertEquals(1.2, state.fontScale, 0.0)
        assertEquals(ReaderPreferences(toggles(gurmukhi = false), 1.2), relaunch())
    }

    @Test
    fun stateStartsFromWhatWasStored() {
        val store = ReaderPreferenceStore(prefs)
        store.saveLayer(LayerKind.ENGLISH, false)
        store.saveFontScale(0.8)
        val state = ReaderPreferencesState(store)
        assertEquals(toggles(english = false), state.toggles)
        assertEquals(0.8, state.fontScale, 0.0)
    }
}
