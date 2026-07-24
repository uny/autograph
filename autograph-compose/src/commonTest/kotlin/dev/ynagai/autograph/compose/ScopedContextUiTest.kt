package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScopeUiRecordingTracker : Tracker {
    val tracks = mutableListOf<Pair<String, JsonObject>>()
    val screens = mutableListOf<Pair<String, JsonObject>>()
    override fun track(name: String, properties: JsonObject, target: String?) {
        tracks += name to properties
    }
    override fun screen(name: String, properties: JsonObject) {
        screens += name to properties
    }
    override fun identify(userId: String, traits: JsonObject) {}
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

@OptIn(ExperimentalTestApi::class)
class ScopedContextUiTest {

    @Test
    fun scopeAppliesToTracksNestedInTheContent() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                AutographScope("article_id" to "42") {
                    Emit { it.track("Recipe Saved", target = "share_button") }
                }
            }
        }
        waitForIdle()

        val props = tracker.tracks.single().second
        assertEquals("42", props.str("article_id"))
    }

    @Test
    fun tracksOutsideTheScopeAreUnaffected() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                AutographScope("article_id" to "42") {
                    Emit { it.track("Inside") }
                }
                Emit { it.track("Outside") }
            }
        }
        waitForIdle()

        val inside = tracker.tracks.first { it.first == "Inside" }.second
        val outside = tracker.tracks.first { it.first == "Outside" }.second
        assertEquals("42", inside.str("article_id"))
        assertNull(outside.str("article_id"), "a sibling outside the scope carries no scope context")
    }

    @Test
    fun nestedScopesStackAndInnerWins() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                AutographScope("tenant" to "acme", "article_id" to "1") {
                    AutographScope("article_id" to "2", "section" to "body") {
                        Emit { it.track("E") }
                    }
                }
            }
        }
        waitForIdle()

        val props = tracker.tracks.single().second
        assertEquals("acme", props.str("tenant"))
        assertEquals("2", props.str("article_id"), "inner scope overrides the outer one")
        assertEquals("body", props.str("section"))
    }

    @Test
    fun composesWithTrackedScreenSoEventsCarryBothScreenAndScope() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(
                LocalTracker provides tracker,
                // A fresh stack per test, for its screen history: the fallback stack is a shared
                // global, so leaving it unprovided would let previous_screen leak between tests.
                LocalScopeStack provides ScopeStack(),
            ) {
                AutographScope("article_id" to "42") {
                    TrackedScreen("ArticleDetail") {
                        Emit { it.track("Recipe Saved") }
                    }
                }
            }
        }
        waitForIdle()

        // The screen-view event carries the scope.
        val screenProps = tracker.screens.single().second
        assertEquals("42", screenProps.str("article_id"))

        // A track nested under both carries screen (from TrackedScreen) and article_id (from scope).
        val trackProps = tracker.tracks.single().second
        assertEquals("42", trackProps.str("article_id"))
    }

    @Test
    fun scopedTrackerInstanceStaysStableAcrossRecomposition() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        val seen = mutableListOf<Tracker>()
        val tick = mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                // Reading `tick` in this scope forces AutographScope to re-execute on each tick,
                // rebuilding a fresh (but structurally-equal) scope via the vararg overload.
                if (tick.value >= 0) {
                    AutographScope("article_id" to "42") {
                        val current = LocalTracker.current
                        SideEffect { seen += current }
                    }
                }
            }
        }
        waitForIdle()
        tick.value = 1
        waitForIdle()
        tick.value = 2
        waitForIdle()

        // AutographScope keys `remember` on (parent, properties) using JsonObject's structural
        // equality, so a rebuilt-but-equal scope returns the SAME ScopedTracker instance across
        // recompositions — otherwise tracker-keyed DisposableEffect/LaunchedEffect in nested
        // screens would restart every frame (and re-fire screen views).
        assertTrue(seen.isNotEmpty(), "the scoped content must have composed at least once")
        assertEquals(
            1,
            seen.distinct().size,
            "the ScopedTracker instance must survive recomposition",
        )
    }

    @Test
    fun nestedScopesFlattenToASingleHopOverTheRootTracker() = runComposeUiTest {
        val root = ScopeUiRecordingTracker()
        var captured: Tracker? = null
        setContent {
            CompositionLocalProvider(LocalTracker provides root) {
                AutographScope("a" to "1") {
                    AutographScope("b" to "2") {
                        val current = LocalTracker.current
                        SideEffect { captured = current }
                    }
                }
            }
        }
        waitForIdle()

        // Flattening keeps the decorator a single hop over the ORIGINAL tracker no matter how deeply
        // scopes nest (constant wrapper depth): the inner scope's delegate is the root tracker, not
        // another ScopedTracker. A stacked chain would fail this assertion.
        val scoped = captured as ScopedTracker
        assertTrue(scoped.delegate === root, "nested scopes must flatten to one hop over the root tracker")
        assertEquals("1", scoped.scope.str("a"))
        assertEquals("2", scoped.scope.str("b"))
    }

    @Test
    fun changingTheScopeValueProducesANewDecoratorCarryingTheNewValue() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        val seen = mutableListOf<Tracker>()
        val id = mutableStateOf("1")
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                AutographScope("article_id" to id.value) {
                    val current = LocalTracker.current
                    SideEffect { seen += current }
                    // Re-fires whenever the decorator instance changes, so a scope-value change
                    // emits a fresh event through the new decorator.
                    LaunchedEffect(current) { current.track("E") }
                }
            }
        }
        waitForIdle()
        assertEquals("1", tracker.tracks.first { it.first == "E" }.second.str("article_id"))

        id.value = "2"
        waitForIdle()

        // The counterpart of instance-stability: when the scope value genuinely changes,
        // remember(parent, properties) must produce a NEW ScopedTracker so later events carry the
        // new value — a keying that ignored `properties` would serve a permanently stale scope.
        assertEquals("2", tracker.tracks.last { it.first == "E" }.second.str("article_id"))
        assertEquals(2, seen.distinct().size, "a scope-value change must produce a new ScopedTracker")
    }

    @Test
    fun autographScopeAndTrackedScreenMirrorIntoTheAmbientStackForCapture() = runComposeUiTest {
        val stack = ScopeStack()
        var screen: String? = null
        var section: String? = null
        var articleId: String? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                AutographScope("article_id" to "42") {
                    TrackedScreen("Article") {
                        SideEffect {
                            val ctx = stack.current()
                            screen = ctx.screen
                            section = ctx.section
                            articleId = ctx.scope.str("article_id")
                        }
                    }
                }
            }
        }
        waitForIdle()

        // The wiring the capture path depends on: the scope + screen an autocaptured tap happened
        // under are visible in the ambient stack, even though the observer sits above these
        // composables and can't read their CompositionLocals.
        assertEquals("Article", screen)
        assertEquals("42", articleId)
        assertNull(section, "no section was pushed")
    }

    @Test
    fun trackedScreenMirrorsItsSectionIntoTheAmbientStackForCapture() = runComposeUiTest {
        val stack = ScopeStack()
        var screen: String? = null
        var section: String? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                TrackedScreen("Article", section = "Header") {
                    SideEffect {
                        val ctx = stack.current()
                        screen = ctx.screen
                        section = ctx.section
                    }
                }
            }
        }
        waitForIdle()

        // #67: the section was reaching explicit trackClick/trackImpression through
        // LocalScreenContext but never the ambient stack, so an autocaptured tap on the same
        // element carried a screen and no section.
        assertEquals("Article", screen)
        assertEquals("Header", section)
    }

    @Test
    fun anInnerScreenDoesNotInheritTheOuterScreensSection() = runComposeUiTest {
        // Two screens co-mounted — e.g. mid nav transition — the outer carrying a section, the inner
        // carrying none. A tap resolving to the inner screen must NOT pick up the outer screen's
        // section: `TrackedScreen("Comments")` means "Comments, no section". This is the section
        // analogue of changingAnOuterScreenNameDoesNotOverrideAnInnerScreen — the leak the frame model
        // has to prevent, verified through the real TrackedScreen path rather than a raw stack push.
        val stack = ScopeStack()
        var screen: String? = null
        var section: String? = "unset"
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                TrackedScreen("Article", section = "Header") {
                    TrackedScreen("Comments") {
                        SideEffect {
                            val ctx = stack.current()
                            screen = ctx.screen
                            section = ctx.section
                        }
                    }
                }
            }
        }
        waitForIdle()

        assertEquals("Comments", screen)
        assertNull(section, "the inner screen declared no section, so the outer 'Header' must not leak")
    }

    @Test
    fun clearingASectionRemovesItFromTheAmbientStackInPlace() = runComposeUiTest {
        val stack = ScopeStack()
        val current = mutableStateOf<String?>("Header")
        var section: String? = "unset"
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                TrackedScreen("Article", section = current.value) {
                    SideEffect { section = stack.current().section }
                }
            }
        }
        waitForIdle()
        assertEquals("Header", section)

        current.value = null
        waitForIdle()

        // The revision goes through ScopeStack.update, which overwrites the frame's contents — a
        // section that stopped applying must not linger on later events.
        assertNull(section)
    }

    @Test
    fun changingAnOuterScopeValueDoesNotLetItOverrideAnInnerScopeInTheAmbientStack() = runComposeUiTest {
        val stack = ScopeStack()
        val outer = mutableStateOf("outer1")
        var k: String? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                AutographScope("k" to outer.value) {
                    AutographScope("k" to "inner") {
                        SideEffect { k = stack.current().scope.str("k") }
                    }
                }
            }
        }
        waitForIdle()
        assertEquals("inner", k, "the inner scope wins the shared key")

        outer.value = "outer2"
        waitForIdle()

        // The stack resolves precedence by position, so the outer frame must be revised IN PLACE
        // rather than re-pushed: a re-push would move it above the still-mounted inner frame and
        // silently attribute captured taps to the outer scope's value.
        assertEquals("inner", k, "an outer scope value change must not overtake the inner scope")
    }

    /**
     * Sibling scopes mounted at once — list rows each in their own `AutographScope`, split-pane
     * content, a sheet over the screen beneath — are ambiguous: the ambient stack cannot tell which
     * subtree a captured tap landed in. It resolves them to NO scope rather than pinning the tap to
     * an arbitrary sibling, because a wrong scope is worse than none and irreversible in analytics
     * (#66). Scoping a screen/route (one subtree at a time) still attributes exactly; only the
     * genuinely-ambiguous sibling case drops. Position-aware disambiguation is a separate layer (#68).
     */
    @Test
    fun siblingScopesMountedAtOnceResolveToNoScope() = runComposeUiTest {
        val stack = ScopeStack()
        var seenFromInsideRow1: String? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                // Three list rows, each scoped to its own article_id, all mounted together with no
                // enclosing scope — so none is the parent of another.
                AutographScope("article_id" to "row1") {
                    SideEffect { seenFromInsideRow1 = stack.current().scope.str("article_id") }
                }
                AutographScope("article_id" to "row2") {}
                AutographScope("article_id" to "row3") {}
            }
        }
        waitForIdle()

        // From INSIDE a scope, explicit instrumentation stays exact — the lexical decorator still
        // carries row1 to any `track` under it (this is why the bug was easy to miss: attribution
        // looks correct from inside the scope, and only the capture path at tap time saw the sibling
        // ambiguity). This effect runs before row2/row3 fill their frames, so it reads row1.
        assertEquals("row1", seenFromInsideRow1)

        // But an autocaptured tap reads the stack at TAP time, once all three siblings are mounted.
        // Rather than reporting the last one (row3) for a tap that may have hit row1, the stack now
        // drops scope entirely: the ambiguous siblings resolve to no article_id at all.
        assertNull(
            stack.current().scope.str("article_id"),
            "ambiguous sibling scopes resolve to no scope, not the last one mounted",
        )
    }

    /**
     * A scope carried by `movableContentOf` that relocates from inside another scope to being its
     * sibling must pick up its new lineage. The parent link is revised in place through the frame's
     * `update` (not frozen at push time), so after the move the two scopes read as siblings and drop
     * — if the moved scope kept its stale parent it would still merge with the scope it left, silently
     * reporting the wrong scope. Guards the #68 fix against `movableContentOf` reparenting.
     */
    @Test
    fun aScopeMovedOutFromUnderAnotherPicksUpItsNewLineage() = runComposeUiTest {
        val stack = ScopeStack()
        val nested = mutableStateOf(true)
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                val row = remember { movableContentOf { AutographScope("x" to "1") {} } }
                // While nested, `row` sits INSIDE scope "a" — a single chain a > x that merges.
                AutographScope("a" to "1") { if (nested.value) row() }
                // When moved out, `row` is a root sibling of "a": neither encloses the other.
                if (!nested.value) row()
            }
        }
        waitForIdle()
        assertEquals("1", stack.current().scope.str("a"))
        assertEquals("1", stack.current().scope.str("x"), "nested: a > x is one chain and merges")

        nested.value = false
        waitForIdle()

        // Now "a" and "x" are siblings mounted at once — ambiguous, so scope drops entirely. A stale
        // parent link would leave "x" merging with "a" and report the wrong scope for a tap in either.
        assertNull(stack.current().scope.str("x"), "a relocated scope must not keep its old lineage")
        assertNull(stack.current().scope.str("a"))
    }

    @Test
    fun changingAnOuterScreenNameDoesNotOverrideAnInnerScreenInTheAmbientStack() = runComposeUiTest {
        val stack = ScopeStack()
        val outer = mutableStateOf("Outer1")
        var screen: String? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                TrackedScreen(outer.value) {
                    TrackedScreen("Inner") {
                        SideEffect { screen = stack.current().screen }
                    }
                }
            }
        }
        waitForIdle()
        assertEquals("Inner", screen, "the innermost screen wins")

        outer.value = "Outer2"
        waitForIdle()
        assertEquals("Inner", screen, "an outer screen rename must not overtake the inner screen")
    }

    @Test
    fun replacingTheTrackerGivesAFreshScopeStackSoContextCannotLeakAcrossTrackers() = runComposeUiTest {
        val before = ScopeUiRecordingTracker()
        val after = ScopeUiRecordingTracker()
        val step = mutableStateOf(0)
        val stacks = mutableListOf<ScopeStack>()
        setContent {
            AutographProvider(if (step.value == 0) before else after) {
                AutographScope("article_id" to "42") {
                    val stack = LocalScopeStack.current
                    SideEffect { if (stacks.lastOrNull() !== stack) stacks += stack }
                }
            }
        }
        waitForIdle()
        val first = stacks.single()
        assertEquals("42", first.current().scope.str("article_id"))

        step.value = 1
        waitForIdle()

        // AutographProvider scopes the stack to its tracker (`remember(tracker)`), so replacing the
        // tracker — e.g. after logout — must hand out a FRESH stack: ambient context must not leak
        // across trackers, and the retired stack must not keep the old frame alive. The twin
        // ScreenHistory mechanism is pinned the same way (see ScreenTrackingUiTest).
        assertEquals(2, stacks.size, "a tracker swap must produce a new ScopeStack")
        assertTrue(first !== stacks[1], "the stack must be owned per tracker, never shared")
        assertNull(
            first.current().scope.str("article_id"),
            "the retired stack must release its frame, not outlive the tracker swap",
        )
        assertEquals(
            "42",
            stacks[1].current().scope.str("article_id"),
            "the live scope must be mirrored into the new tracker's stack exactly once",
        )
    }

    /**
     * The hybrid-app contract: a stack the caller owns is the one Compose pushes into, so a native
     * capture pipeline holding the same instance resolves a tap's screen and scope from frames the
     * Compose side pushed — without being able to read a CompositionLocal.
     */
    @Test
    fun aCallerSuppliedScopeStackIsTheOneComposePushesInto() = runComposeUiTest {
        val shared = ScopeStack()
        setContent {
            AutographProvider(ScopeUiRecordingTracker(), scopeStack = shared) {
                AutographScope("article_id" to "42") {
                    TrackedScreen("Article") {}
                }
            }
        }
        waitForIdle()

        assertEquals("Article", shared.current().screen)
        assertEquals("42", shared.current().scope.str("article_id"))
    }

    /**
     * The deliberate inverse of [replacingTheTrackerGivesAFreshScopeStackSoContextCannotLeakAcrossTrackers]:
     * the provider resets only the stack it *owns*. A caller-supplied stack is shared with a native
     * pipeline that holds its own reference, so swapping it out here would strand that pipeline on a
     * detached stack while Compose pushed into a new one — every native tap silently losing its
     * screen and scope. Replacing it on logout is therefore the caller's call, not this provider's;
     * the kdoc tells them to scope it to the tracker's lifetime.
     */
    @Test
    fun aCallerSuppliedScopeStackIsNotReplacedOnATrackerSwap() = runComposeUiTest {
        val shared = ScopeStack()
        val before = ScopeUiRecordingTracker()
        val after = ScopeUiRecordingTracker()
        val step = mutableStateOf(0)
        val stacks = mutableListOf<ScopeStack>()
        setContent {
            AutographProvider(
                if (step.value == 0) before else after,
                scopeStack = shared,
            ) {
                AutographScope("article_id" to "42") {
                    val stack = LocalScopeStack.current
                    SideEffect { if (stacks.lastOrNull() !== stack) stacks += stack }
                }
            }
        }
        waitForIdle()
        step.value = 1
        waitForIdle()

        // Count first: `all {}` on an empty list is vacuously true, so without this the assertion
        // below would still pass if the scope never composed at all.
        assertEquals(1, stacks.size, "exactly one stack must ever be seen — no swap, no second instance")
        assertTrue(
            stacks.all { it === shared },
            "the caller's stack must stay in place across a tracker swap, or a native pipeline holding it goes deaf",
        )
        assertEquals("42", shared.current().scope.str("article_id"))
    }

    @Test
    fun leavingAScopeRemovesItsFrameFromTheAmbientStack() = runComposeUiTest {
        val stack = ScopeStack()
        val show = mutableStateOf(true)
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                if (show.value) {
                    AutographScope("article_id" to "42") {}
                }
            }
        }
        waitForIdle()
        assertEquals("42", stack.current().scope.str("article_id"))

        show.value = false
        waitForIdle()
        assertNull(
            stack.current().scope.str("article_id"),
            "the frame must be removed once the scope leaves composition",
        )
    }
}

/** Reads the ambient [LocalTracker] once on composition and hands it to [emit]. */
@Composable
private fun Emit(emit: (Tracker) -> Unit) {
    val tracker = LocalTracker.current
    androidx.compose.runtime.LaunchedEffect(Unit) { emit(tracker) }
}
