package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack

/**
 * Captures taps inside an **alternate Compose root** — the content of a `Dialog`, a `Popup`, a
 * `ModalBottomSheet` — the way the enclosing [AutographProvider] captures taps in its own content
 * ([#210](https://github.com/uny/autograph/issues/210)).
 *
 * Such content composes into a window of its own, outside the single tap observer the provider
 * installs, so its taps were delivered and consumed without ever being seen: every tap inside a bottom
 * sheet went unreported, with no warning, indistinguishable from a sheet nobody touched. A second
 * `AutographProvider` inside the sheet would observe them, but it fills the window (its observer is a
 * `fillMaxSize` box), so a wrap-content sheet expands to full height; and it registers a second
 * lifecycle observer and a second host registration that a sheet does not want. This attaches the
 * observer alone, to the node you put it on, which keeps its own size:
 *
 * ```kotlin
 * ModalBottomSheet(onDismissRequest = onDismiss) {
 *     Column(Modifier.autographAutocaptureRoot()) {
 *         FilterChips(...)
 *     }
 * }
 * ```
 *
 * Everything else comes from the enclosing provider, through the composition locals the alternate root
 * inherits: its tracker, its [AutocaptureConfig] (event name included), its `ScopeStack`, and where its
 * taps are resolved from — so a tap in the sheet carries the screen, section and scope declared around
 * the sheet, exactly as a tap beside it does, and [autographIgnore], [trackClick] and
 * [AutographElementScope] inside the sheet behave as they do in the provider's own content. Only taps
 * on the node this is applied to and its descendants are observed; put it on the outermost node of the
 * sheet's content.
 *
 * **Android only.** It is a no-op, with a one-time console warning, on iOS and the JVM: whether the
 * iOS resolver, which walks the UIKit accessibility tree from the provider's view, reaches the content
 * of a Compose `Dialog` there has not been measured, and an observer that resolves nothing would only
 * look like coverage. It is also a no-op, with the same warning:
 * - with no enclosing `AutographProvider`, or one without `autocapture`, since there is nothing to
 *   report to — and silently reporting nothing is exactly what this exists to end;
 * - in the provider's own window, where the provider's observer already sees every tap and a second
 *   one would report each of them twice. Apply it inside the alternate root's content lambda, not
 *   around the `Dialog` / `ModalBottomSheet` call.
 *
 * The provider it attaches for is the nearest one **with** autocapture. A provider without it nested
 * inside one with it does not change that, so a sheet inside the inner provider's content reports
 * through the outer one's tracker — as every other tap in that content already does.
 */
public fun Modifier.autographAutocaptureRoot(): Modifier = composed {
    val root = LocalAutocaptureRoot.current
    when {
        !autocaptureRootSupported -> {
            warnAutocaptureRootOnce(Misuse.Unsupported, "Modifier.autographAutocaptureRoot() is Android-only for now; it does nothing here.")
            Modifier
        }
        root == null -> {
            warnAutocaptureRootOnce(
                Misuse.NoProvider,
                "Modifier.autographAutocaptureRoot() found no enclosing AutographProvider with autocapture; " +
                    "taps in this root are not captured.",
            )
            Modifier
        }
        root.window != null && root.window == currentAutocaptureWindow() -> {
            warnAutocaptureRootOnce(
                Misuse.OwnWindow,
                "Modifier.autographAutocaptureRoot() is in the provider's own window, which the provider already " +
                    "observes; apply it inside the Dialog/Popup/ModalBottomSheet content instead.",
            )
            Modifier
        }
        else -> Modifier.autocaptureTaps(root.tracker, root.scopeStack, root.config, root.origin)
    }
}

/**
 * What an [AutographProvider] with autocapture hands its alternate roots: the observer's inputs, and
 * the window its own observer covers ([currentAutocaptureWindow]). Provided only when autocapture is
 * on.
 */
internal class AutocaptureRoot(
    val tracker: Tracker,
    val scopeStack: ScopeStack,
    val config: AutocaptureConfig,
    val origin: ProviderOrigin,
    val window: Any?,
)

// Not static: `AutocaptureConfig` compares by identity, so `autocapture = Autocapture()` written
// inline gives the provider a new root on every recomposition, and a static local would recompose
// the provider's whole content each time. Only the modifier reads this.
internal val LocalAutocaptureRoot = compositionLocalOf<AutocaptureRoot?> { null }

/**
 * An identity for the window the calling composition draws into, compared by equality: the Android
 * `LocalView` (a `Dialog` or `Popup` has its own). Null where it cannot be told, which disables the
 * same-window guard.
 */
@Composable
internal expect fun currentAutocaptureWindow(): Any?

/** Whether [autographAutocaptureRoot] does anything on this platform. */
internal expect val autocaptureRootSupported: Boolean

internal enum class Misuse { Unsupported, NoProvider, OwnWindow }

/** The misuses already warned about; each kind warns once, so a later, different one is not silent. */
internal val autocaptureRootWarned = mutableSetOf<Misuse>()

/** One console line per process per kind of misuse; see `MissingTracker` for why not the logger. */
private fun warnAutocaptureRootOnce(kind: Misuse, message: String) {
    if (autocaptureRootWarned.add(kind)) println("Autograph: $message")
}
