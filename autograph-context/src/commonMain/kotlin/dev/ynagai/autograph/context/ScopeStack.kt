package dev.ynagai.autograph.context

import dev.ynagai.autograph.EmptyJsonObject
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ambient scope + screen context of the currently visible UI, as a stack of [push]ed frames.
 *
 * This is the non-Compose home for what `AutographScope` and screen context express inside Compose
 * via `CompositionLocal`: a place that autocapture pipelines can read at the moment a tap or screen
 * view is observed, regardless of which UI framework produced it (Compose, UIKit/SwiftUI, or the
 * Android View system). Each surface pushes a frame when a screen or scope comes into view and
 * removes it when it leaves; the capture pipeline calls [current] and [AmbientContext.enrich] to
 * attribute the event.
 *
 * **Read this only from auto-capture code, not from explicit `track` calls.** The stack answers
 * "what was on screen when the user acted", which is the right question for an autocaptured tap but
 * the *wrong* one for an arbitrary `track(...)` — a background event (e.g. a network response that
 * completes after navigation) must not silently inherit whatever screen happens to be visible.
 * Explicit instrumentation keeps its own lexical scope; see the `autograph-compose` decorator.
 *
 * **Own an instance; don't share one globally.** Scope a [ScopeStack] to the tracker it feeds so
 * context can't leak across trackers or outlive a tracker swap on logout — [screenHistory] rides
 * along and inherits that scoping, which is what resets `previous_screen` when the tracker is
 * replaced. Sharing one instance across the *surfaces* of a single hybrid app is the exception, and
 * the point: pass it to `AutographProvider` so the Compose and native pipelines attribute against the
 * same context and share one `previous_screen` chain. That stack is then yours to replace when the
 * tracker is — the provider will not swap a caller-supplied stack out from under the native side.
 *
 * **Threading.** [push], [update], and [remove] must be called from the main thread ([push] and
 * [remove] mutate the frame list; [update] mutates a frame's contents). [current] is lock-free and
 * safe from any thread: it returns an immutable snapshot that is republished atomically on every
 * mutation, so a background reader always sees a whole, consistent context — never a half-applied
 * one.
 */
public class ScopeStack {

    /**
     * The screen history that travels with this stack.
     *
     * Carried here rather than handed over separately so that sharing one stack across a hybrid app's
     * surfaces — the sharing this class already exists to enable — is *all* it takes to keep
     * `previous_screen` continuous across a Compose↔native transition. A second object to thread
     * through would be one more thing to forget, and forgetting it would produce a silently
     * discontinuous `previous_screen` rather than an error.
     */
    public val screenHistory: ScreenHistory = ScreenHistory()

    // Insertion-ordered; "innermost wins" for screen/section means later frames override earlier.
    // For scope, precedence follows the frame LINEAGE (parent links) rather than insertion order —
    // see [resolveScope]. Mutated only from the main thread (see class kdoc), so a plain list needs
    // no guard of its own.
    private val frames = ArrayList<ScopeFrame>()

    @Volatile
    private var snapshot: AmbientContext = AmbientContext.Empty

    /**
     * Pushes a frame contributing [scope] properties (low precedence — an explicit call-site
     * property always wins over them) and/or a [screen]/[section] (reserved keys, high precedence).
     * Returns a [ScopeHandle] to [update] or [remove] it with. A frame may carry scope only (an
     * `AutographScope` analogue), screen only (a `TrackedScreen` analogue), or both.
     *
     * A frame that names a [screen] owns its [section]: pushing `screen = "X"` with no section means
     * "screen X, no section", and an inner screen that declares none does not inherit the section of
     * an outer one still on the stack. A frame with a [section] but no [screen] is a section-only
     * marker that refines the surrounding screen instead.
     *
     * [parent] declares this frame's enclosing frame, forming a lineage the *scope* merge follows:
     * an enclosing scope contributes to a nested one, but two frames that are neither's ancestor
     * (siblings mounted at once — a list's rows, split-pane, a sheet over content) are ambiguous, and
     * [current] then drops scope for them rather than guessing (see [resolveScope]). Pass the
     * [ScopeHandle] of the enclosing frame; `null` (the default) marks a root. Lineage is
     * framework-independent — a native surface declares it the same way — so this does not tie the
     * stack to Compose. It affects only scope; [screen]/[section] still resolve by insertion order.
     */
    public fun push(
        scope: JsonObject = EmptyJsonObject,
        screen: String? = null,
        section: String? = null,
        parent: ScopeHandle? = null,
    ): ScopeHandle {
        val frame = ScopeFrame(scope, screen, section, parent?.frame)
        frames.add(frame)
        snapshot = recompute()
        return ScopeHandle(frame)
    }

    /**
     * Replaces the contents of the frame [handle] refers to, in place — keeping its position, and
     * thus its precedence, in the stack. Use this instead of [remove] + [push] when a still-mounted
     * frame's [scope]/[screen]/[section]/[parent] changes: re-pushing would move the frame to the top
     * and let it wrongly override inner frames that are still on the stack. A no-op (and no snapshot
     * churn) if the contents are unchanged, the handle was already removed, or it belongs to another
     * stack.
     */
    public fun update(
        handle: ScopeHandle,
        scope: JsonObject = EmptyJsonObject,
        screen: String? = null,
        section: String? = null,
        parent: ScopeHandle? = null,
    ) {
        val frame = handle.frame
        if (frames.none { it === frame }) return
        val parentFrame = parent?.frame
        if (frame.scope == scope && frame.screen == screen && frame.section == section &&
            frame.parent === parentFrame
        ) {
            return
        }
        frame.scope = scope
        frame.screen = screen
        frame.section = section
        // Revised in place, like the other fields: a frame moved to a new lineage (e.g. a
        // `movableContentOf` subtree relocated under a different parent) must pick up its new parent
        // without being removed and re-pushed — re-pushing would change its identity and orphan any
        // frames still pointing to it as *their* parent. See [resolveScope] for how the link is used.
        frame.parent = parentFrame
        snapshot = recompute()
    }

    /**
     * Removes the frame [handle] refers to, by identity and independent of position — screen
     * transitions (a Compose `Crossfade`, an iOS interactive-pop that the user cancels) do not
     * guarantee frames leave in push order, so a positional pop would remove the wrong one.
     * Removing a handle that was already removed, or one from another stack, is a no-op.
     */
    public fun remove(handle: ScopeHandle) {
        if (frames.remove(handle.frame)) {
            snapshot = recompute()
        }
    }

    /** The current merged ambient context. Lock-free; safe from any thread. */
    public fun current(): AmbientContext = snapshot

    private fun recompute(): AmbientContext {
        if (frames.isEmpty()) return AmbientContext.Empty
        var screen: String? = null
        var section: String? = null
        for (frame in frames) {
            // A frame that names a screen OWNS its section — it replaces both, so a section carried by
            // an outer screen cannot bleed onto an inner one that declared none (`push(screen = "X")`
            // means "screen X, no section", not "keep whatever section was showing"). A frame with no
            // screen is a section-only marker that refines the surrounding screen's context, so it
            // still updates section alone. This keeps screen and section composing independently in the
            // marker case while stopping the cross-screen leak in the replacement case. Screen/section
            // resolve by insertion order (one screen is active at a time, so "last mounted wins" is
            // right for them); only scope is lineage-aware — see [resolveScope].
            if (frame.screen != null) {
                screen = frame.screen
                section = frame.section
            } else if (frame.section != null) {
                section = frame.section
            }
        }
        return AmbientContext(resolveScope(), screen, section)
    }

    /**
     * The merged scope, resolved along the frame lineage rather than by insertion order.
     *
     * Scope-bearing frames attribute a captured tap unambiguously only when they lie on a single
     * ancestor chain (a route and the refinements nested inside it): then they merge outer→inner, the
     * inner frame winning a key clash, exactly as nested `AutographScope`s compose. When two
     * scope-bearing frames are mounted at once with neither enclosing the other — a list's rows each
     * in their own scope, split-pane content, a sheet or dialog over the screen beneath — the stack
     * cannot tell which subtree the tap landed in. It then drops scope entirely rather than pinning
     * the tap to an arbitrary one: a *wrong* scope is worse than *no* scope and is irreversible in
     * analytics data (#66). Resolving which sibling a tap actually hit needs the tap position, which
     * this framework-independent stack does not have; that is a separate, additive layer (#68).
     */
    private fun resolveScope(): JsonObject {
        val scoped = frames.filter { it.scope.isNotEmpty() }
        if (scoped.isEmpty()) return EmptyJsonObject
        if (scoped.size == 1) return scoped[0].scope
        // A single chain has one frame that is a descendant of every other scoped frame (its ancestor
        // chain contains them all). If none qualifies, the scoped frames branch — ambiguous, drop.
        val single = scoped.any { candidate ->
            val ancestry = generateSequence(candidate) { it.parent }.toHashSet()
            scoped.all { it in ancestry }
        }
        if (!single) return EmptyJsonObject
        // Merge outer→inner (ascending depth) so the deeper frame wins a shared key. Depths are
        // distinct here: a chain strictly increases in depth, which is what `single` just established.
        return scoped.sortedBy { it.depth() }
            .fold(EmptyJsonObject) { acc, frame ->
                if (acc.isEmpty()) frame.scope else JsonObject(acc + frame.scope)
            }
    }

    private fun ScopeFrame.depth(): Int {
        var depth = 0
        var ancestor = parent
        while (ancestor != null) {
            depth++
            ancestor = ancestor.parent
        }
        return depth
    }
}

/**
 * One pushed contribution to a [ScopeStack]. Opaque to callers; they hold a [ScopeHandle].
 *
 * Contents are mutable so [ScopeStack.update] can revise a frame without moving it (see there);
 * mutated only from the main thread, and every mutation republishes an immutable [AmbientContext].
 * [parent] records the frame's enclosing frame for the scope-lineage resolution in
 * [ScopeStack.resolveScope]; it is revisable (a frame can be reparented in place — see
 * [ScopeStack.update]) rather than fixed, so a relocated subtree does not keep a stale lineage.
 */
internal class ScopeFrame(
    var scope: JsonObject,
    var screen: String?,
    var section: String?,
    var parent: ScopeFrame? = null,
)

/** An opaque token identifying a pushed frame, for [ScopeStack.update] and [ScopeStack.remove]. */
public class ScopeHandle internal constructor(internal val frame: ScopeFrame)

/**
 * An immutable snapshot of a [ScopeStack]: the accumulated [scope] defaults plus the effective
 * [screen] / [section]. Use [enrich] to apply it to an event's properties.
 */
public class AmbientContext internal constructor(
    public val scope: JsonObject,
    public val screen: String?,
    public val section: String?,
) {
    /**
     * Returns [properties] enriched with this context: [scope] merged underneath (so an explicit
     * property in [properties] wins over a scope default), then [screen] / [section] written on top
     * under those reserved keys (overwriting any same-named entry) — the same precedence the Compose
     * path applies via its scope decorator and `withScreenContext`.
     */
    public fun enrich(properties: JsonObject): JsonObject {
        var result = if (scope.isEmpty()) properties else JsonObject(scope + properties)
        if (screen != null) result = JsonObject(result + ("screen" to JsonPrimitive(screen)))
        if (section != null) result = JsonObject(result + ("section" to JsonPrimitive(section)))
        return result
    }

    internal companion object {
        val Empty: AmbientContext = AmbientContext(EmptyJsonObject, null, null)
    }
}
