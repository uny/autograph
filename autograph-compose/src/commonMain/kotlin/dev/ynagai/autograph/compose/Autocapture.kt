package dev.ynagai.autograph.compose

import dev.ynagai.autograph.context.DEFAULT_AUTOCAPTURE_EVENT_NAME

/**
 * Enables automatic click capture when passed to `AutographProvider` — taps anywhere in the
 * composition are observed and reported via [dev.ynagai.autograph.Tracker.track] without needing
 * [trackClick] on every element. Opt-in: omit it (the default) to only report explicitly
 * instrumented elements.
 *
 * Identification prefers `Modifier.testTag`, then the element's semantics role, then its
 * accessibility label — never its displayed text, to avoid capturing PII by default (on iOS, the
 * label step is skipped entirely; see the platform note below). Exclude a subtree entirely with
 * [autographIgnore]; attach per-element properties to the taps under one with [autocaptureScope]
 * (Android only, for now — see its kdoc).
 *
 * Known gaps: `Popup`/`Dialog` content composes into a separate root, outside the single observer
 * `AutographProvider` installs, so taps inside them aren't captured. Hit-testing works on each
 * element's bounding rectangle, so it cannot express a `Modifier.clip` that isn't rectangular: a tap
 * in the corner of a rounded or shaped clip can be attributed to an element it visually missed. Nor
 * does it see the touch target Compose expands past those bounds for anything below
 * `minimumInteractiveComponentSize` ([#127](https://github.com/uny/autograph/issues/127)), and it
 * reads an element as clickable from its click *action*, which `clickable(enabled = false)`
 * publishes too ([#128](https://github.com/uny/autograph/issues/128)) and a bare
 * `semantics { onClick { } }` publishes without any pointer input at all.
 * `Modifier.zIndex` is *not* in this category — the semantics children the walk descends are
 * z-sorted, so the visually topmost element takes the tap (pinned by `AutocaptureScopeTest`).
 * Neither is a `clickable` drawn outside its parent's bounds: the walk descends regardless of the
 * parent, and only requires the element it reports to contain the tap itself.
 *
 * Implemented on Android (via the semantics tree) and iOS (via the UIKit accessibility bridge —
 * see `ElementResolver.ios.kt`). Neither role nor the accessibility label fallback is available on
 * iOS: UIKit gives no way to tell an explicit `contentDescription`-derived label apart from one
 * Compose Multiplatform synthesizes from the element's displayed text, so honoring the "never
 * displayed text" guarantee means identification on iOS relies on `testTag` alone. One narrow
 * exception is outside this library's control: Compose Multiplatform's own UIKit bridge falls back
 * to an inline `LinkAnnotation.Clickable`'s developer-supplied `tag` when no `testTag` is set
 * (never to a `LinkAnnotation.Url`'s destination, which resolves to no identifier at all), so such
 * a link's `tag` can surface as the identifier too. Only elements exposing
 * `UIAccessibilityTraitButton` are treated as clickable. Taps are silently not captured on other
 * targets (JVM/desktop).
 */
public data class AutocaptureConfig(
    val eventName: String = DEFAULT_AUTOCAPTURE_EVENT_NAME,
)
