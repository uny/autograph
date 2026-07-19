package dev.ynagai.autograph.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class ScreenHistoryTest {

    @Test
    fun startsEmpty() {
        assertNull(ScreenHistory().lastScreen, "nothing has been viewed yet")
    }

    @Test
    fun recordsTheMostRecentScreen() {
        val history = ScreenHistory()
        history.record("Home")
        assertEquals("Home", history.lastScreen)
        history.record("Detail")
        assertEquals("Detail", history.lastScreen, "the newest recording wins")
    }

    @Test
    fun recordReturnsTheScreenItReplaces() {
        // The returned value IS the previous_screen of the view being recorded, which is why record
        // returns it rather than leaving each call site to read lastScreen in the right order.
        val history = ScreenHistory()
        assertNull(history.record("Home"), "the first screen has no previous")
        assertEquals("Home", history.record("Detail"))
        assertEquals("Detail", history.record("Cart"))
    }

    @Test
    fun survivesTheScreenItNames() {
        // History is deliberately not current state: `previous_screen` needs the name of a screen the
        // user has already left, so nothing about leaving a screen clears this.
        val stack = ScopeStack()
        val handle = stack.push(screen = "Home")
        stack.screenHistory.record("Home")
        stack.remove(handle)

        assertNull(stack.current().screen, "the frame is gone, so nothing is on screen")
        assertEquals("Home", stack.screenHistory.lastScreen, "but the history remembers it")
    }

    @Test
    fun oneStackCarriesOneHistorySoSharingTheStackSharesTheHistory() {
        // The reason this lives on ScopeStack: a hybrid app hands ONE stack to AutographProvider and
        // to the native pipeline, and that alone has to be enough to keep previous_screen continuous
        // across a Compose<->native transition. Two readers of the same stack must see one history.
        val shared = ScopeStack()
        val asComposeSeesIt = shared.screenHistory
        val asNativeSeesIt = shared.screenHistory

        assertSame(asComposeSeesIt, asNativeSeesIt, "one stack must expose one history")

        // A screen recorded through one pipeline's view of it is visible to the other's.
        asNativeSeesIt.record("NativeSettings")
        assertEquals("NativeSettings", asComposeSeesIt.lastScreen)
    }

    @Test
    fun separateStacksDoNotShareHistory() {
        // The flip side, and the property AutographProvider relies on to reset previous_screen when
        // the tracker is replaced: a fresh stack is a fresh history.
        val first = ScopeStack()
        val second = ScopeStack()
        first.screenHistory.record("Home")

        assertNotSame(first.screenHistory, second.screenHistory)
        assertNull(second.screenHistory.lastScreen, "history must not leak between stacks")
    }
}
