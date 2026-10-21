package io.sarbloc.ardaas.ui.compose

import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.translation.ModelState
import io.sarbloc.ardaas.translation.TranslationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translate half of iOS ComposeView's rules, as data: what the section
 * shows for each model state, the button, when Save waits, and when the
 * section and the draft show at all.
 */
class TranslationStatusTest {
    private val english = BentiDraft(typed = "Bless our family")
    private val gurmukhiTyped = BentiDraft(typed = "ਸਰਬੱਤ ਦਾ ਭਲਾ")
    private val translated = english.applyTranslation("ਸਾਡੇ ਪਰਿਵਾਰ ਨੂੰ ਬਖ਼ਸ਼ੋ", "Bless our family")
    private val failure = TranslationError.Download("timed out")

    private fun saveable(benti: BentiDraft) = ComposeDraft(label = "Family", benti = benti)

    // The section, by model state

    @Test
    fun notDownloadedOffersTranslateThroughConsentWithTheSizeDisclosure() {
        val section = TranslationStatus(ModelState.NotDownloaded).section(english)
        assertEquals(
            TranslateSection.Offer(
                TranslateButton(TranslateButton.Label.TRANSLATE, isEnabled = true, opensConsent = true),
                needsDownload = true,
            ),
            section,
        )
    }

    @Test
    fun readyTranslatesDirectlyWithTheOnDeviceNote() {
        val section = TranslationStatus(ModelState.Ready).section(english)
        assertEquals(
            TranslateSection.Offer(
                TranslateButton(TranslateButton.Label.TRANSLATE, isEnabled = true, opensConsent = false),
                needsDownload = false,
            ),
            section,
        )
    }

    @Test
    fun downloadingTruncatesThePercentAndPicksTheCopyByTheQueuedRequest() {
        val idle = TranslationStatus(ModelState.Downloading(0.999)).section(english)
        assertEquals(TranslateSection.Downloading(progress = 0.999f, percent = 99, isAwaitingTranslation = false), idle)

        val awaiting = TranslationStatus(ModelState.Downloading(0.5), translateWhenReady = true).section(english)
        assertEquals(TranslateSection.Downloading(progress = 0.5f, percent = 50, isAwaitingTranslation = true), awaiting)
    }

    @Test
    fun downloadingClampsAnOutOfRangeProgress() {
        val section = TranslationStatus(ModelState.Downloading(1.5)).section(english)
        assertEquals(TranslateSection.Downloading(progress = 1f, percent = 100, isAwaitingTranslation = false), section)
    }

    @Test
    fun optimizingOffersStopWaitingOnlyWhenATranslationIsQueued() {
        assertEquals(
            TranslateSection.Preparing(offersStopWaiting = false),
            TranslationStatus(ModelState.Optimizing).section(english),
        )
        assertEquals(
            TranslateSection.Preparing(offersStopWaiting = true),
            TranslationStatus(ModelState.Optimizing, translateWhenReady = true).section(english),
        )
    }

    @Test
    fun aFailedInstallCarriesItsError() {
        assertEquals(TranslateSection.Failed(failure), TranslationStatus(ModelState.Failed(failure)).section(english))
    }

    // The button

    @Test
    fun theButtonSaysTranslateAgainOnceADraftExists() {
        val offer = TranslationStatus(ModelState.Ready).section(translated) as TranslateSection.Offer
        assertEquals(TranslateButton.Label.TRANSLATE_AGAIN, offer.button.label)
        assertTrue(offer.button.isEnabled)
    }

    @Test
    fun theButtonIsDisabledAndSaysTranslatingWhileOneRuns() {
        val offer = TranslationStatus(ModelState.Ready, isTranslating = true).section(translated) as TranslateSection.Offer
        assertEquals(TranslateButton.Label.TRANSLATING, offer.button.label)
        assertFalse("a double tap must not start a second run", offer.button.isEnabled)
    }

    @Test
    fun theButtonIsDisabledForAnEmptyEditor() {
        for (model in listOf(ModelState.NotDownloaded, ModelState.Ready)) {
            val offer = TranslationStatus(model).section(BentiDraft()) as TranslateSection.Offer
            assertFalse(offer.button.isEnabled)
        }
    }

    // Save waits on a translation the user asked for

    @Test
    fun nothingInFlightLetsAFilledDraftSave() {
        assertFalse(TranslationStatus(ModelState.Ready).isAwaitingTranslation)
        assertTrue(TranslationStatus(ModelState.Ready).canSave(saveable(english)))
    }

    @Test
    fun saveWaitsWhileTranslating() {
        val status = TranslationStatus(ModelState.Ready, isTranslating = true)
        assertTrue(status.isAwaitingTranslation)
        assertFalse(status.canSave(saveable(english)))
    }

    @Test
    fun saveWaitsForAFinishedResultNotYetInTheDraft() {
        val status = TranslationStatus(ModelState.Ready, pendingResult = CompletedTranslation("ਅਰਦਾਸ", "Bless"))
        assertTrue(status.isAwaitingTranslation)
        assertFalse(status.canSave(saveable(english)))
    }

    @Test
    fun saveWaitsForATranslationQueuedBehindTheInstall() {
        for (model in listOf(ModelState.Downloading(0.2), ModelState.Optimizing, ModelState.Ready)) {
            val status = TranslationStatus(model, translateWhenReady = true)
            assertTrue("$model", status.isAwaitingTranslation)
            assertFalse("$model", status.canSave(saveable(english)))
        }
    }

    @Test
    fun aQueuedRequestStopsBlockingOnceTheInstallHasEnded() {
        for (model in listOf(ModelState.NotDownloaded, ModelState.Failed(failure))) {
            assertFalse("$model", TranslationStatus(model, translateWhenReady = true).isAwaitingTranslation)
        }
    }

    @Test
    fun anInstallNobodyIsWaitingOnDoesNotBlockSave() {
        val status = TranslationStatus(ModelState.Downloading(0.4))
        assertFalse(status.isAwaitingTranslation)
        assertTrue(status.canSave(saveable(english)))
    }

    @Test
    fun theDraftsOwnRuleStillApplies() {
        assertFalse(TranslationStatus(ModelState.Ready).canSave(ComposeDraft(label = " ", benti = english)))
        assertFalse(TranslationStatus(ModelState.Ready).canSave(ComposeDraft(label = "Family")))
    }

    // Whether the section and the draft show

    @Test
    fun theSectionShowsForEnglishAndForAnEmptyEditor() {
        val status = TranslationStatus(ModelState.NotDownloaded)
        assertTrue(status.showsSection(english))
        assertTrue("discoverable before anything is typed", status.showsSection(BentiDraft()))
    }

    @Test
    fun theSectionHidesForGurmukhiInputUnlessAnInstallIsRunning() {
        assertFalse(TranslationStatus(ModelState.NotDownloaded).showsSection(gurmukhiTyped))
        assertFalse(TranslationStatus(ModelState.Ready).showsSection(gurmukhiTyped))
        assertFalse(TranslationStatus(ModelState.Failed(failure)).showsSection(gurmukhiTyped))
        assertTrue(TranslationStatus(ModelState.Downloading(0.1)).showsSection(gurmukhiTyped))
        assertTrue(TranslationStatus(ModelState.Optimizing).showsSection(gurmukhiTyped))
    }

    @Test
    fun theDraftShowsOnlyOnceATranslationExistsForEnglishInput() {
        val status = TranslationStatus(ModelState.Ready)
        assertFalse(status.showsDraft(english))
        assertTrue(status.showsDraft(translated))

        // The same draft, the benti since rewritten in Gurmukhi: ignored, so hidden.
        assertFalse(status.showsDraft(translated.copy(typed = "ਸਰਬੱਤ ਦਾ ਭਲਾ")))
    }

    // Purity caution

    @Test
    fun purityCautionFlagsNonGurmukhiInTheDraft() {
        assertFalse(TranslationStatus.showsPurityCaution(translated))
        assertTrue(TranslationStatus.showsPurityCaution(translated.copy(gurmukhi = "ਅਰਦਾਸ हम")))
        assertTrue("digits trip it too", TranslationStatus.showsPurityCaution(translated.copy(gurmukhi = "ਅਰਦਾਸ 2026")))
    }

    @Test
    fun purityCautionNeedsADraft() {
        assertFalse(TranslationStatus.showsPurityCaution(english))
        assertFalse("typed Gurmukhi is not a draft", TranslationStatus.showsPurityCaution(BentiDraft(typed = "ਅਰਦਾਸ", gurmukhi = "abc")))
    }

    // Copy

    @Test
    fun errorsReachableFromComposeHaveFriendlyWording() {
        assertEquals(R.string.translation_error_cellular, translationErrorMessage(TranslationError.CellularNotAllowed))
        assertEquals(R.string.translation_error_disk, translationErrorMessage(TranslationError.InsufficientDisk(1, 2)))
        assertEquals(R.string.translation_error_memory, translationErrorMessage(TranslationError.InsufficientMemory(1, 2)))
        assertEquals(R.string.translation_error_too_long, translationErrorMessage(TranslationError.InputTooLong(300, 256)))
        assertEquals(R.string.translation_error_cancelled, translationErrorMessage(TranslationError.Cancelled))
        assertEquals(R.string.translation_error_not_ready, translationErrorMessage(TranslationError.EngineUnavailable))
        assertEquals(R.string.translation_error_not_ready, translationErrorMessage(TranslationError.NotReady))
    }

    @Test
    fun otherErrorsFallBackToTheirOwnText() {
        assertNull(translationErrorMessage(failure))
        assertNull(translationErrorMessage(TranslationError.SessionFailure("boom")))
    }
}
