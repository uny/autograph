package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ynagai.autograph.context.AutographInternalApi
import dev.ynagai.autograph.uikit.AxPoint
import dev.ynagai.autograph.uikit.AxRect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [AxRect.contains] to `androidx.compose.ui.geometry.Rect.contains`, point for point.
 *
 * The accessibility-tree walk in `autograph-uikit` decides containment with [AxRect], but the tap
 * positions it decides on are produced by Compose (`root.localToWindow`), and this module's
 * [AutocaptureClaims] still hit-tests the same positions with a Compose [Rect]. So the two
 * implementations grade the same coordinates and must agree exactly — a divergence at the boundary
 * would misattribute only edge taps, which is precisely the kind of bug that survives casual testing.
 *
 * `autograph-uikit` can't own this test: it deliberately has no Compose dependency, so this source set
 * is the only place both types exist. Kept as an explicit cross-check rather than a comment, because
 * `Rect.contains`'s edge semantics are an upstream detail that could drift without warning.
 */
@OptIn(AutographInternalApi::class)
class AxRectMatchesComposeRectTest {

    private fun assertAgreesAt(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float) {
        val compose = Rect(left, top, right, bottom).contains(Offset(x, y))
        val ax = AxRect(left, top, right, bottom).contains(AxPoint(x, y))
        assertEquals(compose, ax, "AxRect and Compose Rect disagree on ($x, $y) in ($left, $top, $right, $bottom)")
    }

    @Test
    fun axRectContainsAgreesWithComposeRectOnEdgesAndCorners() {
        val l = 10f
        val t = 20f
        val r = 30f
        val b = 40f
        // Every edge and corner of the rect, plus just-inside/just-outside on each side — the only
        // places the two implementations could differ.
        val xs = listOf(l - 0.1f, l, l + 0.1f, 20f, r - 0.1f, r, r + 0.1f)
        val ys = listOf(t - 0.1f, t, t + 0.1f, 30f, b - 0.1f, b, b + 0.1f)
        for (x in xs) {
            for (y in ys) {
                assertAgreesAt(x, y, l, t, r, b)
            }
        }
    }

    @Test
    fun axRectContainsAgreesWithComposeRectOnAnEmptyRect() {
        assertAgreesAt(10f, 10f, 10f, 10f, 10f, 10f)
    }
}
