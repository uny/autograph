package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.uikit.LocalUIView
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.uikit.AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX
import dev.ynagai.autograph.uikit.AxPoint
import dev.ynagai.autograph.uikit.accessibilityIdentifierOrNull
import dev.ynagai.autograph.uikit.deepestAccessibilityHitPath
import dev.ynagai.autograph.uikit.isAccessibilityDisabled
import dev.ynagai.autograph.uikit.isAutographScopeContainer
import dev.ynagai.autograph.uikit.nearestAccessibilityClickable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import platform.UIKit.UIScreen
import platform.UIKit.UIView

/**
 * Resolves a Compose tap to an element identifier by hit-testing the UIKit accessibility tree that
 * Compose Multiplatform bridges its semantics into, rather than Compose's own `SemanticsOwner`
 * (unlike Android — iOS has no supported route to a `SemanticsOwner` from application code). The walk
 * itself lives in `autograph-uikit`, which documents that mechanism, its on-device evidence, and its
 * coordinate space; this file is only the Compose adapter over it.
 *
 * The adapter's two jobs:
 *
 * 1. **Coordinate conversion.** The walk works in window-space pixels, which is exactly what Compose
 *    produces: [rememberElementResolver] converts the tap via `root.localToWindow(position)` (like
 *    Android's `resolveAutocaptureTarget` does against `boundsInWindow`), and `Offset`/
 *    `LayoutCoordinates` are already pixels. So the conversion here is a straight re-wrap into
 *    [AxPoint] — no scaling, no origin shift.
 * 2. **The two vetoes.** Custom semantics keys don't survive the UIKit bridge, so unlike Android this
 *    resolver can't read [AutographIgnoredKey]/[AutographInstrumentedKey] off the hit node's
 *    ancestry. It consults [AutocaptureClaims] instead: [autographIgnore] by position, and
 *    [trackClick] by whether its handler ran during this dispatch. [AutocaptureClaims] documents why
 *    the two answer differently.
 *
 * The positional half is blind to ancestry — it asks "is the tap inside some registered rect" rather
 * than "is a registered rect an ancestor of the hit node" (what Android's [resolveAutocaptureTarget]
 * does) — so a tap can be suppressed by an [autographIgnore]'d element that isn't on the hit path,
 * merely overlapping it at that position (e.g. mid-scroll/transition, or a stale entry for a
 * composable that's still composed but visually covered). Android can't have this failure mode since
 * it only ever looks at the hit node's own ancestor chain.
 */
@Composable
internal actual fun rememberElementResolver(): ElementResolver {
    val view = LocalUIView.current
    val claims = LocalAutocaptureClaims.current
    return remember(view, claims) {
        ElementResolver { root, position -> resolveIosElement(view, claims, root.localToWindow(position)) }
    }
}

/**
 * The non-Composable core of [rememberElementResolver]'s resolve callback, pulled out so it's
 * directly testable against a hand-built [UIView] tree and [AutocaptureClaims] fixture —
 * `compose.uiTest`'s iOS scene can't otherwise exercise this (see [PlatformAutocaptureTestHost.ios.kt]).
 *
 * [position] is window-relative and in pixels (see [rememberElementResolver]).
 */
@OptIn(AutographInternalApi::class)
internal fun resolveIosElement(
    view: UIView,
    claims: AutocaptureClaims?,
    position: Offset,
): AutocaptureTarget? {
    if (claims != null) {
        if (claims.ignored.values.any { it.contains(position) }) return null
        // Suppress whatever this dispatch would have resolved to, not just the element that ran.
        //
        // That is deliberately weaker than "the resolved element is the instrumented one", and the
        // weakness is the point: a single pointer activates at most one `clickable` (consumption in
        // the Main pass sees to that), so if a [trackClick] handler ran, this dispatch's click is
        // already reported. Whatever the accessibility walk resolves here is then usually that same
        // element or a misattribution, and both should be dropped.
        //
        // "Usually", because stock `clickable`s are what consumption arbitrates. An element that
        // publishes a click *action* without owning the pointer — a hand-written `semantics { onClick
        // {} }`, which `AutocaptureConfig`'s kdoc already lists as a gap in its own right — can be
        // what the walk resolves while an enclosing [trackClick] is what actually ran. That element
        // loses its `Element Clicked`. The tap itself is still reported, by the enclosing
        // [trackClick]'s own explicit event, so this costs attribution rather than the interaction;
        // narrowing it would mean identifying the marking element, which is the geometry this change
        // exists to delete.
        //
        // Stating it this way is what finally separates the shape geometry could not. Measured on
        // device: a `fillMaxWidth` `trackClick` `Text` inside a `fillMaxWidth`, 48dp, uninstrumented
        // `clickable` publishes frames — host `(16, 256, 370x48)`, inner `(16, 244, 370x48)` — that
        // are the same size and both contain the claim, differing only in where Compose's
        // touch-target clamp landed. No rule over those rectangles can tell which one owns the tap,
        // and every attempt either double-reported the inner or silently dropped the host's own tap
        // (#179, and #151/#153/#158/#159 before it). Execution decides it without ambiguity: tapping
        // the host's exposed strip runs no `trackClick`, so nothing is marked and the host is
        // reported; tapping the inner runs one, and the dispatch stays silent.
        //
        // The evidence is only trusted when the observer could attribute it to the pointer being
        // reported — see [AutocaptureObserver]'s guards, which discard it otherwise.
        if (claims.instrumentedClickExecutedThisGeneration()) return null
    }
    val scale = UIScreen.mainScreen.scale.toFloat()
    // `allowScopeContainerDescent` is turned on here and nowhere else. The walk starts at the Compose
    // host, so every node it reaches is Compose-owned and the exemption has no ownership boundary to
    // cross — see deepestAccessibilityHitPath's kdoc for what turning it on in the native pipeline
    // would cost.
    val path = deepestAccessibilityHitPath(
        view, view, AxPoint(position.x, position.y), scale, allowScopeContainerDescent = true,
    ) ?: return null
    val nearestClickable = path.nearestAccessibilityClickable() ?: return null
    // A disabled element takes the hit and is vetoed here, exactly as Android's
    // resolveAutocaptureTarget vetoes SemanticsProperties.Disabled (#128) — measured on-device,
    // tapping one fires no handler at all, not even its enabled clickable parent's. Confined to the
    // resolved element, never asked of its ancestry. See isAccessibilityDisabled for both halves.
    if (nearestClickable.isAccessibilityDisabled()) return null
    // A reserved identifier is Autograph's own marker, never a name the app chose, so it must not be
    // reported as what the user touched. It reaches here only if an app put the prefix on something
    // clickable — the scope wrappers this library emits carry no click action — and the element then
    // resolves to no identifier at all and the tap drops, which is the fail-closed direction and is
    // what AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX's kdoc promises.
    if (nearestClickable.isAutographScopeContainer()) return null
    // No `label` argument: the accessibility label is never read — see accessibilityIdentifierOrNull's
    // kdoc in autograph-uikit for why falling back to it would defeat the "never read displayed text"
    // guarantee that Android's resolveAutocaptureTarget honors by only ever reading ContentDescription.
    val identifier =
        identifierFrom(testTag = nearestClickable.accessibilityIdentifierOrNull(), role = null, label = null)
            ?: return null
    // Read off the SAME path the identifier came from. Deriving the scope from anywhere else — a
    // positional registry, mount order — is what lets a tap be reported against one element carrying
    // a neighbour's scope, the failure AutocaptureTarget's kdoc exists to rule out. `path` is
    // root→hit node, so a deeper wrapper folds in later and wins a key clash, matching both Android's
    // resolveAutocaptureTarget and how nested scopes compose everywhere else.
    return AutocaptureTarget(identifier, path.scopeOnPath())
}

/**
 * Merges every Autograph scope wrapper on this hit path, outer→inner.
 *
 * A payload that doesn't parse is skipped rather than propagated or thrown on. The identifier is the
 * host app's to write and an app is free to put the reserved prefix on something of its own, so this
 * runs against arbitrary strings on the main thread inside a tap handler; a wrong scope lands in
 * analytics data looking true, and a crash costs the whole app. Dropping the entry leaves the tap
 * attributed exactly as it would have been without the wrapper.
 */
@OptIn(AutographInternalApi::class)
private fun List<Any>.scopeOnPath(): JsonObject = fold(EmptyJsonObject) { acc, node ->
    val payload = node.accessibilityIdentifierOrNull()
        ?.takeIf { it.startsWith(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX) }
        ?.removePrefix(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX)
        ?: return@fold acc
    val parsed = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return@fold acc
    when {
        parsed.isEmpty() -> acc
        acc.isEmpty() -> parsed
        else -> JsonObject(acc + parsed)
    }
}
