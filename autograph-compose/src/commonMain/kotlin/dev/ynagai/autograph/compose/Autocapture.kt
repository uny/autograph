package dev.ynagai.autograph.compose

/**
 * Enables automatic click capture when passed to `AutographProvider` — taps anywhere in the
 * composition are observed and reported via [dev.ynagai.autograph.Tracker.track] without needing
 * [trackClick] on every element. Opt-in: omit it (the default) to only report explicitly
 * instrumented elements.
 *
 * Identification prefers `Modifier.testTag`, then the element's semantics role, then its
 * accessibility label — never its displayed text, to avoid capturing PII by default (on iOS, the
 * label step is skipped entirely; see the platform note below). Exclude a subtree entirely with
 * [autographIgnore].
 *
 * Known gaps: `Popup`/`Dialog` content composes into a separate root, outside the single observer
 * `AutographProvider` installs, so taps inside them aren't captured. Hit-testing doesn't account
 * for `Modifier.clip`/`Modifier.zIndex`, so an element that's visually clipped or reordered can be
 * misattributed. Implemented on Android (via the semantics tree) and iOS (via the UIKit
 * accessibility bridge — see `ElementResolver.ios.kt`; role isn't available there, and the
 * accessibility label fallback isn't either — UIKit gives no way to tell an explicit
 * `contentDescription`-derived label apart from one Compose Multiplatform synthesizes from the
 * element's displayed text, so honoring the "never displayed text" guarantee means identification
 * on iOS relies on `testTag` alone; only elements exposing `UIAccessibilityTraitButton` are treated
 * as clickable). Taps are silently not captured on other targets (JVM/desktop).
 */
public data class AutocaptureConfig(
    val eventName: String = "Element Clicked",
)
