package com.sendspindroid.ui.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the currentDetail derivation pattern used in MainActivityViewModel (M-23).
 *
 * The ViewModel derives currentDetail from the detailBackStack via:
 *   _detailBackStack.map { it.lastOrNull() }
 *       .stateIn(viewModelScope, SharingStarted.Eagerly, ...)
 *
 * These tests verify that this derivation correctly reflects push, pop,
 * and clear operations on the back stack -- the exact operations exposed
 * by navigateToDetail(), navigateDetailBack(), and clearDetailNavigation().
 *
 * Uses [backgroundScope] for the stateIn collector so that the eagerly-started
 * coroutine is automatically cancelled when each test finishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentDetailDerivationTest {

    /** Helper: creates the same derived flow pattern used in MainActivityViewModel. */
    private fun CoroutineScope.derivedCurrentDetail(
        backStack: MutableStateFlow<List<DetailDestination>>
    ): StateFlow<DetailDestination?> =
        backStack
            .map { it.lastOrNull() }
            .stateIn(this, SharingStarted.Eagerly, backStack.value.lastOrNull())

    // -- Initial state --

    @Test
    fun emptyStack_yieldsNull() = runTest(UnconfinedTestDispatcher()) {
        val backStack = MutableStateFlow<List<DetailDestination>>(emptyList())
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        assertNull("Empty stack should yield null", currentDetail.value)
    }

    @Test
    fun nonEmptyInitialStack_yieldsLastEntry() = runTest(UnconfinedTestDispatcher()) {
        val album = DetailDestination.Album("1", "Test Album")
        val backStack = MutableStateFlow<List<DetailDestination>>(listOf(album))
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        assertEquals("Should yield the single entry", album, currentDetail.value)
    }

    // -- Push (navigateToDetail) --

    @Test
    fun push_singleEntry_becomesCurrentDetail() = runTest(UnconfinedTestDispatcher()) {
        val backStack = MutableStateFlow<List<DetailDestination>>(emptyList())
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        val artist = DetailDestination.Artist("a1", "Test Artist")
        backStack.value = backStack.value + artist

        assertEquals("Pushed entry should be current", artist, currentDetail.value)
    }

    @Test
    fun push_nestedNavigation_yieldsNewTop() = runTest(UnconfinedTestDispatcher()) {
        val backStack = MutableStateFlow<List<DetailDestination>>(emptyList())
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        val artist = DetailDestination.Artist("a1", "Artist")
        val album = DetailDestination.Album("b1", "Album")

        // Push Artist
        backStack.value = backStack.value + artist
        assertEquals("First push: Artist", artist, currentDetail.value)

        // Push Album on top of Artist (nested: Artist -> Album)
        backStack.value = backStack.value + album
        assertEquals("Second push: Album on top", album, currentDetail.value)
    }

    // -- Pop (navigateDetailBack) --

    @Test
    fun pop_fromSingleEntry_yieldsNull() = runTest(UnconfinedTestDispatcher()) {
        val album = DetailDestination.Album("1", "Album")
        val backStack = MutableStateFlow<List<DetailDestination>>(listOf(album))
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        assertEquals(album, currentDetail.value)

        // Pop
        backStack.value = backStack.value.dropLast(1)

        assertNull("Stack empty after pop -> null", currentDetail.value)
    }

    @Test
    fun pop_fromNestedStack_revealsPreviousEntry() = runTest(UnconfinedTestDispatcher()) {
        val artist = DetailDestination.Artist("a1", "Artist")
        val album = DetailDestination.Album("b1", "Album")
        val backStack = MutableStateFlow(listOf(artist, album))
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        assertEquals("Top is Album", album, currentDetail.value)

        // Pop Album, revealing Artist
        backStack.value = backStack.value.dropLast(1)
        assertEquals("After pop: Artist revealed", artist, currentDetail.value)
    }

    // -- Clear (clearDetailNavigation) --

    @Test
    fun clear_fromDeepStack_yieldsNull() = runTest(UnconfinedTestDispatcher()) {
        val backStack = MutableStateFlow(
            listOf(
                DetailDestination.Artist("a1", "Artist"),
                DetailDestination.Album("b1", "Album"),
                DetailDestination.Playlist("p1", "Playlist")
            )
        )
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        assertEquals(
            "Pre-clear: Playlist on top",
            DetailDestination.Playlist("p1", "Playlist"),
            currentDetail.value
        )

        // Clear entire stack
        backStack.value = emptyList()

        assertNull("After clear: null", currentDetail.value)
    }

    // -- Rapid updates --

    @Test
    fun rapidPushPop_alwaysConsistent() = runTest(UnconfinedTestDispatcher()) {
        val backStack = MutableStateFlow<List<DetailDestination>>(emptyList())
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        // Rapid sequence: push 3, pop 2, push 1
        val d1 = DetailDestination.Album("1", "A")
        val d2 = DetailDestination.Artist("2", "B")
        val d3 = DetailDestination.Playlist("3", "C")
        val d4 = DetailDestination.Album("4", "D")

        backStack.value = listOf(d1)
        backStack.value = listOf(d1, d2)
        backStack.value = listOf(d1, d2, d3)
        backStack.value = listOf(d1)               // pop 2
        backStack.value = listOf(d1, d4)            // push 1

        assertEquals(
            "After rapid updates, should show last entry of final stack",
            d4,
            currentDetail.value
        )
    }

    // -- Full lifecycle --

    @Test
    fun fullLifecycle_pushPopClearPush() = runTest(UnconfinedTestDispatcher()) {
        val backStack = MutableStateFlow<List<DetailDestination>>(emptyList())
        val currentDetail = backgroundScope.derivedCurrentDetail(backStack)

        // Start empty
        assertNull("Start: null", currentDetail.value)

        // Push Artist
        val artist = DetailDestination.Artist("a1", "Artist")
        backStack.value = listOf(artist)
        assertEquals("Push Artist", artist, currentDetail.value)

        // Push Album on top
        val album = DetailDestination.Album("b1", "Album")
        backStack.value = listOf(artist, album)
        assertEquals("Push Album", album, currentDetail.value)

        // Pop Album
        backStack.value = listOf(artist)
        assertEquals("Pop -> Artist", artist, currentDetail.value)

        // Clear all (disconnect scenario)
        backStack.value = emptyList()
        assertNull("Clear -> null", currentDetail.value)

        // Push Playlist (reconnect, new browsing session)
        val playlist = DetailDestination.Playlist("p1", "My Playlist")
        backStack.value = listOf(playlist)
        assertEquals("New session: Playlist", playlist, currentDetail.value)
    }
}
