package io.sarbloc.ardaas.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import io.sarbloc.ardaas.translation.BentiTranslationService
import io.sarbloc.ardaas.translation.ModelState
import io.sarbloc.ardaas.translation.TranslationError
import io.sarbloc.ardaas.translation.TranslationLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One visit to Compose, as far as translation is concerned: the translation
 * the user asked for, the install they consented to from this screen, and
 * the warm-up. The Android counterpart of what iOS's `ComposeView` keeps in
 * `@State` plus its screen-owned `@StateObject` service.
 *
 * ## Why it is retained, not remembered
 *
 * Its lifetime is the *visit*, not the composition. A configuration change
 * (dark mode, font scale, locale, multi-window) recreates the activity and
 * throws the composition away; anything `remember`ed — a
 * `rememberCoroutineScope` above all — would be cancelled with it, killing a
 * translation mid-decode and losing its result. So the screen holds this
 * through Compose's [retain] (see [rememberComposeTranslationSession]): kept
 * across a configuration change, and *retired* — [end] — only when the
 * screen leaves composition for good (Back, close, Save) or the activity
 * finishes. That makes every exit path cancel, without AppRoot having to
 * enumerate them, and none of the non-exits.
 *
 * Process death needs nothing extra: the install and the translation die
 * with the process, the draft comes back from saved state, and a fresh
 * session starts with nothing in flight — which is the truth.
 *
 * ## Threading
 *
 * Every member is called on [dispatcher]'s thread — the main thread in the
 * app — and its coroutines run there too, so the bookkeeping needs no locks:
 * the service does the blocking work on its own dispatchers.
 */
@Stable
class ComposeTranslationSession(
    private val service: BentiTranslationService,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val log: TranslationLogger = TranslationLogger.Android,
) : RetainObserver {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("ComposeTranslation"))

    /** The model's lifecycle; the screen renders it through [status]. */
    val modelState: StateFlow<ModelState> get() = service.state

    /** Exact bytes over the network, for the consent and the size disclosure. */
    val downloadBytes: Long get() = service.downloadBytes

    /** Free space the install needs at its peak. */
    val peakDiskBytes: Long get() = service.peakDiskBytes

    /** Disk kept once installed. */
    val installedDiskBytes: Long get() = service.installedDiskBytes

    var isTranslating by mutableStateOf(false)
        private set

    /**
     * The user asked to translate but the model had to be installed first;
     * translate for them once it is ready rather than making them tap again.
     */
    var translateWhenReady by mutableStateOf(false)
        private set

    /** A finished translation for the screen to write into its draft — see [CompletedTranslation]. */
    var pendingResult by mutableStateOf<CompletedTranslation?>(null)
        private set

    var failure by mutableStateOf<TranslateFailure?>(null)
        private set

    /**
     * The newest draft the screen has shown, so a translation queued behind
     * an install runs on what is in the editor *when the model is ready*, even
     * if the screen is between compositions at that moment.
     */
    private var latestDraft = BentiDraft()

    /**
     * The install running now is the one this visit started, so leaving
     * cancels it — as iOS's screen-owned service does. Cleared the moment
     * that install ends however it ends, so a later install started elsewhere
     * on the shared service (the debug lab) is never this visit's to cancel.
     */
    private var ownsInstall = false

    /**
     * The translation running (or just finished) was started by the queued
     * request rather than a tap — so "Cancel download" or "Don't wait",
     * tapped on a frame that still shows the install, abandon it too.
     */
    private var isQueuedTranslation = false

    /** Bumped per translation, so a cancelled run's cleanup cannot touch the run that replaced it. */
    private var translationRun = 0

    private var isStarted = false
    private var isEnded = false
    private var translationJob: Job? = null
    private var warmUpJob: Job? = null

    /** A snapshot of everything the screen derives its translation UI from. */
    fun status(model: ModelState): TranslationStatus = TranslationStatus(
        model = model,
        isTranslating = isTranslating,
        translateWhenReady = translateWhenReady,
        pendingResult = pendingResult,
        failure = failure,
    )

    /** [status] for the model state right now — for click handlers, which must not act on a stale frame. */
    fun currentStatus(): TranslationStatus = status(service.state.value)

    /**
     * Begins observing the model. Idempotent. Re-reads disk first (iOS's
     * `onAppear` refresh), so a model installed or removed elsewhere shows as
     * it is.
     */
    fun start() {
        if (isStarted || isEnded) return
        isStarted = true
        service.refresh()
        scope.launch { service.state.collect(::onModelState) }
    }

    /** Called by the screen after every composition with the draft it shows. */
    fun updateDraft(draft: BentiDraft) {
        latestDraft = draft
    }

    /**
     * Translates [draft]'s English. A no-op while one is running, when the
     * text is not translatable, or before the model is ready — the button is
     * disabled then anyway, and a double tap must not start a second run.
     */
    fun translate(draft: BentiDraft) = translate(draft, queued = false)

    private fun translate(draft: BentiDraft, queued: Boolean) {
        latestDraft = draft
        if (isEnded || isTranslating || !draft.isTranslatable || !service.state.value.isReady) return
        val source = draft.translationSource
        if (source.isEmpty()) return
        isTranslating = true
        isQueuedTranslation = queued
        failure = null
        val run = ++translationRun
        translationJob?.cancel()
        translationJob = scope.launch {
            try {
                val gurmukhi = service.translate(source).gurmukhi
                if (gurmukhi.trimmingWhitespaceAndNewlines().isEmpty()) {
                    failure = TranslateFailure.EmptyResult
                } else {
                    pendingResult = CompletedTranslation(gurmukhi = gurmukhi, source = source)
                }
            } catch (e: CancellationException) {
                // Leaving the screen, or the request was abandoned: drop the
                // result nobody is asking for any more.
                throw e
            } catch (e: Exception) {
                log.warn("Translating the benti failed", e)
                failure = TranslateFailure.Failed(TranslationError.wrap(e))
            } finally {
                if (run == translationRun) isTranslating = false
            }
        }
    }

    /** The screen has written [result] into its draft. */
    fun consumeResult(result: CompletedTranslation) {
        if (pendingResult == result) pendingResult = null
    }

    /**
     * The user confirmed the consent: start the install, and translate once
     * it is ready. The only path from Compose that puts bytes on the wire.
     */
    fun download(allowingCellular: Boolean) {
        if (isEnded) return
        failure = null
        translateWhenReady = true
        val wasIdle = !service.state.value.isWorking
        service.download(allowingCellular)
        // Only an install this call actually started is this visit's to
        // cancel — not one already running (from the debug lab, say).
        if (wasIdle && service.state.value.isWorking) ownsInstall = true
        // Already ready (installed elsewhere meanwhile): no state change is
        // coming to trigger the queued translation, so run it now.
        onModelState(service.state.value)
    }

    /** "Cancel download": stops the install and the translation queued behind it. */
    fun cancelDownload() {
        abandonQueuedRequest()
        ownsInstall = false
        service.cancelDownload()
    }

    /**
     * "Don't wait — save without translating": drops the queued translation
     * Save is waiting on. The optimization carries on; it cannot be
     * interrupted.
     */
    fun stopWaiting() {
        abandonQueuedRequest()
    }

    /**
     * Drops the translate-when-ready request — and, if the install finished
     * a frame before the tap landed and the request already fired, the
     * translation it started and any result not yet applied: the user just
     * said not to wait for it, so it must neither hold Save nor land later.
     */
    private fun abandonQueuedRequest() {
        translateWhenReady = false
        if (!isQueuedTranslation) return
        isQueuedTranslation = false
        translationRun++
        translationJob?.cancel()
        translationJob = null
        isTranslating = false
        pendingResult = null
    }

    /** Clears the inline failure, as "Remove Gurmukhi" does on iOS. */
    fun clearFailure() {
        failure = null
    }

    /**
     * Preloads the translator if the model is ready (see
     * [BentiTranslationService.warmUp]). Called when the screen starts —
     * including on return from the background, where memory pressure may
     * have released it — and whenever the model becomes ready.
     */
    fun warmUp() {
        if (isEnded || warmUpJob?.isActive == true || !service.state.value.isReady) return
        warmUpJob = scope.launch { service.warmUp() }
    }

    /**
     * The visit is over. Cancels the translation in flight (the engine stops
     * between decode steps) and the install this visit started — whatever
     * finished is kept and resumes next time, as the consent copy promises.
     * Idempotent.
     */
    fun end() {
        if (isEnded) return
        isEnded = true
        translateWhenReady = false
        if (ownsInstall) service.cancelDownload()
        scope.cancel()
    }

    // RetainObserver: the retained lifetime described above.

    override fun onRetained() = start()

    override fun onEnteredComposition() = start()

    override fun onExitedComposition() = Unit

    override fun onRetired() = end()

    override fun onUnused() = end()

    /**
     * iOS's `onChange(of: translation.state)`: the install the user consented
     * to has finished, so run the translation they already asked for. The
     * request is consumed either way — if the text has since become
     * untranslatable [translate] no-ops and the button is right there.
     * Holding it open instead would mean firing inference on a half-typed
     * sentence later on, which is worse than one extra tap.
     *
     * An install that ends any other way (failed, cancelled elsewhere) drops
     * the request too: nothing is coming, so nothing should fire later.
     */
    private fun onModelState(state: ModelState) {
        // Whatever install this visit owned is over. (State conflation could
        // only hide an end followed at once by a new install from another
        // thread; no UI path does that.)
        if (!state.isWorking) ownsInstall = false
        when {
            state.isReady && translateWhenReady -> {
                translateWhenReady = false
                translate(latestDraft, queued = true)
            }
            state.isReady -> warmUp()
            !state.isWorking -> translateWhenReady = false
        }
    }
}

/**
 * The [ComposeTranslationSession] for this visit to Compose — retained, so a
 * configuration change keeps it (and whatever it is running), and ended when
 * the screen leaves composition for good. Call it where the Compose route is
 * composed, once per visit.
 *
 * @param service a provider, so the service is built only when Compose shows.
 */
@Composable
fun rememberComposeTranslationSession(service: () -> BentiTranslationService): ComposeTranslationSession =
    retain { ComposeTranslationSession(service()) }
