package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutocaptureClaimsTest {

    @Test
    fun putRegistersBoundsUnderTheGivenKind() {
        val claims = AutocaptureClaims()
        val key = Any()
        val bounds = Rect(0f, 0f, 10f, 10f)

        claims.put(key, AutocaptureClaimKind.IGNORED, bounds)

        assertEquals(bounds, claims.ignored[key])
        assertTrue(claims.instrumented.isEmpty())
    }

    @Test
    fun removeClearsOnlyTheMatchingKindsEntry() {
        val claims = AutocaptureClaims()
        val key = Any()
        claims.put(key, AutocaptureClaimKind.INSTRUMENTED, Rect(0f, 0f, 10f, 10f))

        claims.remove(key, AutocaptureClaimKind.INSTRUMENTED)

        assertTrue(claims.instrumented.isEmpty())
    }

    @Test
    fun removingOneKeyDoesNotDisturbAnotherKeysEntry() {
        val claims = AutocaptureClaims()
        val keyA = Any()
        val keyB = Any()
        val boundsB = Rect(20f, 20f, 30f, 30f)
        claims.put(keyA, AutocaptureClaimKind.IGNORED, Rect(0f, 0f, 10f, 10f))
        claims.put(keyB, AutocaptureClaimKind.IGNORED, boundsB)

        claims.remove(keyA, AutocaptureClaimKind.IGNORED)

        assertEquals(boundsB, claims.ignored[keyB])
    }

    @Test
    fun ignoredAndInstrumentedKindsAreTrackedIndependently() {
        val claims = AutocaptureClaims()
        val key = Any()
        val ignoredBounds = Rect(0f, 0f, 10f, 10f)
        val instrumentedBounds = Rect(50f, 50f, 60f, 60f)

        claims.put(key, AutocaptureClaimKind.IGNORED, ignoredBounds)
        claims.put(key, AutocaptureClaimKind.INSTRUMENTED, instrumentedBounds)

        assertEquals(ignoredBounds, claims.ignored[key])
        assertEquals(instrumentedBounds, claims.instrumented[key])
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
     * [autographIgnore]'s IGNORED-kind registration is covered above; [trackClick]/[trackImpression]'s
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
        assertTrue(claims?.instrumented?.isNotEmpty() == true, "expected trackClick to register an instrumented claim while composed")

        visible = false
        waitForIdle()

        assertTrue(claims?.instrumented?.isEmpty() == true, "expected the instrumented claim to be removed once trackClick left the composition")
    }

    /** [trackImpression]'s own `registerAutocaptureClaim` call site, distinct from [trackClick]'s. */
    @Test
    fun trackImpressionRegistersAnInstrumentedClaimWhileComposed() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    if (visible) {
                        Box(Modifier.testTag("tracked").size(10.dp).trackImpression("Card Viewed"))
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(claims?.instrumented?.isNotEmpty() == true, "expected trackImpression to register an instrumented claim while composed")

        visible = false
        waitForIdle()

        assertTrue(claims?.instrumented?.isEmpty() == true, "expected the instrumented claim to be removed once trackImpression left the composition")
    }

    /**
     * Regression coverage for registerAutocaptureClaim storing `boundsInWindow()` rather than
     * `boundsInRoot()` (the two only coincide when the ignored/tracked element sits flush with the
     * composition root) — the other tests in this class only assert presence/absence of a claim, so
     * this would still pass if the coordinate space silently reverted to `boundsInRoot()`.
     */
    @Test
    fun registerAutocaptureClaimStoresBoundsInWindowSpaceNotRootSpace() = runComposeUiTest {
        var claims: AutocaptureClaims? = null
        var actualBoundsInWindow: Rect? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    // Padding offsets this element from the composition root, so boundsInWindow()
                    // and boundsInRoot() diverge — only the former should match the stored claim.
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

        assertEquals(actualBoundsInWindow, claims?.ignored?.values?.singleOrNull())
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
