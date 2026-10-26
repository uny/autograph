package dev.ynagai.autograph.android

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.ref.WeakReference

/**
 * Declares scope [properties] for the taps captured **in this fragment only** — a detail screen's
 * item id, a list's variant — the native counterpart of `autograph-compose`'s `AutographScope`
 * ([#239](https://github.com/uny/autograph/issues/239)).
 *
 * A root `ScopeStack.push` cannot do this: it is app-wide by construction, so the property would land
 * on every other surface's taps too — a wrong value rather than a missing one. This puts the
 * properties on the frame [installAutographNativeScreenCapture] keeps for the fragment, so they reach
 * exactly the events that capture attributes to it:
 *
 * - taps the native tap capture resolves to a view inside the fragment, and taps in a Compose
 *   `AutographProvider` hosted inside it;
 * - surfaces nested inside it — a child fragment, the composition it hosts — whose own scope merges
 *   over it, the inner value winning a key clash, as nested `AutographScope`s do;
 * - its own `Screen Viewed`, when declared before the fragment resumes (in `onCreate`).
 *
 * Not a sibling fragment's taps, and not the taps on the Activity around it. Precedence is the same as
 * for `AutographScope`: above a [default property][dev.ynagai.autograph.DefaultProperties], below a
 * property the capture sets itself and the reserved `screen` / `section`. Explicit `Tracker.track`
 * calls are untouched — this feeds autocapture only.
 *
 * One read has no surface to go by: a tap on a window no surface claims — a `PopupWindow`, an
 * `AlertDialog` the app builds itself — is resolved from the ambient `ScopeStack.current()`, which
 * holds the scope of every resumed surface. Alone, a resumed fragment's scope reaches such a tap; two
 * scoped siblings resumed at once are ambiguous there and both drop. A screen name behaves the same
 * way. Show such a window as a `DialogFragment` to keep it to its own surface.
 *
 * ## Lifetime: the fragment's, not the call site's
 *
 * Call it once, from `onCreate`. The declaration lasts until the fragment is destroyed and covers
 * every view the fragment creates in between: a fragment whose view is destroyed and re-created
 * (`detach` / `attach`, the back stack) keeps its scope without being asked again. Calling it again for
 * the same fragment and [scopeStack] — from `onViewCreated` on every view, say — **replaces** the
 * properties of the existing declaration and returns its handle; it never stacks a second one. A
 * fragment that is already destroyed declares nothing.
 *
 * Use [AutographScopeHandle.update] to revise the properties later (an item id that arrives after
 * the data loads). The handle does not keep the fragment alive.
 *
 * ## It needs the native screen capture
 *
 * The scope lives on the capture's frame for the fragment, so it takes effect only while
 * [installAutographNativeScreenCapture] runs on the same [scopeStack]. Without it, nothing is
 * attributed to the fragment and the scope is simply absent — never app-wide. A capture installed
 * later picks the declaration up at the fragment's next resume.
 *
 * Values are strings, as the same shape is meant for iOS, where a Swift caller cannot build a
 * `JsonObject`. Main thread only, like the lifecycle it is bound to.
 */
public fun Fragment.autographScope(scopeStack: ScopeStack, properties: Map<String, String>): AutographScopeHandle =
    SurfaceScopes.declare(this, lifecycle, scopeStack, properties)

/**
 * Declares scope [properties] for the taps captured **in this Activity**: on its own views, and in
 * every fragment and Compose `AutographProvider` inside it, each of which may declare its own scope
 * on top (the inner value winning a key clash).
 *
 * The same contract as [Fragment.autographScope]: bound to the Activity's lifecycle (call it from
 * `onCreate`; it lasts until `onDestroy`), a second call replaces the properties, and it takes effect
 * only while [installAutographNativeScreenCapture] runs on the same [scopeStack]. A configuration
 * change creates a new Activity instance, whose `onCreate` declares again.
 */
public fun ComponentActivity.autographScope(scopeStack: ScopeStack, properties: Map<String, String>): AutographScopeHandle =
    SurfaceScopes.declare(this, lifecycle, scopeStack, properties)

/**
 * A scope declared with [Fragment.autographScope] or [ComponentActivity.autographScope]. Keep it to
 * revise the properties; dropping it changes nothing, since the declaration is bound to the surface's
 * lifecycle, not to this handle.
 */
public class AutographScopeHandle internal constructor(internal val declaration: SurfaceScopes.Declaration) {

    /**
     * Replaces the declared properties, in place: taps made from now on carry the new ones. Omitted
     * keys are dropped, not kept. A no-op once the surface has been destroyed.
     *
     * Callable from any thread, since the value it carries often arrives on a background one: off
     * the main thread the change is posted to it, in call order, and taps made before it runs carry
     * the previous properties.
     */
    public fun update(properties: Map<String, String>) {
        val scope = properties.toScope()
        if (Looper.myLooper() === Looper.getMainLooper()) {
            declaration.update(scope)
        } else {
            mainHandler.post { declaration.update(scope) }
        }
    }

    private companion object {
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    }
}

/**
 * The scopes surfaces have declared, and the captures that apply them. Main-thread-confined, like the
 * lifecycles the declarations are bound to and the captures reading them, so the plain collections
 * need no guard.
 *
 * Kept apart from any one capture on purpose: a fragment declares in `onCreate`, which can be before a
 * capture is installed, and a declaration is read again at every resume of the surface by whichever
 * capture runs on its stack.
 */
internal object SurfaceScopes {

    /** Weak keys: a declaration must never keep its Activity or Fragment alive. */
    private val declarations = java.util.WeakHashMap<Any, MutableList<Declaration>>()

    /** The running captures, told when a declaration changes so a live frame is revised at once. */
    private val listeners = ArrayList<(owner: Any, stack: ScopeStack) -> Unit>()

    class Declaration(owner: Any, val stack: ScopeStack, scope: JsonObject) {
        private val owner = WeakReference(owner)

        var scope: JsonObject = scope
            private set

        /** False once the surface is destroyed (or was when declared): [update] is then a no-op. */
        var live = true

        fun update(scope: JsonObject) {
            if (!live || scope == this.scope) return
            this.scope = scope
            owner.get()?.let { notifyChanged(it, stack) }
        }
    }

    fun declare(owner: Any, lifecycle: Lifecycle, stack: ScopeStack, properties: Map<String, String>): AutographScopeHandle {
        val scope = properties.toScope()
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            return AutographScopeHandle(Declaration(owner, stack, scope).also { it.live = false })
        }
        declarations[owner]?.firstOrNull { it.stack === stack }?.let { existing ->
            existing.update(scope)
            return AutographScopeHandle(existing)
        }
        val declaration = Declaration(owner, stack, scope)
        declarations.getOrPut(owner) { ArrayList(1) } += declaration
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event != Lifecycle.Event.ON_DESTROY) return
                source.lifecycle.removeObserver(this)
                declaration.live = false
                declarations[owner]?.let { list ->
                    list -= declaration
                    if (list.isEmpty()) declarations.remove(owner)
                }
                // Nothing to revise: the surface's frame goes with it.
            }
        })
        notifyChanged(owner, stack)
        return AutographScopeHandle(declaration)
    }

    /** What [owner] declared for [stack]; empty when nothing (or nothing live) was. */
    fun scopeOf(owner: Any, stack: ScopeStack): JsonObject =
        declarations[owner]?.firstOrNull { it.stack === stack && it.live }?.scope ?: EmptyScope

    fun addListener(listener: (owner: Any, stack: ScopeStack) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (owner: Any, stack: ScopeStack) -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged(owner: Any, stack: ScopeStack) {
        listeners.toList().forEach { it(owner, stack) }
    }

    /** For tests: whether anything is still declared for [owner]. */
    internal fun hasDeclarations(owner: Any): Boolean = declarations[owner]?.isNotEmpty() == true

    private val EmptyScope = JsonObject(emptyMap())
}

private fun Map<String, String>.toScope(): JsonObject = JsonObject(mapValues { (_, value) -> JsonPrimitive(value) })
