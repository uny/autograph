package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
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
                LocalScreenHistory provides ScreenHistory(),
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
                LocalScreenHistory provides ScreenHistory(),
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
     * Characterization test for the top-of-stack attribution limit the README documents under
     * "Scoped context" — NOT an endorsement of this outcome. The ambient stack orders frames by when
     * they were mounted, so with sibling scopes mounted simultaneously it cannot tell which subtree
     * a tap landed in and reports the last one. Scoping a screen/route (one subtree mounted at a
     * time) attributes exactly; scoping individual list rows does not.
     *
     * If you make attribution position-aware (resolving scope from the tap-position semantics tree,
     * see #64), this test SHOULD fail — update it and the README's claim together.
     */
    @Test
    fun siblingScopesMountedAtOnceAreAttributedToTheLastOneMounted() = runComposeUiTest {
        val stack = ScopeStack()
        var seenFromInsideRow1: String? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                // Three list rows, each scoped to its own article_id, all mounted together.
                AutographScope("article_id" to "row1") {
                    SideEffect { seenFromInsideRow1 = stack.current().scope.str("article_id") }
                }
                AutographScope("article_id" to "row2") {}
                AutographScope("article_id" to "row3") {}
            }
        }
        waitForIdle()

        // Every row's frame is already pushed by the time any SideEffect runs (remember observers
        // dispatch first), but each frame is pushed EMPTY and filled by its own SideEffect in
        // composition order — so row1's effect, running before row2/row3 fill theirs, still reads
        // row1. Attribution looks correct from inside the scope, which is what makes this limit easy
        // to miss in a narrower test.
        assertEquals("row1", seenFromInsideRow1)

        // But an autocaptured tap reads the stack at TAP time, once every row is mounted. A tap on
        // row1 is therefore reported with row3's article_id: the wrong row, silently.
        assertEquals(
            "row3",
            stack.current().scope.str("article_id"),
            "documented limit: with siblings mounted at once, the last one wins regardless of which was tapped",
        )
    }

    @Test
    fun changingAnOuterScreenNameDoesNotOverrideAnInnerScreenInTheAmbientStack() = runComposeUiTest {
        val stack = ScopeStack()
        val outer = mutableStateOf("Outer1")
        var screen: String? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides ScopeUiRecordingTracker(),
                LocalScreenHistory provides ScreenHistory(),
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
