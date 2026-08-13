package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Attaches [properties] to every autocaptured tap on an element inside [content].
 *
 * ```kotlin
 * AutographElementScope("article_id" to article.id) {
 *     Row(Modifier.testTag("article_row").clickable { open(article) }) { … }
 * }
 * ```
 *
 * The tap is still reported as `article_row`; the scope rides along as properties on that event. Use
 * it where the *same* composable is mounted many times over different data — list rows, a grid, a
 * carousel — and the element's name alone can't say which one was tapped. It composes with itself:
 * an enclosing scope contributes to a nested one, the inner winning a key clash, and [AutographScope]
 * frames enclosing the element contribute underneath both. Screen and section from [TrackedScreen]
 * still win over all of them, so don't name a scope key `screen` or `section`.
 *
 * **A wrapper, not a modifier, and that is load-bearing on iOS.** The scope has to occupy a layout
 * node of its own: iOS carries it in the element's accessibility identifier, the same slot the tap's
 * own `target` comes from, and Compose collapses two `testTag`s on one layout node to the first. Put
 * on the clickable's own chain the two would fight, and the element would lose either its scope or
 * its name. This API's shape is what makes that impossible rather than documented-against — which is
 * why it replaces [autocaptureScope], whose kdoc endorsed exactly the usage iOS cannot support.
 *
 * The wrapper is a [Box]: it adds one layout node, propagates its constraints — minimums included,
 * which is why it passes `propagateMinConstraints = true` — and clips nothing, so a `clickable` drawn
 * *outside* it — an overhanging badge, an `offset` decoration — is still scoped and still resolves.
 *
 * **Two things a wrapper cannot be transparent to.** Both follow from it being a real layout node, so
 * neither is fixable here — wrap a single element and keep the rest outside.
 *
 * - **Modifiers scoped to the *enclosing* layout.** `Modifier.weight` in a `Row`/`Column`,
 *   `Modifier.align` / `matchParentSize` in a `Box`, and `alignByBaseline` are parent data read by the
 *   direct parent, and after wrapping the direct parent is this [Box] rather than yours. `weight` and
 *   `alignByBaseline` stop compiling, which is the loud half; `align` and `matchParentSize` are members
 *   of [BoxScope] and quietly rebind to this wrapper, which is the half to watch (measured: an
 *   `align(BottomEnd)` moved inside stops reaching its former parent, and a `matchParentSize` collapses
 *   to zero).
 * - **More than one child.** [content] is a [BoxScope], so two siblings stack instead of being laid out
 *   by your `Row`/`Column` — `Row { AutographElementScope(…) { Icon(…); Text(…) } }` draws the icon and
 *   the label on top of each other. Wrap the element, not a run of siblings.
 *
 * **What it does to accessibility, on iOS only.** The scope reaches the tap through the UIKit
 * accessibility tree, which is the only route to a tapped element Compose Multiplatform offers there,
 * so the wrapper is published as a real accessibility container — a `UIAccessibilityContainerType`
 * semantic group — and the scope's keys and values sit in that container's `accessibilityIdentifier`
 * verbatim. Two consequences worth knowing before you opt in:
 *
 * - **VoiceOver.** Reading order, stop count and spoken labels come out unchanged across four
 *   controlled A/Bs against identical unwrapped content, matching the mechanism: `clickable` merges
 *   its subtree into a single stop and the container never takes focus itself. Those A/Bs read the
 *   **bridged hierarchy** rather than a running VoiceOver, so they are strong evidence and not a
 *   screen-reader pass — the distinction matters because a null result on one accessibility surface
 *   does not carry to another, which is exactly how the rotor was missed on the first pass. The rotor
 *   *does* change, and that part is measured outright: with it set to Containers, the scoped element
 *   becomes one more navigation target. Additive — nothing hidden, no stop lost, no order moved — but
 *   it is a change to your app's accessibility structure, made by an analytics library, and you are
 *   the one choosing it. **Unchecked on a device:** whether VoiceOver announces the group's boundary
 *   on entry or exit.
 * - **The scope is visible on the device.** An accessibility identifier is readable by any
 *   accessibility client — Accessibility Inspector, Appium, Maestro, a page-source dump — so don't
 *   put anything in a scope you wouldn't put in a `testTag`. It is also an extra node in the
 *   hierarchy, which UI tests that count or traverse descendants will see.
 *
 * Also on iOS only: the scope is parsed on the main thread inside a tap handler, so a scope whose
 * encoded form exceeds 2048 characters is dropped whole — the element still reports its tap, without
 * the scope — and a one-line diagnostic naming the size is printed to the console once per process.
 * Scopes are for identifiers; nothing this API is for comes near the ceiling.
 *
 * On Android none of that applies: the scope travels as a private semantics property that no
 * assistive technology can perceive, and no traversal grouping is set.
 *
 * **Requires Compose Multiplatform 1.11 or newer for the iOS half.** Older versions do not publish a
 * traversal group as its own accessibility element, so the wrapper is bridged as a flat sibling of the
 * clickable rather than its ancestor and the scope is silently absent. The failure is a *missing*
 * property rather than a wrong one, which is the direction this library takes on purpose, but it is
 * silent — pin your Compose version if you rely on iOS scopes.
 */
@Composable
public fun AutographElementScope(
    vararg properties: Pair<String, String>,
    content: @Composable BoxScope.() -> Unit,
) {
    AutographElementScope(
        JsonObject(properties.associate { (k, v) -> k to JsonPrimitive(v) }),
        content,
    )
}

/**
 * [AutographElementScope] overload taking a [JsonObject], for scope values that aren't strings
 * (numbers, booleans, nested objects) or that are already assembled as a [JsonObject].
 */
@Composable
public fun AutographElementScope(
    properties: JsonObject,
    content: @Composable BoxScope.() -> Unit,
) {
    // `propagateMinConstraints = true`, not the Box default: a wrapper that zeroed the incoming
    // minimums would stop stretching content its parent had stretched before, and the caller cannot
    // compensate — this composable takes no `Modifier`. Measured against a `propagateMinConstraints`
    // parent, which is the shape `Surface` uses: the wrapped child went 1024x20 -> 20x20, which for a
    // row with `Arrangement.SpaceBetween` bunches its content at the start. Where the incoming minimums
    // are 0 — the common case, and what `Column`/`Row`/`LazyColumn` pass their children — this changes
    // nothing.
    Box(Modifier.autographElementScopeMarker(properties), propagateMinConstraints = true, content = content)
}

/**
 * Writes the scope onto this node in whatever form the platform's resolver can read back.
 *
 * Android and desktop write a private semantics property — invisible to assistive technology, read
 * off the tapped node's ancestry. iOS writes a traversal group and a reserved `testTag`, because the
 * UIKit accessibility bridge carries no custom semantics and the identifier is the only slot that is
 * both identity-bearing and never spoken aloud.
 *
 * Both implementations must apply their semantics through a value-equal [ModifierNodeElement] rather
 * than a `semantics {}` lambda: `Modifier.semantics` compares its lambda by reference, and one
 * capturing the scope is a fresh instance every call, so an unchanged scope would invalidate its
 * layout node's semantics on every recomposition. This API is aimed squarely at list rows, where that
 * difference lands — see [autocaptureScope] for the measurement.
 *
 * `@Composable` for the sake of iOS, the one platform that has work to do here beyond handing the
 * scope to a node: it encodes the payload and checks it against a ceiling, and `remember` is what
 * keeps that off the recomposition path and keeps its diagnostic from repeating.
 */
@Composable
internal expect fun Modifier.autographElementScopeMarker(properties: JsonObject): Modifier
