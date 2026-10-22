package io.sarbloc.ardaas.ui.reader

import android.view.View
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeepScreenOnHoldsTest {

    @Test
    fun oneReaderHoldsTheScreenOnWhileShown() {
        val view = View(RuntimeEnvironment.getApplication())
        KeepScreenOnHolds.acquire(view)
        assertTrue(view.keepScreenOn)
        KeepScreenOnHolds.release(view)
        assertFalse(view.keepScreenOn)
    }

    /** #159: the incoming copy's Reader acquires before the outgoing original releases. */
    @Test
    fun anOverlappingReaderKeepsItOnAfterTheOtherLeaves() {
        val view = View(RuntimeEnvironment.getApplication())
        KeepScreenOnHolds.acquire(view)
        KeepScreenOnHolds.acquire(view)
        KeepScreenOnHolds.release(view)
        assertTrue(view.keepScreenOn)
        KeepScreenOnHolds.release(view)
        assertFalse(view.keepScreenOn)
    }
}
