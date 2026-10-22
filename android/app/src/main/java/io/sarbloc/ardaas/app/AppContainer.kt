package io.sarbloc.ardaas.app

import android.content.Context
import android.util.Log
import io.sarbloc.ardaas.persistence.ArdaasDatabase
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.persistence.SavedArdaasDao
import io.sarbloc.ardaas.translation.ActivityManagerMemoryProbe
import io.sarbloc.ardaas.translation.BentiTranslationService
import io.sarbloc.ardaas.translation.ModelInstaller
import io.sarbloc.ardaas.translation.TranslationEngine
import java.io.FileNotFoundException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * The one place the app's data is opened: the Room database and the bundled
 * content. Lives as long as the process (see [io.sarbloc.ardaas.ArdaasApplication]),
 * so an activity recreated by a configuration change neither reloads the
 * bundle nor opens a second database.
 *
 * Everything here is lazy: nothing is touched until a screen first asks.
 */
class AppContainer(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val database: ArdaasDatabase by lazy { ArdaasDatabase.create(appContext) }

    val savedArdaasDao: SavedArdaasDao get() = database.savedArdaasDao()

    private val translationServiceHolder = lazy {
        BentiTranslationService(
            installer = ModelInstaller.create(appContext),
            engine = TranslationEngine(ActivityManagerMemoryProbe(appContext)),
        )
    }

    /**
     * On-device translation (#119). One per process, built on first touch:
     * an install must survive the screen that started it, and a second
     * instance would race the first over the same model files. Constructing
     * it reads a little disk and downloads nothing.
     */
    val translationService: BentiTranslationService by translationServiceHolder

    /**
     * Memory pressure, from `Application.onTrimMemory`. Reaches the engine
     * only if translation has been used this process — trimming must never
     * be what builds the service.
     */
    fun onTrimMemory(level: Int) {
        if (translationServiceHolder.isInitialized()) translationService.onTrimMemory(level)
    }

    /**
     * The bundled library and occasion list, loaded once on the IO
     * dispatcher. Each failure is logged here and carried to the screens
     * that need the content, which draw it — iOS surfaces a broken bundle the
     * same way, rather than crashing or showing a blank screen.
     */
    val bundledContent: StateFlow<BundledContent> =
        flow {
            val loaded = BundledContent.load { name ->
                try {
                    appContext.assets.open(name)
                } catch (_: FileNotFoundException) {
                    null
                }
            }
            (loaded.library as? LoadState.Failed)?.let {
                Log.e(TAG, "Bundled Ardaas texts failed to load", it.error)
            }
            (loaded.occasionCatalog as? LoadState.Failed)?.let {
                Log.e(TAG, "Bundled occasion list failed to load; the Reader carries on without it", it.error)
            }
            emit(loaded)
        }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, BundledContent.Loading)

    /**
     * Every saved Ardaas, newest first (the DAO's order, matching iOS).
     *
     * A database that cannot open — say, a release missing a migration — is
     * a [LoadState.Failed] for Home to draw, not a crash on launch. Shared
     * while anyone is watching, and kept for a few seconds after, so a
     * configuration change does not flash an empty list.
     */
    val savedArdaas: StateFlow<LoadState<List<SavedArdaas>>> =
        flow { emitAll(savedArdaasDao.observeAll()) }
            .asLoadStates { Log.e(TAG, "Saved Ardaas failed to load", it) }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LoadState.Loading)

    /**
     * Saves [saved] as a new record (its id left 0). Returns false — after
     * logging why — if the database refused, so Compose can stay open with
     * the user's words rather than lose them.
     */
    suspend fun insert(saved: SavedArdaas): Boolean = try {
        savedArdaasDao.insert(saved)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Inserting a new saved Ardaas failed", e)
        false
    }

    /**
     * Saves a copy of [source] (#159) and returns its id, or null — after
     * logging why — if the database refused.
     */
    suspend fun duplicate(source: SavedArdaas): Long? = try {
        savedArdaasDao.insertDuplicate(source, createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Duplicating saved Ardaas ${source.id} failed", e)
        null
    }

    /**
     * Deletes [saved]. Returns false — after logging why — if the database
     * refused, so the caller can put the row back rather than pretend.
     */
    suspend fun delete(saved: SavedArdaas): Boolean = try {
        savedArdaasDao.delete(saved)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Deleting saved Ardaas ${saved.id} failed", e)
        false
    }

    /**
     * Writes [saved] over its stored row. Returns false — after logging why —
     * if the database refused; the Reader keeps showing the stored record,
     * which is still the truth.
     */
    suspend fun update(saved: SavedArdaas): Boolean = try {
        savedArdaasDao.update(saved)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Updating saved Ardaas ${saved.id} failed", e)
        false
    }

    private companion object {
        const val TAG = "Ardaas"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
