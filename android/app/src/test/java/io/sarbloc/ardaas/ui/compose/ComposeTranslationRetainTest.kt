package io.sarbloc.ardaas.ui.compose

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import io.sarbloc.ardaas.translation.BentiTranslationService
import io.sarbloc.ardaas.translation.FakeGraphOptimizer
import io.sarbloc.ardaas.translation.FakeModelFileFetcher
import io.sarbloc.ardaas.translation.FakeTranslator
import io.sarbloc.ardaas.translation.ModelInstaller
import io.sarbloc.ardaas.translation.ModelState
import io.sarbloc.ardaas.translation.RecordingLogger
import io.sarbloc.ardaas.translation.TranslationEngine
import io.sarbloc.ardaas.translation.TranslationFixtures
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The lifecycle choice itself, on a real activity: a configuration change
 * keeps the retained session — and the install it started — while leaving
 * composition for good ends it and cancels that install.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeTranslationRetainTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var service: BentiTranslationService

    @Before
    fun setUp() {
        val model = TranslationFixtures.syntheticModel()
        // Long enough that the install is still running when the test looks.
        val fetcher = FakeModelFileFetcher(model.payloads).apply { delayMillis = 30_000 }
        val installer = ModelInstaller(
            catalog = model.catalog,
            directory = File(temp.root, "BentiModel"),
            fetcher = fetcher,
            optimizer = FakeGraphOptimizer(),
            availableDiskBytes = { 10_000_000 },
            log = RecordingLogger(),
        )
        val engine = TranslationEngine(memoryProbe = { null }, makeTranslator = { _, _, _ -> FakeTranslator() })
        service = BentiTranslationService(installer, engine, log = RecordingLogger())
        HostActivity.service = service
        HostActivity.showsCompose.value = true
        HostActivity.seen.clear()
    }

    @After
    fun tearDown() {
        service.close()
    }

    private fun frames() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test
    fun aConfigurationChangeKeepsTheSessionAndLeavingEndsIt() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        frames()
        val session = HostActivity.seen.last()

        session.download(allowingCellular = false)
        assertTrue(service.state.value is ModelState.Downloading)

        controller.recreate()
        frames()
        assertSame("the recreated screen gets the same session", session, HostActivity.seen.last())
        assertTrue("the install survives the configuration change", service.state.value is ModelState.Downloading)

        HostActivity.showsCompose.value = false
        frames()
        assertEquals("leaving Compose cancels the install it started", ModelState.NotDownloaded, service.state.value)

        controller.pause().stop().destroy()
    }

    /** Stands in for AppRoot's Compose route: the session is retained exactly as there. */
    class HostActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
                if (showsCompose.value) {
                    val session = rememberComposeTranslationSession { service }
                    SideEffect { seen += session }
                }
            }
        }

        companion object {
            lateinit var service: BentiTranslationService
            val showsCompose = mutableStateOf(true)
            val seen = mutableListOf<ComposeTranslationSession>()
        }
    }
}
