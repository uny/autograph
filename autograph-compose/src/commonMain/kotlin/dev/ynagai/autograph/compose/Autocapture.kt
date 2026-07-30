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
 * in the corner of a rounded or shaped clip can be attributed to an element it visually missed. It
 * also reads an element as clickable from its click *action*, which a bare `semantics { onClick { } }`
 * publishes without any pointer input at all — so such an overlay is reported for a tap Compose
 * routes straight through it to the element underneath.
 *
 * A `clickable(enabled = false)` publishes that action too, but is not a gap on either platform: it
 * consumes the pointer like Compose does and is then excluded, so the tap is reported as nothing
 * rather than as a click that never fired. Android reads `SemanticsProperties.Disabled`
 * ([#128](https://github.com/uny/autograph/issues/128)); iOS reads the
 * `UIAccessibilityTraitNotEnabled` the UIKit bridge publishes alongside the button trait
 * ([#134](https://github.com/uny/autograph/issues/134)). The one shape that costs is, on both, the
 * inverse and self-contradictory one — a live `Modifier.clickable` whose semantics were hand-marked
 * `disabled()` (measured in either modifier order on Android) — which does fire and is dropped.
 * `Modifier.zIndex` is *not* in this category — the semantics children the walk descends are
 * z-sorted, so the visually topmost element takes the tap (pinned by `AutocaptureScopeTest`).
 * Neither is the touch target Compose expands past an element's bounds for anything below
 * `minimumInteractiveComponentSize`: Android ranks such a hit by Compose's own rules
 * ([#127](https://github.com/uny/autograph/issues/127)), and iOS needs no counterpart because the
 * UIKit bridge already publishes the *expanded* target as the element's `accessibilityFrame`.
 * Neither, on either platform, is a `clickable` drawn outside its parent's bounds: on Android the
 * semantics walk descends regardless of the parent and only requires the element it reports to
 * contain the tap itself, and on iOS the question cannot arise, since the bridged accessibility tree
 * is flat — such an element is a *sibling* rather than a descendant, so no parent frame excludes it.
 *
 * **One further gap, iOS only, and it is none of the above:** an overhanging tap can still be
 * misreported there, where Android attributes it correctly
 * ([#140](https://github.com/uny/autograph/issues/140)). The walk breaks an overlap between *siblings*
 * that tie on clickability by reverse tree order as a stand-in for z-order, and the bridge does not
 * emit siblings in z-order.
 *
 * What that costs is narrow, because misattribution needs two things at once: an overlap Compose
 * Multiplatform cannot trim away — it shrinks a covered sibling's reported bounds wherever the
 * remainder is still a rectangle, settling the overlap before the tie-break is reached — *and* the
 * element on top sorting earlier in the emitted order. Measured to attribute correctly: a full-width
 * overlay and a horizontal overlap, because the trim settles them, and a badge overhanging to the
 * top-right, because although its corner overlap is not trimmable the badge sorts later anyway. The
 * measured failure is a corner overhang straight up. On the native (UIKit/SwiftUI) pipeline no such
 * trim was observed — measured on SwiftUI, with UIKit unmeasured — leaving the tie-break to decide
 * overlaps that Compose disambiguates.
 *
 * `deepestAccessibilityHitPath`'s kdoc in `autograph-uikit` is the canonical account — the exact
 * conditions, the fixture behind each claim, and why the obvious rankings are refuted rather than
 * merely untried. Deliberately summarized rather than restated here: this description had drifted out
 * of step with the implementation once already, and it did so because the same mechanism was spelled
 * out in three places at once.
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
