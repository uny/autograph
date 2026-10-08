@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.context.autographScopeOwner
import dev.ynagai.autograph.context.notifyAutographScopeOwnerChanged
import dev.ynagai.autograph.context.emitScreenView
import dev.ynagai.autograph.context.continueScreenVisit
import dev.ynagai.autograph.context.endScreenVisit
import dev.ynagai.autograph.context.screenViewIdOf

/**
 * The engine behind [installAutographNativeScreenCapture]: an [Application.ActivityLifecycleCallbacks]
 * that also installs a recursive [FragmentManager.FragmentLifecycleCallbacks] on each Activity, and
 * keeps one [ScopeStack] frame per surface (Activity or Fragment) describing what that surface says it
 * is showing. Main-thread-confined (see the install kdoc), so the plain maps need no guard.
 *
 * ## Two questions, two mechanisms
 *
 * A surface asks the stack for two different things and they are kept apart on purpose, because the
 * signals that answer them are different:
 *
 * - **"Is this surface the one on display?"** — the frame's *active* bit ([ScopeStack.setActive]).
 *   Derived from the surface's own resume/pause, which is the only signal that is reliable: a host
 *   that keeps several surfaces mounted and merely demotes the off-screen ones (a `ViewPager2`
 *   caching neighbouring pages) leaves every demoted frame in place, and position — which stands in
 *   for "most recently foregrounded" — then names the page the user just left. Deactivating instead
 *   of removing keeps the frame's position, so a surface can come back without being rebuilt.
 * - **"Has a `Screen Viewed` already been reported for what is on display now?"** — [SurfaceState.emitted].
 *   A view of a screen survives a pause (a dialog, a permission prompt, a partially-covering
 *   Activity), so this is *not* the active bit: it is cleared only when the surface stops, or when it
 *   is demoted *within a host that is itself still resumed* — the one pause that means "the user
 *   moved to another surface of this host".
 *
 * Folding the two together is what the previous design did, via a `screenHistory.lastScreen == name`
 * test at resume, and it misfired whenever several surfaces are `RESUMED` at once (`add` on top,
 * `show()`/`hide()`, a child fragment resuming before its parent): a plain pause/resume of the host
 * re-emitted for all of them.
 *
 * ## Whose event is it
 *
 * Every frame this capture reserves is a boundary ([ScopeStack.pushSurface]) nested under the frame
 * of the surface containing it — a fragment under its parent fragment or its Activity — and each
 * surface's root view is claimed with that frame ([autographScopeOwner]). That is what lets what a
 * surface says reach *its own* events and no others: the native tap capture resolves a tap from the
 * nearest claimed ancestor of the tapped view, and the Compose provider nests its composition's root
 * frame under the surface hosting it, so a mask raised by an unnamed fragment added straight into a
 * named Activity blanks the taps inside that fragment and leaves the Activity's own taps naming the
 * Activity (#216 S2). Insertion order alone had the mask, being later, win for both.
 */
internal class AndroidScreenCapture(
    private val tracker: Tracker,
    private val scopeStack: ScopeStack,
    private val activityScreenName: (Activity) -> String?,
    private val fragmentScreenName: (Fragment) -> String?,
) : Application.ActivityLifecycleCallbacks {

    // active guard: any callback still in flight after tearDown() no-ops, belt-and-suspenders with the
    // explicit unregisters.
    private var active = true

    // Surface bookkeeping is keyed by screen identity, with weak keys so a surface destroyed without
    // its teardown callback (should not happen, but cheap insurance) cannot pin the Activity/Fragment.
    // The registration map cannot be weak — its value holds the FragmentManager, which pins the
    // Activity through its host — so it is a plain map cleared explicitly in onActivityDestroyed and
    // tearDown (both reliable short of process death, which frees everything regardless).
    private val activityStates = java.util.WeakHashMap<Activity, SurfaceState>()
    private val fragmentRegistrations = HashMap<Activity, FragmentRegistration>()

    /**
     * The Activities currently between `onActivityResumed` and `onActivityPaused`.
     *
     * This is the discriminator that tells an in-app demotion from a host-wide interruption at the
     * moment `onFragmentPaused` arrives, and it works because `onActivityPaused` is dispatched
     * **before** `mFragments.dispatchPause()` — measured: a permission prompt produces
     * `ACT paused` → `FRG paused`, while a pager swipe produces `FRG paused` alone.
     */
    private val resumedActivities: MutableSet<Activity> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /**
     * The views a configuration change interrupted, by the token of the Activity they were on display
     * in ([SurfaceState.identity]) — the same token for every instance a chain of re-creations
     * produces, which is what keeps one that re-rotates before it resumes. See [HostCarries].
     */
    private val carries = HashMap<String, HostCarries>()

    private val handler = Handler(Looper.getMainLooper())

    /**
     * What this capture knows about one surface. The [handle] is reserved for the life of one
     * **mounting** and replaced by [endMounting] when the next begins, so never cache it — the frame
     * a stale handle points at is gone, and `ScopeStack` no-ops on it in silence. Everything else is
     * state that decides what the frame says and whether it takes part.
     */
    private class SurfaceState(var handle: ScopeHandle) {
        /**
         * The frame of the surface containing this one, as last read — what a fresh frame reserved
         * by [endMounting] is nested under until `onFragmentViewCreated` re-reads it.
         */
        var parent: ScopeHandle? = null

        /**
         * Whether what this frame says has been settled for the current **mounting**.
         *
         * Settled at the mounting's first resume, and with it whether the frame masks — the mask
         * lives on the frame, so it is set only here. A fragment revisits it when its view is
         * destroyed and a fresh frame is reserved; an Activity, which never replaces its frame,
         * revisits it at every stop instead. Re-deciding on every resume looks harmless and is not:
         * the inputs are time-varying (`isCapturableActivity` asks whether any added fragment has a
         * view; `isCapturableFragment` walks the live view subtree), so a plain Activity that merely
         * pauses and resumes while it hosts a content fragment would read as a shell and mask — and
         * removing the fragment resumes nothing, so the mask would stand until the Activity's next
         * resume. Measured when the mask was still one-way, with a view-bearing dialog fragment
         * before those stopped counting: the Activity reported `screen = null` for the rest of its
         * life while continuing to emit its own name.
         */
        var decided = false

        /** What [decided] settled: whether this capture treats the surface as a screen of its own. */
        var capturable = false

        /** Our belief about the frame's active bit, so a no-op toggle costs no snapshot. */
        var selected = false

        /** A `Screen Viewed` stands for the view of this screen that is currently in progress. */
        var emitted = false

        /** The screen that view is of, while [emitted]: what a configuration change carries over. */
        var viewScreen: String? = null

        /**
         * What the instance re-creating this surface after a configuration change shares with it,
         * read once when the surface is first seen: a fragment's `mWho`, which `FragmentState`
         * restores into the re-created instance, or the token this capture saves in an Activity's
         * instance state. Null when it could not be read, and then nothing is carried for it.
         */
        var identity: String? = null

        /**
         * A fragment the app did not build: one a re-creation restored from saved state, or a retained
         * instance, which comes back without being created again. Only one it built — created with no
         * saved state — can stand in for an owner removed unresumed ([Orphan]).
         */
        var restored = true

        /** An Activity's carries for surfaces that were never restored have been dropped. See [scheduleUnrestoredCarryDrop]. */
        var unrestoredDropped = false

        /**
         * This surface paused because its **host Activity** was interrupted, not because the host
         * moved to a different surface. Resolved after the host's next resume dispatch — see
         * [FragmentCallbacks.confirmDemotions].
         */
        var pausedWithHost = false

        /**
         * The screen an Activity did not report on this resume because a fragment was expected to
         * report the return instead — checked, and cleared, by [scheduleDemotionCheck].
         */
        var yieldedReturn: String? = null
    }

    private class FragmentRegistration(
        val fragmentManager: FragmentManager,
        val callbacks: FragmentCallbacks,
    )

    /**
     * One view of [screen] a configuration change interrupted (#242), waiting for the surface
     * re-created into it: that surface reports no second `Screen Viewed` and continues the visit
     * [screenViewId] (null when none was started), so its events stay tied to the view before it.
     */
    private class Carry(val screenViewId: String?, val screen: String)

    /**
     * What one Activity's re-creation chain has to hand over. Each carry belongs to exactly one
     * re-created surface, matched by [SurfaceState.identity] — never by class, container or tag, which
     * two live instances can share, and a shared key is how a carry once landed on an unrelated
     * fragment. Every carry ends at that surface's resume, or when its view is known to be over: the
     * re-created host stopping or finishing, another screen's view reported while the host is off
     * display, the owner stopping without resuming, the owner never being restored at all. There is no
     * lifecycle point that marks the end of a re-creation, so nothing here expires on time — a
     * restored pager page can resume well after its host does.
     */
    private class HostCarries {
        var activity: Carry? = null
        val fragments = HashMap<String, Carry>()

        /**
         * Carries whose restored owner was removed before it resumed — an app that rebuilds its
         * content in `onCreate`/`onResume` replaces it with a fresh instance. See [Orphan].
         */
        val orphans = ArrayList<Orphan>()

        /**
         * The re-created host has not finished its first resume dispatch: the only time a restored
         * owner removed unresumed can have a replacement standing in for it. A removal after it is
         * the app moving on — a navigation that happens to replace an owner still held unresumed —
         * and ends the owner's view instead.
         */
        var restoring = true

        fun isEmpty() = activity == null && fragments.isEmpty() && orphans.isEmpty()
    }

    /**
     * A carry whose owner went away unresumed. A fresh fragment of the same class under the same
     * parent that resumes before the posted deadline stands for that interrupted view and reports no
     * second `Screen Viewed` — but it is **not** given the visit: nothing proves it is the replacement
     * rather than an unrelated fragment added in the same turn, and a visit id is what a consumer joins
     * on, so no id beats a wrong one.
     */
    private class Orphan(val fragmentClass: Class<*>, val parent: Fragment?, val carry: Carry)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!active) return
        // Dispatched from inside Activity.onCreate — so from super.onCreate(), before the subclass can
        // call setContentView or add a fragment. That is what puts this frame BELOW everything the
        // Activity's own content pushes, which is the whole reason positions are reserved this early
        // rather than at resume. See reserveFrame.
        startTracking(activity, savedInstanceState)
    }

    /**
     * Begins tracking [activity]: reserves its frame and installs the recursive fragment callbacks.
     * A no-op if it is already tracked. Idempotent because it runs from two places — see the call in
     * [onActivityResumed].
     */
    private fun startTracking(activity: Activity, savedInstanceState: Bundle? = null) {
        if (activityStates.containsKey(activity)) return
        val state = newSurface(parent = null)
        // The token the instance this one re-creates saved, so the two are told apart from every other
        // instance of the class (#242). A fresh one otherwise: one that already existed at install time
        // has no saved token, and its own re-creation will carry this one.
        state.identity = savedInstanceState?.getString(IDENTITY_KEY) ?: java.util.UUID.randomUUID().toString()
        activityStates[activity] = state
        if (activity is FragmentActivity) {
            val fragmentManager = activity.supportFragmentManager
            val callbacks = FragmentCallbacks(host = state.handle, hostIdentity = state.identity!!)
            // recursive = true so a NavHostFragment's / ViewPager2's child FragmentManager is covered.
            fragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
            fragmentRegistrations[activity] = FragmentRegistration(fragmentManager, callbacks)
            // Outermost-first, so an Activity adopted late still gets parent-below-child ordering.
            // Reserving them lazily at resume instead inverts it, because a child resumes inside its
            // parent's performResume — measured, a late install reported the PARENT of the pair.
            callbacks.adoptAttached(fragmentManager)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (!active) return
        resumedActivities += activity
        // Not only onActivityCreated: an Activity that already existed when this capture was installed
        // never saw that callback, and gating on it made such an Activity invisible for the whole of
        // its remaining life — measured, no frame and no Screen Viewed across any number of resumes,
        // where the documented contract (and the previous implementation) is that it is picked up at
        // its next transition. Lazy tracking costs it only the early frame position, which is the
        // right trade: a late position is a worse mask, no position at all is no capture.
        startTracking(activity)
        val state = activityStates[activity] ?: return
        // The DECOR, not android.R.id.content: an AppCompat window action bar sits outside content,
        // and a Toolbar menu tap is about the most common native tap a fragment-shell app has. The
        // tap capture resolves from `peekDecorView()`, so the claim goes on the same root. Claimed at
        // resume, not at create — this callback runs inside super.onCreate, before any content
        // exists — and `peekDecorView` never forces the decor into existence. No tap can arrive
        // before the first resume, and the Compose provider looks its host up later still.
        // Idempotent: an Activity's frame never changes.
        activity.decorView()?.let { claim(it, state) }
        onSurfaceResumed(
            state = state,
            takeCarry = { carries[state.identity]?.let { host -> host.activity.also { host.activity = null } } },
            capturable = isCapturableActivity(activity),
            // An Activity covers its window — but only while it is the one Activity on display. Under
            // multi-resume (Android 10+ multi-window, split screen) two Activities are RESUMED at
            // once in two windows, and an excluded one masking then blanks the screen of a *named*
            // Activity beside it — measured, `PlainActivity` became null. Neither position nor this
            // stack knows about windows, so an Activity that is not alone simply does not mask: it
            // resolves as it did before masks existed.
            covers = resumedActivities.none { it !== activity },
            screenName = { activityScreenName(activity) },
            yieldsToFragment = fragmentRegistrations[activity]?.callbacks?.hasReturningContent() == true,
        )
        scheduleDemotionCheck(activity)
        scheduleUnrestoredCarryDrop(activity, state)
    }

    override fun onActivityPaused(activity: Activity) {
        if (!active) return
        resumedActivities -= activity
        // Deselect, but never clear `emitted`: a pause is not the end of a view. A dialog Activity, a
        // permission prompt or a partially-covering Activity all pause the one underneath, and coming
        // back must not report the same screen twice. Stop is what ends a view.
        activityStates[activity]?.let {
            // A return left to a fragment is settled at the resume that left it, or not at all: the
            // next resume decides again, under the name it reads then.
            it.yieldedReturn = null
            deselect(it)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (!active) return
        activityStates[activity]?.let {
            if (activity.isChangingConfigurations) {
                // Kept, not overwritten, when this is a re-created instance rotating again before its
                // first resume: it has no view in progress of its own, and the carry it inherited is
                // still the one its own re-creation stands for.
                val identity = it.identity
                val carry = carryOf(it)
                if (identity != null && carry != null) carriesFor(identity).activity = carry
                identity?.let(carries::get)?.restoring = true
            } else {
                // The re-created host's view ended without a configuration change: whatever it still
                // holds for surfaces that never resumed is over with it. Most of them stop with it and
                // drop their own; one held at CREATED does not stop, and would otherwise take a view
                // that has ended into its eventual resume.
                it.identity?.let(carries::remove)
            }
            it.emitted = false
            // An Activity's frame keeps its position for life — its content view does, and its
            // fragments' frames sit above it and survive this stop, so replacing it here would jump
            // it over them. What it SAYS still has to be re-derived, though: an Activity that owned
            // its content at its first resume and has since become a fragment shell went on emitting
            // its own name on every foreground return — measured, two spurious events per return.
            // The mask is re-decided with the rest at the next resume; until then it stays on the
            // frame, which is inactive anyway.
            it.decided = false
            it.capturable = false
            deselect(it)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (!active) return
        resumedActivities -= activity
        activityStates.remove(activity)?.let {
            // Finished, not re-created: nothing will claim what it holds.
            if (!activity.isChangingConfigurations) it.identity?.let(carries::remove)
            release(it, activity.decorView())
        }
        fragmentRegistrations.remove(activity)?.let {
            // Releasing here is load-bearing; the order relative to the unregister below is not (the
            // states are a field of the callbacks object, which unregistering does not touch). This
            // callback is dispatched from Activity.onDestroy(), which FragmentActivity calls via
            // super.onDestroy() *before* mFragments.dispatchDestroy() — so onFragmentDetached never
            // fires for the fragments still attached here, and their frames would otherwise stay on
            // the ScopeStack for the life of the process: one per fragment per rotation, each walked
            // by recompute() on every push.
            it.callbacks.releaseFrames()
            it.fragmentManager.unregisterFragmentLifecycleCallbacks(it.callbacks)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        if (!active) return
        // An Activity's instance state is always kept when it is re-created (it holds the window's
        // view state), so this token reaches the re-created instance's onActivityCreated — measured,
        // across two rotations in a row. After process death it comes back too, but nothing in this
        // process waits for it then, so it matches nothing.
        activityStates[activity]?.identity?.let { outState.putString(IDENTITY_KEY, it) }
    }

    /** The view [state] has in progress, as what a configuration change carries over; null when none is. */
    private fun carryOf(state: SurfaceState): Carry? {
        if (!state.emitted) return null
        val screen = state.viewScreen ?: return null
        return Carry(scopeStack.screenViewIdOf(state.handle), screen)
    }

    private fun carriesFor(hostIdentity: String): HostCarries = carries.getOrPut(hostIdentity) { HostCarries() }

    /**
     * Drops, once per Activity instance and after its first resume dispatch, what it holds for
     * fragments it never restored. Every fragment a re-created host restores is attached during its
     * `super.onCreate` — before that resume — so one that is still unseen then never will be: it was
     * not saved (a commit after the state was saved), and a carry left for it would wait for nothing.
     * A restored one that has not resumed yet keeps its carry — a pager page can resume after this.
     */
    private fun scheduleUnrestoredCarryDrop(activity: Activity, state: SurfaceState) {
        if (state.unrestoredDropped) return
        state.unrestoredDropped = true
        val identity = state.identity ?: return
        val callbacks = fragmentRegistrations[activity]?.callbacks
        handler.post {
            if (!active) return@post
            val host = carries[identity] ?: return@post
            host.restoring = false
            if (callbacks == null) host.fragments.clear() else callbacks.dropUnrestored(host)
            if (host.isEmpty()) carries.remove(identity)
        }
    }

    /**
     * Posts the one piece of bookkeeping that cannot be decided from a callback: which of the
     * fragments that paused with [activity] were **not** brought back by its resume.
     *
     * A host can change which of its surfaces is selected **while it is paused** — a `ViewPager2` page
     * swap behind a permission prompt — and that produces *no Fragment callback at all*: both pages
     * are already `STARTED`, so neither pauses nor resumes. Measured. The demoted page is therefore
     * still holding a `Screen Viewed` that no longer describes anything, and returning to it later
     * would be silent.
     *
     * The final state is only readable once the host's resume dispatch has finished — mid-dispatch, a
     * sibling that is about to resume still reads `isResumed == false` (measured), so sweeping from
     * inside `onFragmentResumed` would wrongly demote every co-resumed surface. `onActivityPostResumed`
     * would be the exact hook but is API 29+ and this module's floor is 24, so the check is posted to
     * the main looper, which measurably runs after the whole dispatch.
     *
     * The same missing callback breaks the one expectation the host's resume acts on: an Activity
     * that left its return to a fragment that paused with it ([FragmentCallbacks.hasReturningContent])
     * is wrong when that fragment was the one moved down, and nothing would report the return. So
     * that return is reported here, late, when no such fragment resumed after all — the only emit
     * this check makes (#272). Attribution is never deferred.
     *
     * A host that paused again before this ran is skipped whole, and its next resume posts the check
     * again. Its surfaces paused with it a second time, so "not resumed now" no longer means "not
     * brought back", and clearing their paused-with-host state here would take the next return from
     * a fragment that is coming back with it.
     */
    private fun scheduleDemotionCheck(activity: Activity) {
        val callbacks = fragmentRegistrations[activity]?.callbacks ?: return
        handler.post {
            if (!active || activity !in resumedActivities) return@post
            activityStates[activity]?.let { state ->
                val screen = state.yieldedReturn ?: return@let
                state.yieldedReturn = null
                if (!state.emitted && !callbacks.hasResumedContent()) emit(state, screen)
            }
            callbacks.confirmDemotions()
        }
    }

    /**
     * [host] is the frame of the Activity these callbacks belong to — the root of its fragments'
     * lineage — and [hostIdentity] its [SurfaceState.identity], which its fragments' carries are filed under.
     */
    private inner class FragmentCallbacks(
        private val host: ScopeHandle,
        private val hostIdentity: String,
    ) : FragmentManager.FragmentLifecycleCallbacks() {
        /**
         * This Activity's fragment surfaces.
         *
         * Per-Activity rather than one map on the outer class, and deliberately: these are the frames
         * that must be released when the host Activity is destroyed, and the outer class cannot
         * enumerate that Activity's fragments at that moment (one removed with `addToBackStack` is no
         * longer in `fm.fragments`). One map per Activity's callbacks is complete by construction.
         */
        private val fragmentStates = java.util.WeakHashMap<Fragment, SurfaceState>()

        /**
         * Reserves frames for fragments that attached before these callbacks were registered.
         * Depth-first and parent-before-child, which is the order [reserveFrame] needs.
         */
        fun adoptAttached(fm: FragmentManager) {
            for (fragment in fm.fragments) {
                // `fm.fragments` can hold one that is added but not yet attached, and reading its
                // childFragmentManager then throws. It has not missed anything either — its own
                // onFragmentPreAttached is still to come — so there is nothing to adopt.
                if (fragment == null || !fragment.isAdded) continue
                val state = fragmentStates.getOrPut(fragment) { newFragmentSurface(fragment) }
                // A view that exists at install time is claimed here, since its viewCreated is past.
                fragment.view?.let { claim(it, state) }
                adoptAttached(fragment.childFragmentManager)
            }
        }

        /** Drops every frame this Activity's fragments own. Called before the callbacks unregister. */
        fun releaseFrames() {
            fragmentStates.forEach { (fragment, state) -> release(state, fragment.view) }
            fragmentStates.clear()
        }

        /** Ends the in-progress view of every fragment of this Activity — see [endViewsOffDisplay]. */
        fun endViews() {
            fragmentStates.values.forEach { it.emitted = false }
        }

        /**
         * Whether a fragment that is this Activity's own content is coming back with it — so the
         * return is that fragment's to report, not the Activity's
         * ([#272](https://github.com/uny/autograph/issues/272)).
         *
         * Read from the host's resume, which runs before any of its fragments resume, so it cannot
         * ask what is resumed; [SurfaceState.pausedWithHost] is the exact answer to "was on display
         * when the host left", and nothing has cleared it yet. The rest mirrors what makes
         * [isCapturableActivity] call the host a shell, restricted to surfaces this capture would
         * report: a shown dialog covers the Activity without replacing it, a hidden fragment shows
         * nothing, and an excluded one (a Compose mini-player) reports nothing, so none of those
         * takes the return away from the Activity. The name is deliberately not asked: an opted-out
         * fragment still replaces the Activity's content, and a return through a stop — which
         * re-derives the Activity as a shell — reports nothing over it either.
         */
        fun hasReturningContent(): Boolean =
            fragmentStates.any { (f, state) -> state.pausedWithHost && isContent(f, state) }

        /**
         * Whether the expectation [hasReturningContent] gave has held once the host's resume dispatch
         * is done. It can fail: a fragment moved down to `STARTED` while its host was paused gets no
         * callback at all, so it still reads as paused-with-host and never resumes to report anything.
         */
        fun hasResumedContent(): Boolean =
            fragmentStates.any { (f, state) -> f.isResumed && isContent(f, state) }

        // Not only the fragment itself: one nested in a shown dialog is part of that dialog's window.
        private fun isContent(f: Fragment, state: SurfaceState): Boolean =
            state.decided && state.capturable && f.view != null && !f.isHidden &&
                generateSequence(f) { it.parentFragment }.none { it.isShownAsDialog() }

        /** See [scheduleDemotionCheck]. Runs after the host's resume dispatch has finished. */
        fun confirmDemotions() {
            for ((fragment, state) in fragmentStates) {
                if (!state.pausedWithHost) continue
                state.pausedWithHost = false
                // Still not resumed now that the host's whole dispatch is done: the host came back to
                // a different surface, so this surface's view really did end. Clear the emit record so
                // returning to it reports a fresh `Screen Viewed`. It is already deselected (its own
                // pause did that), so attribution needs nothing here.
                if (!fragment.isResumed) state.emitted = false
            }
        }

        override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (!active) return
            // `parentFragment` is already set here — measured — so the lineage goes in with the
            // position, and the parent has its own frame because it pre-attached before its child.
            fragmentStates[f] = newFragmentSurface(f)
        }

        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            if (!active) return
            fragmentStates[f]?.restored = savedInstanceState != null
        }

        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (!active) return
            val state = fragmentStates[f] ?: return
            // The frame's parent is re-read here rather than trusted from reservation, because a
            // re-created view hierarchy re-reserves frames child-first: a child's view is destroyed
            // (and its fresh frame reserved) inside its parent's performDestroyView, *before* the
            // parent's own — so the child's fresh frame was linked to a parent frame that is about
            // to be replaced. Views come back parent-first, so by the time the child reaches here its
            // parent holds the frame it will keep.
            state.parent = parentOf(f)
            scopeStack.reparent(state.handle, state.parent)
            claim(v, state)
        }

        /** The frame of the surface containing [f]: its parent fragment's, or the host Activity's. */
        private fun parentOf(f: Fragment): ScopeHandle =
            f.parentFragment?.let { fragmentStates[it]?.handle } ?: host

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (!active) return
            // getOrPut, not a lookup: these callbacks are registered when their Activity is tracked,
            // which for an Activity that already existed at install time is at its resume — after its
            // fragments have attached, so onFragmentPreAttached will never fire for them. Requiring a
            // reservation made a fragment attached before that point invisible for its whole life,
            // while its host went on masking over it. It costs only a late position, which is what a
            // late install costs everywhere else too.
            val state = fragmentStates.getOrPut(f) {
                newFragmentSurface(f).also { late -> f.view?.let { claim(it, late) } }
            }
            state.pausedWithHost = false
            onSurfaceResumed(
                state = state,
                takeCarry = { takeCarry(f, state) },
                capturable = isCapturableFragment(f),
                // Two things must hold before an unnamed surface may blank the screen underneath.
                //  - It has a view. A headless / retained worker fragment (Glide's
                //    SupportRequestManagerFragment and its kind) is not a surface at all: it attaches
                //    on top of whatever is showing and resumes, and masking there would blank a screen
                //    that is on display and names itself perfectly well.
                //  - It covers its host. A fragment nested inside one that names a screen is a section
                //    of that screen, not a replacement for it. A DialogFragment is exempt: it draws
                //    its own window over everything, so it covers its host no matter whose
                //    FragmentManager it was shown through — measured, `show(parent.childFragmentManager)`
                //    otherwise left the dialog reporting its parent's screen. Only when it is actually
                //    being shown as a dialog, though: `setShowsDialog(false)` is the documented way to
                //    reuse one class as an inline piece of another screen, and that one draws no
                //    window of its own and covers nothing.
                covers = f.view != null && (f.isShownAsDialog() || !hasNamedAncestor(f)),
                screenName = { fragmentScreenName(f) },
            )
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (!active) return
            val state = fragmentStates[f] ?: return
            val host = f.activity
            if (host != null && host in resumedActivities) {
                // The host is still on display, so this surface lost the host's selection: a pager page
                // moved down to STARTED, a fragment detached, a `hide()`. Its view of that screen has
                // ended even though it never stopped, and coming back is a new view.
                state.emitted = false
            } else {
                // The host itself is going away (a permission prompt, another Activity). Whether that
                // ends this surface's view cannot be told yet — see confirmDemotions.
                state.pausedWithHost = true
            }
            deselect(state)
        }

        override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
            if (!active) return
            // The host Activity is the leaving instance here, so its flag reports the rotation. Only a
            // fragment whose view was in progress is re-created INTO that view: a pager page held at
            // STARTED never resumed, so its first resume after the rotation is its first view, and a
            // carry left for it would swallow that Screen Viewed.
            fragmentStates[f]?.let {
                if (f.activity?.isChangingConfigurations == true) {
                    // Kept rather than overwritten by a restored instance rotating again before its
                    // first resume, which has no view in progress of its own.
                    val identity = it.identity
                    val carry = carryOf(it)
                    if (identity != null && carry != null) carriesFor(hostIdentity).fragments[identity] = carry
                } else {
                    // Still holding a carry, this is a restored instance leaving before it resumed.
                    // Removed, it may have a replacement standing for the view it was restored into;
                    // otherwise that view is over.
                    leftUnresumed(f, it)
                }
                it.emitted = false
                it.pausedWithHost = false
                deselect(it)
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (!active) return
            // THE view is what a mounting is, so this — not the stop before it — is where one ends.
            fragmentStates[f]?.let(this@AndroidScreenCapture::endMounting)
        }

        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
            if (!active) return
            fragmentStates.remove(f)?.let {
                // A restored instance removed before it even started never stops — measured, a
                // fragment replaced in its host's onCreate goes straight from created to detached.
                leftUnresumed(f, it)
                release(it, f.view)
            }
        }

        private fun newFragmentSurface(f: Fragment): SurfaceState =
            newSurface(parentOf(f)).also { it.identity = identityOf(f) }

        /**
         * What a restored instance waits for, if it is the re-creation of a view a configuration
         * change interrupted: its own carry, matched by identity; else, while it is fresh, one whose
         * owner was removed unresumed — given only to stand for that view, never with its visit
         * ([Orphan]). Read at the resume that would report a new view.
         */
        private fun takeCarry(f: Fragment, state: SurfaceState): Carry? {
            val host = carries[hostIdentity] ?: return null
            state.identity?.let { identity -> host.fragments.remove(identity)?.let { return it } }
            // A restored instance with no carry of its own had no view in progress, so whatever
            // resumes it now is its first view, whoever else was removed beside it.
            if (state.restored) return null
            val orphan = host.orphans.firstOrNull { it.fragmentClass == f.javaClass && it.parent === f.parentFragment }
            if (orphan != null) {
                host.orphans.remove(orphan)
                return Carry(screenViewId = null, screen = orphan.carry.screen)
            }
            // An owner whose removal has not finished yet — its exit can be held back by an animation —
            // has not reached the callbacks that orphan it, but it is already as good as gone.
            if (!host.restoring) return null
            for ((owner, ownerState) in fragmentStates) {
                if (owner === f || !owner.isRemoving || owner.isResumed) continue
                if (owner.javaClass != f.javaClass || owner.parentFragment !== f.parentFragment) continue
                val carry = ownerState.identity?.let(host.fragments::remove) ?: continue
                return Carry(screenViewId = null, screen = carry.screen)
            }
            return null
        }

        /** See [onFragmentStopped] and [onFragmentDetached]. A no-op for a surface that holds no carry. */
        private fun leftUnresumed(f: Fragment, state: SurfaceState) {
            // The leaving instance of a re-creation is what left the carry, not something that ended it.
            if (f.activity?.isChangingConfigurations == true) return
            val host = carries[hostIdentity] ?: return
            val carry = state.identity?.let(host.fragments::remove) ?: return
            if (!f.isRemoving || !host.restoring) return
            val orphan = Orphan(f.javaClass, f.parentFragment, carry)
            host.orphans += orphan
            // A replacement resumes in the same dispatch that removed the owner — the onCreate and
            // onResume rebuilds both do, measured — so a turn of the main looper is the whole window.
            // It is a bound on how long a suppression may wait, not a proof of which fragment is the
            // replacement; that is why no visit goes with it.
            handler.post { host.orphans.remove(orphan) }
        }

        /** See [scheduleUnrestoredCarryDrop]. */
        fun dropUnrestored(host: HostCarries) {
            val restored = fragmentStates.values.mapNotNullTo(HashSet()) { it.identity }
            host.fragments.keys.retainAll(restored)
        }
    }

    /**
     * Whether [this] is a [DialogFragment] currently drawing its own window — which is what both
     * callers actually mean to ask, rather than "is it of that type".
     *
     * It decides an outcome at [isCapturableActivity]: `DialogFragment.onCreate` forces `showsDialog`
     * to `containerId == 0`, so one added to a container is inline content and *does* make its
     * Activity a shell, while one that was `show()`n covers the Activity and does not. Reading the
     * type alone gets one of those two wrong whichever way it is written; pinned by
     * `aDialogFragmentInAContainerStillMakesItsActivityAShell` and
     * `anActivityWithASheetUpAtItsFirstResumeIsStillItsOwnScreenAfterwards`.
     *
     * At the containment gate in [onFragmentResumed] it is defensive and **has no test**: the inline
     * reuse it guards against there needs a container inside the named host, which puts the excluded
     * fragment's view in the host's subtree and makes the host a Compose host that names no screen of
     * its own anyway.
     */
    private fun Fragment.isShownAsDialog(): Boolean = this is DialogFragment && showsDialog

    /**
     * Whether some fragment *containing* [f] names a screen.
     *
     * Containment, unlike stack position, is the question a mask actually wants answered: an excluded
     * fragment nested inside a named one is part of that screen, not a surface covering it. Walking
     * `parentFragment` is the one containment signal a `FragmentManager` gives for free — it says
     * nothing about a *sibling* added into another container of the same manager, which is why a
     * non-covering sibling is a documented residual rather than something this catches.
     *
     * The ancestor is asked the same **static** question it will ask itself, rather than being looked
     * up in the frames already pushed, and that is load-bearing: a child fragment resumes **before**
     * its parent (measured), so a child committed in the parent's `onCreate` reaches this gate while
     * the parent has not resumed and has not declared anything yet. Reading state would say "no named
     * ancestor" and mask — measured, an embedded Compose widget turned its host's screen from
     * `NestingFragment` into `null`, a screen that was correct before masks existed.
     */
    private fun hasNamedAncestor(f: Fragment): Boolean {
        var parent = f.parentFragment
        while (parent != null) {
            if (isCapturableFragment(parent) && fragmentScreenName(parent) != null) return true
            parent = parent.parentFragment
        }
        return false
    }

    /**
     * Reserves a surface's **position** in the stack, inert, before anything is known about it.
     *
     * Position has to be claimed at create/attach because no later hook is early enough. The frame has
     * to land BELOW anything the surface's own content pushes, or a Compose host whose content *does*
     * declare a `TrackedScreen` would lose it: `AbstractComposeView` creates its composition from
     * `onAttachedToWindow`, and a fragment's view is attached to its container **before**
     * `onFragmentViewCreated` — measured — so every hook from there on is potentially too late.
     * Reserving at attach also puts a parent fragment below its children, which resume order would
     * invert (a child resumes before its parent — measured), and "innermost wins" needs the child on
     * top. `sample-android`'s `ComposeHostMaskTest` pins the hook, not just the property: moving this
     * reservation to `onFragmentResumed` makes a declared `TrackedScreen` lose to its host's mask.
     *
     * Every frame is a boundary ([ScopeStack.pushSurface]): this capture can tell, from the view a
     * tap landed on, which surface it happened in, so what a surface declares is scoped to its own
     * events — see the class kdoc. [parent] is the containing surface's frame, the lineage that
     * scoping runs along.
     *
     * Claiming the position must not claim a *voice*, though: an attached surface is not necessarily
     * the visible one, and a `ViewPager2` page cached by `offscreenPageLimit` attaches while a
     * different page is showing. So the frame is pushed and immediately deactivated; [onSurfaceResumed]
     * fills in what it says and switches it on, in place, when the surface actually comes to the front.
     *
     * The deactivation changes nothing at this instant — the frame is still empty, and an empty frame
     * contributes nothing to screen, section or scope whether it is active or not (`resolveScope`
     * drops empty scopes before it looks for ambiguity). It matters for what nests under it next: the
     * ambient read takes an inactive frame's descendants off display with it
     * ([#228](https://github.com/uny/autograph/issues/228);
     * [design notes](https://github.com/uny/autograph/blob/main/docs/design/228-ambient-propagation.md)), so a
     * `TrackedScreen` composed inside a cached, not-yet-shown page is born silent, exactly as its
     * surface is. It also makes [SurfaceState.selected] a true mirror of the frame's active bit from
     * the first instant, so the short-circuits in [select]/[deselect] cannot be reasoning from a
     * belief the stack does not share.
     */
    private fun reserveFrame(parent: ScopeHandle?): ScopeHandle =
        scopeStack.pushSurface(parent = parent).also { scopeStack.setActive(it, false) }

    private fun newSurface(parent: ScopeHandle?): SurfaceState =
        SurfaceState(reserveFrame(parent)).also { it.parent = parent }

    /**
     * Marks [view] as belonging to [state]'s surface, for the two readers of [autographScopeOwner]:
     * the native tap capture, which resolves a tap from the nearest claimed ancestor of the tapped
     * view, and the Compose provider, which nests its root frame under the surface hosting it.
     */
    private fun claim(view: View, state: SurfaceState) {
        view.autographScopeOwner = state.handle
        // Compositions already inside this view (a late install, a re-claimed mounting) re-link
        // under the frame that now claims them; see View.addAutographScopeOwnerListener.
        view.notifyAutographScopeOwnerChanged()
    }

    /**
     * Drops [state]'s frame and takes its claim off [view], if it still holds it. A claim left
     * behind would hand the tap capture a stale origin, which resolves to *less* than the ambient
     * context (a removed frame's lineage contributes nothing) — after this capture alone is
     * uninstalled, a frame the app pushes by hand must be visible to native taps again.
     */
    private fun release(state: SurfaceState, view: View?) {
        scopeStack.remove(state.handle)
        if (view != null && view.autographScopeOwner === state.handle) {
            view.autographScopeOwner = null
            view.notifyAutographScopeOwnerChanged()
        }
    }

    /** The window's decor if it exists; never forces one into existence. */
    private fun Activity.decorView(): View? = runCatching { window.peekDecorView() }.getOrNull()

    /**
     * Handles one surface reaching `RESUMED`, for both Activities and Fragments: settles what its frame
     * says, switches the frame on, and emits a `Screen Viewed` if one is not already standing for this
     * view.
     */
    private inline fun onSurfaceResumed(
        state: SurfaceState,
        takeCarry: () -> Carry?,
        capturable: Boolean,
        covers: Boolean,
        screenName: () -> String?,
        yieldsToFragment: Boolean = false,
    ) {
        // Two different questions, and the name answers only one of them. `capturable` is what this
        // capture *structurally* declines — a Compose host, a fragment-hosting shell — and that is
        // what a mask is for. A null name is the adopter saying "do not report this surface": it must
        // never *cause* a mask (an opt-out that blanked the screen underneath would be a different,
        // undocumented contract) but it does *suppress* one, which is the same promise read the other
        // way. Masking on `capturable` alone left a structurally-excluded surface masking after the
        // adopter had opted it out — measured, `DetailFragment` became null beside one.
        //
        // Which is why the lambda is asked at all here, and why it is asked only of a surface whose
        // answer can matter: one this capture would report, or one that could mask. A headless
        // fragment is neither, and has never been passed to it — `{ it.requireView().tag as String }`
        // is a reasonable lambda to write, and asking it about Glide's retained worker fragment
        // throws out of a FragmentManager dispatch. `covers` already requires a view, so that is the
        // whole guard.
        //
        // What the surface IS is settled once per mounting; what it is CALLED is not. A name that
        // arrives late — `{ it.loadedTitle }`, null until the data does — has to be picked up at the
        // next resume, and one that goes away has to stop being reported. So the gate reads the
        // settled answer, not this resume's: an Activity settled as a screen that has since taken a
        // content fragment, resumed beside another Activity, is neither capturable nor covering right
        // now — and gating on that blanked the name it still owns.
        val deciding = !state.decided
        if (deciding) {
            state.decided = true
            state.capturable = capturable
        }
        val name = if (state.capturable || covers) screenName() else null
        // Settled: the frame keeps its mask, and only the name is revised below.
        val masks = if (deciding) !state.capturable && covers && name != null else null
        val screen = if (state.capturable) name else null
        // Raise before `update` and lift after it, so the snapshot in between is still true of the
        // frame and never lets the screen underneath show through (see ScopeStack.setScreenMasked).
        if (masks == true) scopeStack.setScreenMasked(state.handle, true)
        // `update` revises what the frame says and keeps its parent link, which [reparent] owns
        // (onFragmentViewCreated).
        scopeStack.update(state.handle, screen = screen)
        if (masks == false) scopeStack.setScreenMasked(state.handle, false)

        // A view that has ended takes its visit id with it before the frame answers again (#242).
        // Every path that ends a view does so while the surface is off display, and not every resume
        // after one emits — a return yielded to a fragment, a surface that is not its own screen right
        // now — so without this the frame would hand a finished visit's id to the taps made on it.
        if (!state.emitted) scopeStack.endScreenVisit(state.handle)

        // Selected on every resume, whatever the frame says — including nothing. A resumed surface IS
        // on display, and that is the only question the bit answers; whether it names, masks or
        // declares nothing is settled above, in the frame's contents. The ambient read takes a
        // deselected frame's descendants with it, so leaving an empty frame off would silence what is
        // composed inside the surface (docs/design/228-ambient-propagation.md). Attribution before
        // emission: emitScreenView records history, and the frame has to be answering by the time
        // anything reads the stack.
        select(state)

        if (state.emitted) return // a view of this screen is already in progress; this resume is a return
        // Taken by the first genuinely fresh resume of the surface it was left for, capturable or not —
        // that resume is the re-creation, and a carry left behind would wait for a view that is over.
        // It continues the interrupted view only while that is still a view of the same screen: one
        // renamed across the change is a new view, and reports one.
        val carry = takeCarry()
        val continues = carry != null && carry.screen == screen
        if (screen == null) return
        // The frame keeps the settled name, but a surface that is right now neither its own screen
        // nor covering does not report itself: an Activity settled as a screen that has since taken
        // a content fragment, returning beside another Activity, would otherwise emit its own name
        // over the fragment the user is looking at. This is where the name gate above used to stop it.
        // Covering does not settle it either: alone on display, the same Activity returning without a
        // stop reported itself over a content fragment that was coming back with it (#272). Gating on
        // `capturable` alone would also silence an Activity whose content sits beside an excluded
        // fragment — a mini-player it still owns the screen around — so the fragment has to be one
        // that will report the return itself — an expectation checked after the dispatch, in
        // scheduleDemotionCheck, since a fragment moved down while its host was paused never does.
        if (!capturable && !covers) return
        if (!capturable && yieldsToFragment) {
            if (!continues) state.yieldedReturn = screen
            return
        }
        if (continues) {
            state.emitted = true
            state.viewScreen = screen
            carry?.screenViewId?.let { scopeStack.continueScreenVisit(state.handle, it, screen) }
            return
        }
        emit(state, screen)
    }

    private fun emit(state: SurfaceState, screen: String) {
        state.emitted = true
        state.viewScreen = screen
        try {
            scopeStack.emitScreenView(tracker, screen, origin = state.handle)
        } catch (_: Throwable) {
            // A throwing tracker must never crash a lifecycle callback; the frame stays regardless.
        }
        endViewsOffDisplay()
    }

    /**
     * Ends the in-progress view of every surface belonging to an Activity that is **not** on display.
     *
     * The Activity-level counterpart of the demotion rule in `onFragmentPaused`: a pause alone does not
     * end a view, because a dialog, a permission prompt or a partially-covering Activity all pause the
     * screen underneath without the user leaving it. What *does* end it is another screen being
     * reported while this one is off display — the user demonstrably saw something else, so coming back
     * is a new view and must report one. An Activity fully covered also stops, which ends its view
     * anyway; this is what catches the translucent case, where it never stops.
     *
     * Called only from an actual emit, not from every resume, and that is the tight part: an unnamed
     * overlay — a Compose host that reports nothing, an excluded Activity — passes over the screen
     * beneath without ending its view, so returning stays silent instead of reporting the same screen
     * twice. Activities currently on display are skipped, which is also what keeps multi-resume
     * (Android 10+, two Activities `RESUMED` at once) from having each end the other's view.
     */
    private fun endViewsOffDisplay() {
        for ((activity, state) in activityStates) {
            if (activity in resumedActivities) continue
            state.emitted = false
            // A view a rotation interrupted and a restored surface still holds unresumed ends here too:
            // its host only paused, so nothing else drops it, and resuming it later is a new view.
            state.identity?.let(carries::remove)
            fragmentRegistrations[activity]?.callbacks?.endViews()
        }
    }

    /**
     * Ends a fragment's current **mounting**: drops its frame and reserves a fresh one in its place.
     *
     * Everything the old frame held was a statement about the view that has just been destroyed: what
     * it said (a screen name, or a mask), and, crucially, **its position**. A `detach()`ed
     * fragment is never `onFragmentDetached`, so re-attaching it reused a frame reserved before its
     * sibling's — measured, a re-attached `DetailFragment` emitted its own name while the ambient
     * screen kept saying `SecondFragment`. A fresh frame reserved here lands above everything still
     * mounted and below whatever this fragment's rebuilt view composes next, which is exactly the
     * ordering [reserveFrame] exists to get.
     *
     * **View destruction, not the stop before it.** A stop destroys nothing — a fragment keeps its
     * view, its children keep their frames, and an `AbstractComposeView` keeps its composition (the
     * default strategy disposes on detach from the window, which a stop is not). Re-reserving there
     * jumped the frame above every one of those: measured, pressing home and returning turned a
     * nested host's child screen from `SecondFragment` into a masked null, and it would do the same
     * to a `TrackedScreen` declared inside a Compose host. An Activity never needs this at all: its
     * content view lives from `onCreate` to `onDestroy`, so it has exactly one mounting.
     */
    private fun endMounting(state: SurfaceState) {
        scopeStack.remove(state.handle)
        state.handle = reserveFrame(state.parent)
        state.decided = false
        state.selected = false
    }

    private fun select(state: SurfaceState) {
        if (state.selected) return
        state.selected = true
        scopeStack.setActive(state.handle, true)
    }

    private fun deselect(state: SurfaceState) {
        if (!state.selected) return
        state.selected = false
        scopeStack.setActive(state.handle, false)
    }

    /** Removes every frame this capture pushed and stops it reporting. Idempotent. */
    fun tearDown() {
        active = false
        handler.removeCallbacksAndMessages(null)
        activityStates.forEach { (activity, state) -> release(state, activity.decorView()) }
        activityStates.clear()
        fragmentRegistrations.values.forEach {
            it.callbacks.releaseFrames()
            it.fragmentManager.unregisterFragmentLifecycleCallbacks(it.callbacks)
        }
        fragmentRegistrations.clear()
        resumedActivities.clear()
        carries.clear()
    }

    /**
     * A fragment's `mWho`: the id its `FragmentManager` gives it, which `FragmentState` saves and writes
     * back into the instance that re-creates it, and which no other live fragment shares — measured
     * across two rotations in a row for two untagged siblings of one class, a child fragment and the
     * pages of a `ViewPager2`. `putFragment` is the public route to it (`FragmentStateAdapter` reads
     * page identity the same way). Through the fragment's own manager: in a recursive callback the
     * `fm` passed in is the outer one, and putting a child fragment there throws. Null when the
     * fragment has no manager yet, and then nothing is carried for it.
     */
    private fun identityOf(fragment: Fragment): String? = runCatching {
        Bundle().also { fragment.parentFragmentManager.putFragment(it, IDENTITY_KEY, fragment) }.getString(IDENTITY_KEY)
    }.getOrNull()

    // --- Static capturability filter (mirrors iOS isCapturableScreen; all decidable at resume) -------

    private fun isCapturableActivity(activity: Activity): Boolean {
        if (contentHostsComposeView(activity)) return false
        // A single-Activity app's Fragments are the screens; the Activity hosting them is a shell.
        // A DialogFragment is not one of those: it draws its own window *over* the Activity rather
        // than being its content, and counting it made an Activity that merely had a sheet up at its
        // first resume a shell for good — measured, while the mask was still one-way, it then reported
        // no screen at all, permanently; even now it would mask until its next stop, because the
        // decision is settled once per mounting.
        if (activity is FragmentActivity &&
            activity.supportFragmentManager.fragments.any { it.view != null && !it.isShownAsDialog() }
        ) {
            return false
        }
        return true
    }

    private fun isCapturableFragment(fragment: Fragment): Boolean {
        val view = fragment.view ?: return false // headless / retained worker fragment
        if (isNavHostFragment(fragment)) return false // a container, not a screen
        return !viewSubtreeHostsComposeView(view)
    }

    private fun contentHostsComposeView(activity: Activity): Boolean {
        val content = activity.findViewById<View>(android.R.id.content) ?: return false
        return viewSubtreeHostsComposeView(content)
    }

    private fun viewSubtreeHostsComposeView(root: View): Boolean {
        val composeClass = abstractComposeViewClass ?: return false
        return viewSubtreeContains(root, composeClass)
    }

    private fun viewSubtreeContains(root: View, klass: Class<*>): Boolean {
        if (klass.isInstance(root)) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (viewSubtreeContains(root.getChildAt(i), klass)) return true
            }
        }
        return false
    }

    private fun isNavHostFragment(fragment: Fragment): Boolean =
        navHostFragmentClass?.isInstance(fragment) == true

    private companion object {
        // Resolved reflectively, once, so this module compiles and runs without Compose / navigation on
        // the classpath — a plain View/Fragment app depends on neither. Null when the class is absent
        // (nothing to exclude), which is exactly the right answer for such an app.
        private val abstractComposeViewClass: Class<*>? =
            runCatching { Class.forName("androidx.compose.ui.platform.AbstractComposeView") }.getOrNull()
        private val navHostFragmentClass: Class<*>? =
            runCatching { Class.forName("androidx.navigation.fragment.NavHostFragment") }.getOrNull()

        /** The key of the token in an Activity's saved state, and of the scratch entry [identityOf] reads. */
        private const val IDENTITY_KEY = "dev.ynagai.autograph.android.identity"
    }
}
