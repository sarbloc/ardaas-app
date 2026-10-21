package io.sarbloc.ardaas.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackTest {

    @Test
    fun startsAtHomeWithNothingToGoBackTo() {
        val stack = BackStack()
        assertEquals(Destination.Home, stack.current)
        assertFalse(stack.canGoBack)
        assertNull(stack.pop())
        assertEquals(Destination.Home, stack.current)
    }

    @Test
    fun pushThenPopReturnsToTheScreenBelow() {
        val stack = BackStack()
        stack.push(Destination.Reader(7))
        assertEquals(Destination.Reader(7), stack.current)
        assertTrue(stack.canGoBack)

        assertEquals(Destination.Reader(7), stack.pop())
        assertEquals(Destination.Home, stack.current)
    }

    @Test
    fun pushingTheScreenAlreadyOnTopIsIgnored() {
        val stack = BackStack()
        stack.push(Destination.Sources)
        stack.push(Destination.Sources)
        assertEquals(listOf(Destination.Home, Destination.Sources), stack.entries)
    }

    @Test
    fun aDifferentReaderIsADifferentScreen() {
        val stack = BackStack()
        stack.push(Destination.Reader(1))
        stack.push(Destination.Reader(2))
        assertEquals(3, stack.entries.size)
    }

    @Test
    fun pushingHomePopsBackToIt() {
        val stack = BackStack()
        stack.push(Destination.Sources)
        stack.push(Destination.Compose)
        stack.push(Destination.Home)
        assertEquals(listOf(Destination.Home), stack.entries)
    }

    @Test
    fun popToHomeReturnsWhatWasPoppedTopFirst() {
        val stack = BackStack()
        stack.push(Destination.Reader(3))
        stack.push(Destination.Sources)
        assertEquals(listOf(Destination.Sources, Destination.Reader(3)), stack.popToHome())
        assertEquals(listOf(Destination.Home), stack.entries)
    }

    @Test
    fun homeIsAlwaysTheRootAndNeverRepeated() {
        val stack = BackStack(listOf(Destination.Sources, Destination.Home, Destination.Reader(4)))
        assertEquals(listOf(Destination.Home, Destination.Sources, Destination.Reader(4)), stack.entries)
    }

    @Test
    fun everyDestinationRoundTripsThroughItsKey() {
        val all = listOf(Destination.Home, Destination.Sources, Destination.Licences, Destination.Compose, Destination.Diagnostics, Destination.Reader(42), Destination.Edit(42))
        for (destination in all) {
            assertEquals(destination, Destination.fromKey(destination.key))
        }
    }

    @Test
    fun unrecognisedKeysDecodeToNothing() {
        for (key in listOf("", "settings", "reader:", "reader:abc", "xreader:1", "edit:", "edit:abc", "xedit:1")) {
            assertNull(key, Destination.fromKey(key))
        }
    }

    @Test
    fun saverRestoresTheStackAndDropsWhatItCannotRead() {
        val stack = BackStack()
        stack.push(Destination.Reader(9))
        stack.push(Destination.Edit(9))

        val saved = with(BackStack.Saver) { scope.save(stack) }!!
        assertEquals(listOf("home", "reader:9", "edit:9"), saved)

        val restored = BackStack.Saver.restore(arrayListOf("home", "gone", "reader:9", "edit:9"))!!
        assertEquals(stack.entries, restored.entries)
    }

    private val scope = SaverScope { true }
}
