package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Rect
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
