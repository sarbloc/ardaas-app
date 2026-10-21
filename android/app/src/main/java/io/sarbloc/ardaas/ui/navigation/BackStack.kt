package io.sarbloc.ardaas.ui.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver

/**
 * Every screen the app can show. iOS hosts Home in a `NavigationStack` and
 * pushes the Reader; Compose and About & Sources are sheets there. Android
 * has no sheet-shaped equivalent worth the weight, so every one is a full
 * screen on one back stack, each with an Up arrow and system Back.
 */
sealed interface Destination {
    /** Stable string form: the saved-state encoding and the per-screen state key. */
    val key: String

    data object Home : Destination {
        override val key = "home"
    }

    data object Sources : Destination {
        override val key = "sources"
    }

    /** Open-source licences (#132), pushed from About & Sources. */
    data object Licences : Destination {
        override val key = "licences"
    }

    data object Compose : Destination {
        override val key = "compose"
    }

    /**
     * Debug-only on-device translation diagnostics (#119). Only the route
     * lives in main; what it shows comes from the build type's
     * [io.sarbloc.ardaas.ui.diagnostics.DiagnosticsHook], which in release
     * shows nothing and leaves.
     */
    data object Diagnostics : Destination {
        override val key = "diagnostics"
    }

    /**
     * Reading one saved Ardaas. Carries only the record's id, never the
     * record: the screen observes the live row, so an edit made while
     * reading (variant, occasion) is what it draws, and a restored back
     * stack cannot resurrect a stale copy.
     */
    data class Reader(val savedArdaasId: Long) : Destination {
        override val key = "$READER_PREFIX$savedArdaasId"
    }

    /**
     * Compose reopened on one saved Ardaas (#154), pushed from its Reader.
     * Like [Reader], it carries only the id: the screen seeds its form from
     * the live row, and the form itself survives a restore through its own
     * saved state.
     */
    data class Edit(val savedArdaasId: Long) : Destination {
        override val key = "$EDIT_PREFIX$savedArdaasId"
    }

    companion object {
        private const val READER_PREFIX = "reader:"
        private const val EDIT_PREFIX = "edit:"

        /** Inverse of [key]; null for anything unrecognised. */
        fun fromKey(key: String): Destination? = when (key) {
            Home.key -> Home
            Sources.key -> Sources
            Licences.key -> Licences
            Compose.key -> Compose
            Diagnostics.key -> Diagnostics
            else -> idAfter(READER_PREFIX, key)?.let(::Reader) ?: idAfter(EDIT_PREFIX, key)?.let(::Edit)
        }

        private fun idAfter(prefix: String, key: String): Long? =
            key.removePrefix(prefix).takeIf { key.startsWith(prefix) }?.toLongOrNull()
    }
}

/**
 * The app's navigation: a stack of [Destination]s, always rooted at
 * [Destination.Home]. Snapshot state, so composition follows [current].
 *
 * Deliberately hand-rolled rather than navigation-compose: a handful of screens and
 * one id argument do not justify a new dependency.
 */
class BackStack(entries: List<Destination> = listOf(Destination.Home)) {

    private val stack = mutableStateListOf<Destination>().apply {
        add(Destination.Home)
        addAll(entries.filter { it != Destination.Home })
    }

    /** The screen on top — the one showing. */
    val current: Destination get() = stack.last()

    /** False at Home, where Back leaves the app. */
    val canGoBack: Boolean get() = stack.size > 1

    /** A snapshot of the stack, bottom first. */
    val entries: List<Destination> get() = stack.toList()

    /**
     * Shows [destination]. Ignored when it is already on top, so a double tap
     * on a row or button cannot stack the same screen twice.
     */
    fun push(destination: Destination) {
        if (destination == current) return
        if (destination == Destination.Home) {
            popToHome()
            return
        }
        stack.add(destination)
    }

    /** Leaves the top screen. Returns it, or null at Home (nothing popped). */
    fun pop(): Destination? = if (canGoBack) stack.removeAt(stack.lastIndex) else null

    /** Pops everything above Home. Returns what was popped, top first. */
    fun popToHome(): List<Destination> {
        val popped = mutableListOf<Destination>()
        while (canGoBack) popped += stack.removeAt(stack.lastIndex)
        return popped
    }

    companion object {
        /**
         * Survives process death and configuration changes via
         * `rememberSaveable`. Unrecognised entries are dropped on restore and
         * Home is always the root, so a corrupt bundle degrades to Home.
         */
        val Saver: Saver<BackStack, ArrayList<String>> = Saver(
            save = { backStack -> ArrayList(backStack.stack.map { it.key }) },
            restore = { keys -> BackStack(keys.mapNotNull(Destination::fromKey)) },
        )
    }
}
