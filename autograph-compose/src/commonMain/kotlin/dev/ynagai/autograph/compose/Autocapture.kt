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
 * misattributed. On iOS specifically, embedding `ComposeUIViewController` inside a SwiftUI view
 * *without* `.ignoresSafeArea()` was found (`sample-ios`'s on-device XCUITest suite) to misattribute
 * every tap by a roughly-safe-area-sized offset: `ElementResolver.ios.kt` compares Compose's own
 * window-space tap position (`root.localToWindow`, which excludes the safe-area inset SwiftUI
 * applies to the hosted view) against UIKit accessibility frames converted via
 * `UIScreen.mainScreen.coordinateSpace` (which are real screen-absolute, safe-area-inclusive,
 * coordinates) — the two disagree by the inset amount whenever one is present. `sample-ios`'s
 * `ContentView.swift` works around this with `.ignoresSafeArea()`; the resolver itself doesn't yet
 * account for the mismatch, so any adopter embedding Compose UI in a safe-area-respecting SwiftUI
 * container should expect the same misattribution until this is fixed at the resolver level.
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
    val eventName: String = "Element Clicked",
)
