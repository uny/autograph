package dev.ynagai.autograph.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.uikit.LocalUIView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.compose.AutocaptureConfig
import dev.ynagai.autograph.compose.AutographProvider
import dev.ynagai.autograph.uikit.AxPoint
import dev.ynagai.autograph.uikit.accessibilityBoundsInWindowPx
import dev.ynagai.autograph.uikit.accessibilityChildren
import dev.ynagai.autograph.uikit.accessibilityIdentifierOrNull
import dev.ynagai.autograph.uikit.deepestAccessibilityHitPath
import dev.ynagai.autograph.uikit.isAccessibilityButton
import dev.ynagai.autograph.uikit.nearestAccessibilityClickable
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.accessibilityElements
import platform.UIKit.accessibilityLabel
import platform.UIKit.isAccessibilityElement
import platform.darwin.NSObject

/**
 * THROWAWAY measurement rig for [#185](https://github.com/uny/autograph/issues/185) **option B** —
 * delete with the spike. Reached from `ContentView.swift` behind `-autograph-measure-185`.
 *
 * Option B's question, in one line: **can a wrapper carry an element scope across the UIKit
 * accessibility bridge in the `accessibilityIdentifier` slot, without disturbing the tap's own
 * `target`?** The scope cannot share a layout node with the element's `testTag` — two `testTag`s on
 * one node collapse, measured in `ZzMeasure185BTest` — so it has to live on a wrapper, and
 * `isTraversalGroup = true` is the lever meant to make Compose Multiplatform publish that wrapper to
 * the bridge as an element of its own rather than folding it away.
 *
 * Three things have to be true, and the first is the one that kills the option outright:
 *
 * 1. the wrapper appears in the iOS accessibility tree **as an element of its own**;
 * 2. `deepestAccessibilityHitPath` returns it **as an ancestor** of the tapped clickable;
 * 3. `nearestAccessibilityClickable` still picks the **same element it picked without the wrapper**.
 *
 * Unlike #185's pointer-dispatch rig this one patches **no production code**: the whole walk is
 * public (`@AutographInternalApi`) surface, so the rig calls it directly and prints what it sees.
 * What is measured here is therefore the shipped walk, not a modified one.
 *
 * Read with:
 * ```
 * xcrun simctl spawn <udid> log stream --style compact --predicate 'eventMessage BEGINSWITH "AX185B"'
 * ```
 * (`BEGINSWITH`, not `CONTAINS` — the device name matches unrelated system logs.)
 *
 * **The tree must be warm.** Both iOS tap pipelines are inert until an accessibility client has
 * connected to the process (#135), and mobile-mcp's tap is itself such a client — so drive this with
 * mobile-mcp, or the dump prints an empty tree and means nothing.
 *
 * Tap **DUMP** to print the whole bridged tree plus, for every fixture, the hit path the shipped walk
 * returns at that fixture's own centre. Tapping a fixture logs `ORACLE`, which is the ground truth for
 * which element Compose actually routed the pointer to.
 */
public fun Measure185ViewController(): UIViewController =
    ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) { Measure185B() }

private fun ax(message: String) = NSLog("AX185B $message")

/** The reserved prefix an option-B scope would claim in the identifier slot. */
private const val SCOPE = "__autograph_scope__"

/**
 * Selects the B8–B11 VoiceOver page instead of B1–B7. Read from the process arguments rather than
 * plumbed through `ContentView.swift`, so the Swift side keeps its single `-autograph-measure-185`
 * entry point and this throwaway file stays self-contained.
 */
private val voiceOverPage: Boolean
    get() = NSProcessInfo.processInfo.arguments.contains("-autograph-measure-185-voiceover")

/** Every fixture's window-space centre in px, filled in by [probe] as each one is positioned. */
private val centres = mutableMapOf<String, Offset>()

private fun Modifier.probe(tag: String): Modifier = onGloballyPositioned {
    // Chain-end placement is deliberate: an `onGloballyPositioned` written before `.offset()` reports
    // the pre-offset coordinates, which silently mis-aims every probe below (#185's spike hit this).
    val b = it.boundsInWindow()
    centres[tag] = b.center
    ax("GEOM $tag ${b.left},${b.top} ${b.width}x${b.height}")
}

/** Option B's scope carrier: a wrapper node whose identifier is the scope, published via traversal group. */
private fun Modifier.scopeWrapper(json: String, traversalGroup: Boolean = true): Modifier =
    (if (traversalGroup) this.semantics { isTraversalGroup = true } else this).testTag(SCOPE + json)

@Composable
private fun Measure185B() {
    val view = LocalUIView.current
    // No AutographScope / TrackedScreen anywhere: the ambient stack would contribute properties of
    // its own and this rig measures exactly one thing — what the element's own ancestry carries.
    AutographProvider(
        tracker = LoggingTracker(
            onTrack = { name, props, target -> ax("REPORT name=$name target=$target props=$props") },
        ),
        autocapture = AutocaptureConfig(),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Box(
                Modifier
                    .testTag("dump")
                    .size(280.dp, 36.dp)
                    .background(Color(0xFF90A4AE))
                    .clickable { dumpEverything(view) },
            )
            // B8–B11 are a *separate page*, selected with `-autograph-measure-185-voiceover`. Laid out
            // below B1–B7 they overflow the screen and the last control reports a 0x0 frame, which
            // leaves the A/B with no control at all. They also need an uncluttered tree to read the
            // VoiceOver stop order out of.
            if (voiceOverPage) {
                B8ScopedTextRow()
                B9PlainTextRow()
                B10ScopedTwoClickables()
                B11PlainTwoClickables()
                B12ScopedSiblingMixedRows()
                B13PlainSiblingMixedRows()
            } else {
                B1TraversalGroupWrapper()
                B2WrapperWithoutTraversalGroup()
                B3NestedWrappers()
                B5SiblingWrappers()
                // Last, and with clearance below: the first run put B4 above B5, where the badge's
                // overhang overlapped `b5_row1` and the bridge trimmed the badge's own frame to the
                // uncovered strip (#140's occlusion trim). The badge's centre then fell outside its
                // own accessibility frame, so what looked like an overhang result was really a
                // neighbour stealing the hit. An overlapping fixture cannot measure the overhang.
                B4WrapperWithOverhang()
                B6OverhangWithoutWrapper()
                B7PlainTraversalGroupOverhang()
            }
        }
    }
}

/**
 * B1 — the canonical shape. A traversal-group wrapper carrying the scope, one plain `clickable` with
 * its own `testTag` inside. Pass = the dump shows a `__autograph_scope__` element, and `HIT b1_row`
 * lists it as an ancestor with `b1_row` still the nearest clickable.
 */
@Composable
private fun B1TraversalGroupWrapper() {
    Box(Modifier.padding(top = 8.dp).scopeWrapper("""{"article_id":"42"}""")) {
        Box(
            Modifier
                .testTag("b1_row")
                .size(280.dp, 44.dp)
                .background(Color(0xFFB3E5FC))
                .probe("b1_row")
                .clickable { ax("ORACLE b1_row") },
        )
    }
}

/**
 * B2 — the control that says whether `isTraversalGroup` is doing any work at all. Same wrapper, same
 * reserved `testTag`, no traversal group. If this one is published too, the lever is unnecessary and
 * option B costs the host app no VoiceOver grouping change; if only B1 is published, the grouping
 * side effect is the price of the option and has to be weighed.
 */
@Composable
private fun B2WrapperWithoutTraversalGroup() {
    Box(Modifier.padding(top = 8.dp).scopeWrapper("""{"card":"plain"}""", traversalGroup = false)) {
        Box(
            Modifier
                .testTag("b2_row")
                .size(280.dp, 44.dp)
                .background(Color(0xFFC8E6C9))
                .probe("b2_row")
                .clickable { ax("ORACLE b2_row") },
        )
    }
}

/**
 * B3 — nesting. An enclosing scope and a per-row one, the composition [autocaptureScope]'s kdoc
 * promises on Android. Pass = **both** wrappers on the path, outer first, so a merge can apply
 * outer→inner with the inner winning a key clash.
 */
@Composable
private fun B3NestedWrappers() {
    Box(Modifier.padding(top = 8.dp).scopeWrapper("""{"section":"for_you"}""")) {
        Box(Modifier.scopeWrapper("""{"article_id":"7"}""")) {
            Box(
                Modifier
                    .testTag("b3_row")
                    .size(280.dp, 44.dp)
                    .background(Color(0xFFFFE0B2))
                    .probe("b3_row")
                    .clickable { ax("ORACLE b3_row") },
            )
        }
    }
}

/**
 * B4 — the overhang, and the reason this option is not obviously equivalent to Android's. Android
 * reads the scope off the *semantics ancestry*, so a `clickable` drawn outside its wrapper's bounds
 * still carries the wrapper's scope (#126). This walk gates the descent on **geometric containment**
 * at every node below the root — so a wrapper whose frame misses the tap prunes before its own child
 * is reached, and the scope is lost exactly where Android keeps it. Measuring the size of that
 * divergence is the point; `autocaptureScope`'s kdoc currently promises the overhang works.
 *
 * **Nothing may overlap the badge** — see the note at this fixture's call site for the first run,
 * where a neighbour's overlap trimmed the badge's own accessibility frame and the "overhang result"
 * was really a stolen hit.
 */
@Composable
private fun B4WrapperWithOverhang() {
    Box(
        Modifier
            .padding(top = 48.dp)
            .scopeWrapper("""{"list":"main"}""")
            .size(280.dp, 24.dp)
            .background(Color(0xFFD1C4E9))
            .probe("b4_wrapper"),
    ) {
        Box(
            Modifier
                .testTag("b4_badge")
                // requiredSize, not size: the wrapper is 24.dp tall and would otherwise clamp the
                // badge to its own height, defeating the overhang this fixture exists to create.
                .offset(y = 24.dp)
                .requiredSize(120.dp, 40.dp)
                .background(Color(0xFFF8BBD0))
                .probe("b4_badge")
                .clickable { ax("ORACLE b4_badge") },
        )
    }
}

/**
 * B5 — #68's actual shape: sibling scopes mounted at the same time, which the ambient `ScopeStack`
 * cannot tell apart and therefore drops. Pass = each row's path carries its **own** wrapper and not
 * its neighbour's. This is the fixture that would make option B worth shipping at all.
 */
@Composable
private fun B5SiblingWrappers() {
    Column(Modifier.padding(top = 8.dp)) {
        for (id in listOf("1", "2")) {
            Box(Modifier.scopeWrapper("""{"article_id":"$id"}""")) {
                Box(
                    Modifier
                        .testTag("b5_row$id")
                        .size(280.dp, 40.dp)
                        .background(Color(0xFFB0BEC5))
                        .probe("b5_row$id")
                        .clickable { ax("ORACLE b5_row$id") },
                )
            }
        }
    }
}

/**
 * Prints the bridged tree, then runs the **shipped** walk at every fixture's centre and prints the
 * path it returns. Both halves read the same public surface `autograph-compose`'s resolver uses, so
 * a disagreement between this and production would be a bug in the rig, not in the measurement.
 */
/**
 * B8/B9 — **the VoiceOver cost, on the shape option B actually exists for.** B1–B7 all wrap a single
 * clickable with no children, where a traversal group has nothing to reorder; #68's use case is an
 * article row with several published children, and that is where `isTraversalGroup` does the thing it
 * was designed to do. Identical rows, one variable: B8 carries the scope wrapper, B9 is a bare `Box`.
 *
 * Read `VOICEOVER` in the dump, not `TREE`: the walk's [accessibilityChildren] unions
 * `accessibilityElements + subviews` for hit-testing, which is *not* how VoiceOver picks an order.
 * [dumpVoiceOverOrder] models the real rule instead — `accessibilityElements` when non-empty, else
 * `subviews` — and emits only nodes that claim `isAccessibilityElement`, i.e. the swipe stops.
 *
 * Compare the two runs of stops. Same sequence, same labels = the grouping is inert on this shape and
 * the a11y objection to option B is answered. A stop added, removed, or reordered = the SDK changes
 * what a VoiceOver user hears, and option B does not ship in this form.
 */
@Composable
private fun B8ScopedTextRow() {
    Box(Modifier.padding(top = 8.dp).scopeWrapper("""{"article_id":"88"}""")) {
        TextRow("b8", Color(0xFFFFE0B2))
    }
}

/** B9 — B8's control. Same row, no wrapper published at all. */
@Composable
private fun B9PlainTextRow() {
    Box(Modifier.padding(top = 8.dp)) { TextRow("b9", Color(0xFFD1C4E9)) }
}

/**
 * One `clickable` row with three published `Text` children — `clickable` merges descendant semantics,
 * but Compose Multiplatform publishes the child `Text`s across the bridge anyway (measured in #153),
 * which is what makes this a multi-stop subtree rather than a single element.
 */
@Composable
private fun TextRow(id: String, colour: Color) {
    Row(
        Modifier
            .testTag("${id}_row")
            .size(280.dp, 44.dp)
            .background(colour)
            .probe("${id}_row")
            .clickable { ax("ORACLE ${id}_row") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Title $id", Modifier.padding(horizontal = 4.dp))
        Text("Author $id", Modifier.padding(horizontal = 4.dp))
        Text("New", Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * B10/B11 — the harder half of the same question. Two *separately focusable* clickables side by side,
 * an article row and its bookmark button, which is the shape a traversal group is most likely to
 * regroup. B10 wraps them in the scope, B11 does not.
 */
@Composable
private fun B10ScopedTwoClickables() {
    Box(Modifier.padding(top = 8.dp).scopeWrapper("""{"article_id":"1010"}""")) {
        TwoClickables("b10", Color(0xFFB2DFDB))
    }
}

/** B11 — B10's control. */
@Composable
private fun B11PlainTwoClickables() {
    Box(Modifier.padding(top = 8.dp)) { TwoClickables("b11", Color(0xFFF8BBD0)) }
}

@Composable
private fun TwoClickables(id: String, colour: Color) {
    Row(Modifier.size(280.dp, 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .testTag("${id}_row")
                .size(220.dp, 44.dp)
                .background(colour)
                .probe("${id}_row")
                .clickable { ax("ORACLE ${id}_row") },
        )
        Box(
            Modifier
                .testTag("${id}_bookmark")
                .size(60.dp, 44.dp)
                .background(Color(0xFF9E9E9E))
                .probe("${id}_bookmark")
                .clickable { ax("ORACLE ${id}_bookmark") },
        )
    }
}

/**
 * B12/B13 — **the case a traversal group is actually for**, and the only one that can falsify "the
 * wrapper is inert". B8–B11 each wrapped a `clickable`, which merges its descendants into one
 * VoiceOver stop, so the group had nothing to reorder and inertness there proves little.
 *
 * Here each row publishes *three* stops — two `Text`s and a `clickable` bookmark — and there are two
 * such rows with column-aligned children. Compose Multiplatform emits siblings in `(left, top)`
 * order, x-primary (#140), so ungrouped the two rows' stops **interleave by column**: both titles,
 * then both authors, then both bookmarks. Grouping each row is precisely what pulls them back into
 * row order. So this is the shape where the answer can differ:
 *
 * - B12 and B13 produce the same stop sequence → the wrapper is inert even here.
 * - They differ → option B changes what a VoiceOver user hears, and the direction of the change
 *   (toward row order, or away from it) is the thing to weigh.
 */
@Composable
private fun B12ScopedSiblingMixedRows() {
    Column {
        for (id in 1..2) {
            Box(Modifier.padding(top = 8.dp).scopeWrapper("""{"article_id":"12$id"}""")) {
                MixedRow("b12_$id", Color(0xFFFFCDD2))
            }
        }
    }
}

/** B13 — B12's control. Identical rows, no wrapper published. */
@Composable
private fun B13PlainSiblingMixedRows() {
    Column {
        for (id in 1..2) {
            Box(Modifier.padding(top = 8.dp)) { MixedRow("b13_$id", Color(0xFFDCEDC8)) }
        }
    }
}

/** A row that is NOT itself clickable, so its children stay separate VoiceOver stops. */
@Composable
private fun MixedRow(id: String, colour: Color) {
    Row(
        Modifier.size(280.dp, 40.dp).background(colour),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Title $id", Modifier.size(110.dp, 20.dp))
        Text("Auth $id", Modifier.size(110.dp, 20.dp))
        Box(
            Modifier
                .testTag("${id}_mark")
                .size(60.dp, 40.dp)
                .background(Color(0xFF9E9E9E))
                .probe("${id}_mark")
                .clickable { ax("ORACLE ${id}_mark") },
        )
    }
}

@OptIn(AutographInternalApi::class)
private fun dumpEverything(view: UIView) {
    val scale = UIScreen.mainScreen.scale.toFloat()
    ax("=== TREE (scale=$scale) ===")
    dumpTree(view, view, scale, depth = 0)
    ax("=== VOICEOVER ===")
    dumpVoiceOverOrder(view, mutableListOf())
    ax("=== HITS ===")
    for ((tag, centre) in centres.entries.sortedBy { it.key }) {
        val path = deepestAccessibilityHitPath(view, view, AxPoint(centre.x, centre.y), scale)
        if (path == null) {
            ax("HIT $tag -> NO PATH")
            continue
        }
        val nearest = path.nearestAccessibilityClickable()
        ax("HIT $tag nearestClickable=${nearest?.describe()} path=${path.joinToString(" > ") { it.describe() }}")
        // The scope option B would have resolved: every reserved identifier on the path, outer→inner.
        val scopes = path.mapNotNull { it.accessibilityIdentifierOrNull() }.filter { it.startsWith(SCOPE) }
        ax("HIT $tag scopesOnPath=$scopes")
    }
    ax("=== END ===")
}

@OptIn(AutographInternalApi::class)
private fun dumpTree(node: Any, view: UIView, scale: Float, depth: Int) {
    if (depth > 12) return
    ax("TREE ${"  ".repeat(depth)}${node.describe()} frame=${node.accessibilityBoundsInWindowPx(view, scale)}")
    for (child in node.accessibilityChildren()) dumpTree(child, view, scale, depth + 1)
}

@OptIn(AutographInternalApi::class)
private fun Any.describe(): String {
    val id = accessibilityIdentifierOrNull()
    val kind = this::class.simpleName ?: "?"
    return "$kind(id=$id${if (isAccessibilityButton()) ",button" else ""})"
}

/**
 * VoiceOver's own traversal, modelled from the bridged hierarchy — deliberately **not**
 * [accessibilityChildren], which unions `accessibilityElements + subviews` for hit-testing and would
 * report an order VoiceOver never uses. UIKit's container rule is: descend `accessibilityElements`
 * when the node publishes any, otherwise `subviews`; a node claiming `isAccessibilityElement` is a
 * swipe stop and is not descended into.
 *
 * This is the one place in the repo that reads `accessibilityLabel`. Production never does, on
 * purpose — it cannot tell a developer-authored label from one Compose synthesized out of displayed
 * text, so reading it would defeat the "never capture displayed text" guarantee
 * ([accessibilityIdentifierOrNull]'s kdoc). Here the announced text *is* the measurement, and this
 * file is throwaway.
 */
private fun dumpVoiceOverOrder(node: Any, stops: MutableList<String>, depth: Int = 0) {
    if (depth > 24) return
    val obj = node as? NSObject
    if (obj?.isAccessibilityElement() == true) {
        val id = @OptIn(AutographInternalApi::class) node.accessibilityIdentifierOrNull()
        stops += "${stops.size}:$id"
        ax("VOICEOVER stop=${stops.size - 1} id=$id label=${obj.accessibilityLabel()}")
        return
    }
    val axElements = obj?.accessibilityElements()?.filterNotNull().orEmpty()
    val children = axElements.ifEmpty { (node as? UIView)?.subviews?.filterNotNull().orEmpty() }
    for (child in children) dumpVoiceOverOrder(child, stops, depth + 1)
}

/**
 * B6 — B4's control, and the fixture that decides whether the container is to blame. Identical
 * geometry to [B4WrapperWithOverhang]; the wrapper carries **no scope and no traversal group**, so it
 * is not published as an element and the badge hangs directly off the root's element as before.
 *
 * If `b6_badge` resolves and `b4_badge` does not, then wrapping an element in an option-B scope
 * turns a *reported* tap into a *dropped* one — which is not the "no scope on iOS" trade this library
 * already accepts, but a regression in the event itself, and the option cannot ship in that shape
 * without a change to the walk.
 */
@Composable
private fun B6OverhangWithoutWrapper() {
    Box(
        Modifier
            .padding(top = 48.dp)
            .size(280.dp, 24.dp)
            .background(Color(0xFFD1C4E9))
            .probe("b6_wrapper"),
    ) {
        Box(
            Modifier
                .testTag("b6_badge")
                .offset(y = 24.dp)
                .requiredSize(120.dp, 40.dp)
                .background(Color(0xFFF8BBD0))
                .probe("b6_badge")
                .clickable { ax("ORACLE b6_badge") },
        )
    }
}

/**
 * B7 — the fixture that decides whether the overhang drop is **option B's cost or a bug this library
 * already ships**. A bare `isTraversalGroup` wrapper with *no autograph marker of any kind*, i.e. a
 * plain host-app accessibility grouping, around the same overhanging badge.
 *
 * B2 already showed the container comes from `isTraversalGroup` alone and the reserved `testTag` only
 * supplies its identifier string — so if B7 drops too, the tap loss belongs to the shipped walk on
 * CMP 1.11+ and any host app grouping its own UI hits it today, with no autograph feature involved.
 */
@Composable
private fun B7PlainTraversalGroupOverhang() {
    Box(
        Modifier
            .padding(top = 48.dp)
            .semantics { isTraversalGroup = true }
            .size(280.dp, 24.dp)
            .background(Color(0xFFD1C4E9))
            .probe("b7_wrapper"),
    ) {
        Box(
            Modifier
                .testTag("b7_badge")
                .offset(y = 24.dp)
                .requiredSize(120.dp, 40.dp)
                .background(Color(0xFFF8BBD0))
                .probe("b7_badge")
                .clickable { ax("ORACLE b7_badge") },
        )
    }
}
