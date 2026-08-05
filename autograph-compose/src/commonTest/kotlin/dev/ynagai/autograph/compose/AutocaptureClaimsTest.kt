package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutocaptureClaimsTest {

    /** A claim for an element drawn at exactly the size it was measured at — no transform (#159). */
    private fun claim(drawn: Rect) = AutocaptureClaimBounds(drawn, Size(1f, 1f))

    @Test
    fun putRegistersBoundsUnderTheGivenKind() {
        val claims = AutocaptureClaims()
        val key = Any()
        val bounds = Rect(0f, 0f, 10f, 10f)

        claims.put(key, AutocaptureClaimKind.IGNORED, claim(bounds))

        assertEquals(bounds, claims.ignored[key]?.drawn)
        assertTrue(claims.instrumentedClick.isEmpty())
    }

    @Test
    fun removeClearsOnlyTheMatchingKindsEntry() {
        val claims = AutocaptureClaims()
        val key = Any()
        val ignoredBounds = Rect(0f, 0f, 10f, 10f)
        claims.put(key, AutocaptureClaimKind.IGNORED, claim(ignoredBounds))
        claims.put(key, AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(Rect(0f, 0f, 10f, 10f)))

        claims.remove(key, AutocaptureClaimKind.INSTRUMENTED_CLICK)

        assertTrue(claims.instrumentedClick.isEmpty())
        assertEquals(ignoredBounds, claims.ignored[key]?.drawn, "IGNORED entry for the same key should survive INSTRUMENTED_CLICK removal")
    }

    @Test
    fun removingOneKeyDoesNotDisturbAnotherKeysEntry() {
        val claims = AutocaptureClaims()
        val keyA = Any()
        val keyB = Any()
        val boundsB = Rect(20f, 20f, 30f, 30f)
        claims.put(keyA, AutocaptureClaimKind.IGNORED, claim(Rect(0f, 0f, 10f, 10f)))
        claims.put(keyB, AutocaptureClaimKind.IGNORED, claim(boundsB))

        claims.remove(keyA, AutocaptureClaimKind.IGNORED)

        assertEquals(boundsB, claims.ignored[keyB]?.drawn)
    }

    @Test
    fun ignoredAndInstrumentedKindsAreTrackedIndependently() {
        val claims = AutocaptureClaims()
        val key = Any()
        val ignoredBounds = Rect(0f, 0f, 10f, 10f)
        val instrumentedBounds = Rect(50f, 50f, 60f, 60f)

        claims.put(key, AutocaptureClaimKind.IGNORED, claim(ignoredBounds))
        claims.put(key, AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(instrumentedBounds))

        assertEquals(ignoredBounds, claims.ignored[key]?.drawn)
        assertEquals(instrumentedBounds, claims.instrumentedClick[key]?.drawn)
    }
}

private class NoopTracker : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) {}
    override fun screen(name: String, properties: JsonObject) {}
    override fun identify(userId: String, traits: JsonObject) {}
}

/**
 * [registerAutocaptureClaim]'s registration/removal is wired through [androidx.compose.runtime.DisposableEffect]
 * on `onGloballyPositioned`/dispose — [AutocaptureClaimsTest]'s other cases exercise
 * [AutocaptureClaims.put]/[AutocaptureClaims.remove] directly, but never that composition-lifecycle
 * wiring itself, so a regression there (e.g. a stale entry surviving a composable leaving the
 * composition) wouldn't be caught.
 */
@OptIn(ExperimentalTestApi::class)
class AutocaptureClaimDisposalTest {

    @Test
    fun leavingCompositionRemovesTheElementsClaim() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    if (visible) {
                        Box(Modifier.testTag("ignored").size(10.dp).autographIgnore())
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(claims?.ignored?.isNotEmpty() == true, "expected the claim to be registered while composed")

        visible = false
        waitForIdle()

        assertTrue(claims?.ignored?.isEmpty() == true, "expected the claim to be removed once its composable left the composition")
    }

    /**
     * [autographIgnore] was refactored into `composed { semantics{...}.registerAutocaptureClaim(...) }`
     * — the tests above only check the [AutocaptureClaims] map (iOS's path), never that
     * [AutographIgnoredKey] still reaches the [androidx.compose.ui.semantics.SemanticsConfiguration]
     * through the real modifier (Android's path, via `config.isAutocaptureIgnored()`) after that
     * wrapping.
     */
    @Test
    fun autographIgnoreStillSetsTheSemanticsKey() = runComposeUiTest {
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    Box(Modifier.testTag("ignored").size(10.dp).autographIgnore())
                }
            }
        }
        waitForIdle()

        val config = onNodeWithTag("ignored").fetchSemanticsNode().config
        assertTrue(config.isAutocaptureIgnored(), "expected AutographIgnoredKey to still reach the SemanticsConfiguration")
    }

    /**
     * [autographIgnore]'s IGNORED-kind registration is covered above; [trackClick]'s
     * INSTRUMENTED-kind registration is otherwise only ever exercised via directly-constructed
     * [AutocaptureClaims] fixtures in resolver tests, never through the real composable wiring — a
     * regression in [registerAutocaptureClaim]'s call site inside [Modifier.trackClick] specifically
     * wouldn't be caught by either.
     */
    @Test
    fun trackClickRegistersAnInstrumentedClaimWhileComposed() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    if (visible) {
                        Box(Modifier.testTag("tracked").size(10.dp).trackClick("Item Clicked") {})
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(claims?.instrumentedClick?.isNotEmpty() == true, "expected trackClick to register an INSTRUMENTED_CLICK claim while composed")

        visible = false
        waitForIdle()

        assertTrue(claims?.instrumentedClick?.isEmpty() == true, "expected the instrumented claim to be removed once trackClick left the composition")
    }

    /**
     * [trackImpression] registers NO claim — the counterpart to [trackClick]'s above.
     *
     * It reports a visibility event and never a click, so there is nothing for autocapture to
     * double-report and nothing to suppress. It used to register an `INSTRUMENTED_IMPRESSION` claim,
     * and on iOS geometry could not tell that claim apart from the clickable enclosing it: a
     * coincident impression element silently dropped its host's real tap (#158), while qualifying the
     * match to save that tap double-reported the opposite shape. Both were measured byte-identical
     * from the accessibility tree, so the ambiguity was removed rather than resolved.
     */
    @Test
    fun trackImpressionRegistersNoClaim() = runComposeUiTest {
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.testTag("tracked").size(10.dp).trackImpression("Card Viewed"))
                }
            }
        }
        waitForIdle()

        assertTrue(claims?.instrumentedClick?.isEmpty() == true, "trackImpression must register no claim (#158)")
        assertTrue(claims?.ignored?.isEmpty() == true, "trackImpression must register no claim (#158)")
    }

    /**
     * Asserts registerAutocaptureClaim stores the element's actual `boundsInWindow()` value as the
     * claim's [AutocaptureClaimBounds.drawn] half.
     * **Known harness limitation** (confirmed empirically): `runComposeUiTest`'s root composition IS
     * its own window, so `boundsInRoot()` and `boundsInWindow()` are numerically identical here even
     * with this test's padding offset — this assertion alone can't discriminate the two coordinate
     * spaces, and would pass unchanged if `registerAutocaptureClaim` reverted to `boundsInRoot()`.
     * Real regression protection for that would need an on-device/instrumented harness where the
     * composition root is offset from the platform window (safe-area insets, nested embedding) —
     * same class of limitation as this file's other iOS on-device-only findings. Kept as coverage
     * that the stored bounds are real, positioned geometry (not a stale/zero rect), not as a
     * discriminating regression guard.
     */
    @Test
    fun registerAutocaptureClaimStoresTheElementsActualBoundsInWindow() = runComposeUiTest {
        var claims: AutocaptureClaims? = null
        var actualBoundsInWindow: Rect? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.padding(start = 40.dp, top = 60.dp)) {
                        Box(
                            Modifier.testTag("ignored")
                                .size(10.dp)
                                .onGloballyPositioned { actualBoundsInWindow = it.boundsInWindow() }
                                .autographIgnore(),
                        )
                    }
                }
            }
        }
        waitForIdle()

        assertEquals(actualBoundsInWindow, claims?.ignored?.values?.singleOrNull()?.drawn)
    }

    /**
     * A claim also stores the per-axis scale the element was drawn through — the other half of the
     * pair `ElementResolver.ios.kt` needs to derive the touch target Compose published (#159).
     */
    @Test
    fun registerAutocaptureClaimStoresTheScaleAnElementIsDrawnThrough() = runComposeUiTest {
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.testTag("ignored").size(40.dp).scale(0.5f).autographIgnore())
                }
            }
        }
        waitForIdle()

        val stored = claims?.ignored?.values?.singleOrNull()
        assertTrue(stored != null, "expected exactly one ignored claim")
        assertEquals(0.5f, stored.drawScale.width, 0.01f, "must recover the scale the element was drawn through")
        assertEquals(0.5f, stored.drawScale.height, 0.01f, "must recover the scale the element was drawn through")
    }

    /**
     * ...and an ancestor's clip is NOT a scale. This is the discriminating half: `boundsInWindow()`
     * is clipped, so deriving the scale from it instead of from the element's own corners reports
     * this fixture as `0.5` — indistinguishable from the genuine `scale(0.5f)` above — and the
     * resolver would then shrink a clipped element's derived touch target below the plain minimum it
     * matched on before #159, reopening #151 for it.
     *
     * The fixture overflows its clipping host symmetrically, so `boundsInWindow()` reports half the
     * measured height (verified: 20dp measured, 10dp drawn) while nothing is scaled at all.
     */
    @Test
    fun registerAutocaptureClaimDoesNotReportAnAncestorsClipAsAScale() = runComposeUiTest {
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.size(40.dp, 10.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                        Box(Modifier.testTag("ignored").requiredSize(40.dp, 20.dp).autographIgnore())
                    }
                }
            }
        }
        waitForIdle()

        val stored = claims?.ignored?.values?.singleOrNull()
        assertTrue(stored != null, "expected exactly one ignored claim")
        assertTrue(
            stored.drawn.height < stored.drawn.width / 2f,
            "fixture must actually be clipped, or this test proves nothing: drawn = ${stored.drawn}",
        )
        assertEquals(1f, stored.drawScale.width, 0.01f, "a clip is not a scale")
        assertEquals(1f, stored.drawScale.height, 0.01f, "a clip is not a scale")
    }

    /**
     * A still-composed element that moves/resizes must overwrite its existing claim entry, not
     * accumulate a second one — [registerAutocaptureClaim] keys by a stable per-call-site identity
     * specifically so relayout re-puts under the same key.
     */
    @Test
    fun registerAutocaptureClaimOverwritesBoundsOnRepositionRatherThanAccumulating() = runComposeUiTest {
        var offset by mutableStateOf(0.dp)
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.padding(start = offset)) {
                        Box(Modifier.testTag("ignored").size(10.dp).autographIgnore())
                    }
                }
            }
        }
        waitForIdle()
        val boundsBeforeMove = claims?.ignored?.values?.singleOrNull()
        assertTrue(boundsBeforeMove != null, "expected exactly one ignored claim before moving")

        offset = 40.dp
        waitForIdle()

        assertEquals(1, claims?.ignored?.size, "expected the claim to be overwritten in place, not accumulated")
        val boundsAfterMove = claims?.ignored?.values?.singleOrNull()
        assertTrue(boundsAfterMove != boundsBeforeMove, "expected the stored bounds to reflect the new position")
    }
}
