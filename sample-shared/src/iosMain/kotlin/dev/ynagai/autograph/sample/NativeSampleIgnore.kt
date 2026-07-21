package dev.ynagai.autograph.sample

import dev.ynagai.autograph.uikit.AutographIgnoredBoundsRegistration
import dev.ynagai.autograph.uikit.registerAutographIgnoredBounds

/**
 * Sample facade over the shipped `registerAutographIgnoredBounds()` opt-out (the mechanism behind
 * `AutographUI`'s `.autographIgnore()`), so the SwiftUI-only sample can register a window region through
 * the **same `sample_shared` framework** that installs the tap capture ([installNativeSampleCapture]).
 *
 * Routing both through one framework is load-bearing, not incidental. `autograph-uikit` is embedded here
 * as an `implementation` dependency, so `sample_shared` carries its own copy of the `AutographIgnoredBounds`
 * registry. If the sample instead reached `registerAutographIgnoredBounds` through the `Autograph` umbrella
 * (a *second* copy of `autograph-uikit`), the marker would register in one registry while the capture's
 * resolver consulted the other — the veto would never see the registration and the exclusion would fail
 * open. The real `.autographIgnore()` avoids this by construction: an app links one framework (the
 * umbrella) for both. The sample links `sample_shared`, so it registers through `sample_shared` too.
 *
 * Shape mirrors [AutographIgnoredBoundsRegistration] one-to-one so the Swift shim marker is identical to
 * the shipped one but for where it gets its handle. **Main thread only.**
 */
public class SampleIgnoredBounds internal constructor(
    private val registration: AutographIgnoredBoundsRegistration,
) {
    public fun update(left: Float, top: Float, right: Float, bottom: Float): Unit =
        registration.update(left, top, right, bottom)

    public fun clear(): Unit = registration.clear()

    public fun unregister(): Unit = registration.unregister()
}

/** Starts a window-region exclusion; call [SampleIgnoredBounds.update] to set its rect. */
public fun registerSampleIgnoredBounds(): SampleIgnoredBounds =
    SampleIgnoredBounds(registerAutographIgnoredBounds())
