package dev.ynagai.autograph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics

/** Marks a subtree as excluded from autocapture. See [autographIgnore]. */
internal val AutographIgnoredKey: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey("AutographIgnored")

/**
 * Marks a subtree as excluded from autocapture (see `AutocaptureConfig` / `AutographProvider`).
 * Explicit instrumentation ([trackClick]/[trackImpression]) inside the subtree is unaffected —
 * this only stops the ambient tap observer from reporting taps here on its own.
 */
public fun Modifier.autographIgnore(): Modifier = composed {
    semantics { this[AutographIgnoredKey] = true }.registerAutocaptureClaim(AutocaptureClaimKind.IGNORED)
}

/** Marks an element as already instrumented via [trackClick]/[trackImpression], so the ambient
 * tap observer doesn't double-report it. Internal — set automatically by those modifiers. */
internal val AutographInstrumentedKey: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey("AutographInstrumented")

/** Android hit-testing convenience: true if [this] carries the subtree-wide ignore marker directly. */
internal fun SemanticsConfiguration.isAutocaptureIgnored(): Boolean =
    getOrNull(AutographIgnoredKey) == true

/** Android hit-testing convenience: true if [this] carries the already-instrumented marker directly. */
internal fun SemanticsConfiguration.isAutocaptureInstrumented(): Boolean =
    getOrNull(AutographInstrumentedKey) == true
