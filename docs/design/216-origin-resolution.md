# #216 — an event resolves from the surface it happened in

- Issue: [#216](https://github.com/uny/autograph/issues/216) (closed)
- Shipped in: [#222](https://github.com/uny/autograph/pull/222) (`f0f70f4`, 2026-09-11) and
  [#223](https://github.com/uny/autograph/pull/223) (`2dd3086`, 2026-09-12), the last two of the #216
  rebuild stack (#220 → #221 → #222 → #223); first released in 0.9.0
- Code: `ScopeStack.push(…, boundary)` / `ScopeStack.current(origin)` (`autograph-context`),
  `View.autographScopeOwner` and its listener (`autograph-context`, `ScopeOrigin.android.kt`),
  `ProviderFrame` / `ProviderOrigin` / `KeepLinkedToHost` (`autograph-compose`, `ScopedContext.kt`,
  `HostSurface.android.kt`), `NavController.TrackScreenViews` / `TrackScreenView`
  (`autograph-compose`), `AndroidScreenCapture.reserveFrame` / `endMounting` and the fragment
  callbacks (`autograph-android`, `AndroidScreenCaptureImpl.kt`), `resolveTapTarget`
  (`AndroidTapTarget.kt`)

This is the lab notebook for origin resolution. The KDoc on those declarations states the contract;
this file keeps what was measured, what was refuted, and what depends on a particular library version.
Expect parts of it to go stale — that is why it lives here and not in the published API docs.

Scope: the owner half of #216 (#222) and the declarations-as-frames half (#223). The selection half
(#220's active bit and mask, #221's fragment lifecycle ordering) is not here; see *Not here* at the
end. The ambient read's own failure, found on the same on-device rig, became #228 and has its own
file, [`228-ambient-propagation.md`](228-ambient-propagation.md).

## The problem

Before #222, every event read the one ambient answer: the innermost frame on the stack, by insertion
order. Two things went wrong with that, both **wrong values, not absent ones**:

- **A declaration reached events on another surface.** An unnamed (masked) fragment added straight
  into a named Activity is later on the stack, so its mask blanked the Activity's own taps beside it
  (#216's "S2"). A `ViewPager2` of Compose pages, each declaring a `TrackedScreen`: page B's frame
  is pushed after A's, so a tap on A carried `PageB`.
- **A surface that names no screen borrowed the last one seen.** The Compose tap observer fell back
  to `ScreenHistory.lastScreen` whenever no frame named a screen. History outlives what it records by
  design, so on an unnamed surface it supplied the screen the user had just left.

## The rule that shipped

### Origin (#222)

An event is resolved **from the frame of the surface it happened in**, `ScopeStack.current(origin)`.
Three sets of frames take part:

1. the origin's **lineage** (itself and its ancestors, by the parent links);
2. the **subtree** beneath the origin, stopping at, and excluding, any `boundary` frame;
3. every frame **under no boundary at all**.

They resolve in insertion order, except that **a container ranks no later than the content nested in
it**, ties broken by depth (outermost first). A parent link that points off the stack (a removed
frame, or another stack's) is **transparent**: it contributes nothing and is not a boundary.

A frame is a `boundary` when its pipeline can **localize** an event to it **and** it stands for **a
surface of its own**. Every Activity and fragment frame the Android screen capture reserves is one;
the Compose provider's root frame is deliberately not.

The two Android pipelines meet through a view tag, `View.autographScopeOwner`: the screen capture
tags each surface's root view (a fragment's view, the Activity's `peekDecorView()`) with that
surface's frame; the native tap capture resolves from the nearest tagged ancestor of the tapped view;
the Compose provider links its root frame under the nearest tagged ancestor of its host view — **at
tap time**, from a posted runnable at composition, and again whenever the capture announces a claim
or release above it (`notifyAutographScopeOwnerChanged` → `addAutographScopeOwnerListener`).

Invariant: **a boundary-free stack resolves to the same members with or without an origin.** That is
the stack iOS has, and Android without the native screen capture.

### Declarations are frames (#223)

The history fallback could not simply be deleted: a bare `TrackScreenView` and
`NavController.TrackScreenViews` recorded history and emitted but pushed no frame, so every tap under
a `NavHost` would have lost its screen. Both now declare a frame, and then the observer stopped
reading history.

- `NavController.TrackScreenViews` owns one frame, pushed from an effect keyed on the **stack alone**
  and filled by the destination listener, which is registered after it.
- The route frame and the destination's `TrackedScreen` are **siblings**, resolved by push order, so
  calling `TrackScreenViews()` above the `NavHost` is load-bearing.
- A destination whose `screenName` is `null` clears the frame, instead of leaving the previous route
  attributing its taps.
- A bare `TrackScreenView` wraps no content, so its declaration applies to the whole composition, not
  a subtree, and — naming a screen — owns its section, clearing an enclosing `TrackedScreen`'s.

## Measured facts it rests on

Read on androidx.fragment **1.8.9**, JetBrains navigation-compose **2.9.2**, CMP **1.11.1**, in the
JVM and Robolectric suites; the on-device run below later confirmed the tap payloads that rest on
them. The rig for the first two was
`.claude/handoff/rigs/216-step3-owner-tag-timing-probe.kt.txt` (local, not in the repository).

1. `Fragment.parentFragment` is already set at `onFragmentPreAttached`, so a fragment's frame can be
   reserved with its lineage in one step.
2. A fragment added while its Activity is showing **attaches its view to the window — and so creates
   its composition — before `onFragmentViewCreated`**, where the capture claims the view. An owner
   lookup at composition time found the **Activity's** claim: a wrong owner, not a missing one. Hence
   linking at tap time, plus the posted link and the claim notification.
3. On a view rebuild (`detach` + `attach`) frames are re-reserved **child-first**: a child's view is
   destroyed inside its parent's `performDestroyView`, before the parent's own, so the child's fresh
   frame is linked to a parent frame about to be replaced. Views come back parent-first, so the parent
   link is re-read at `onFragmentViewCreated`.
4. An Activity's decor does not exist at `onActivityCreated` (dispatched inside `super.onCreate`), so
   the claim goes on at resume, on `peekDecorView()` rather than `android.R.id.content`: an AppCompat
   window action bar sits outside content.
5. `NavController.addOnDestinationChangedListener` fires **synchronously** when a destination is
   already current, so the route frame must be pushed before the listener is registered.

## Refuted along the way

Each would have made a value correct→absent or correct→wrong. Items 1–9 were written and tested,
then reproduced failing in #222's review; item 10 was ruled out while #223 was being designed, before
any of it was built; item 11 was written, then found in #223's review.

1. **Only lineage and subtree take part.** A root the app pushed by hand (an experiment scope, a
   screen on an opted-out surface) vanished from every tap the moment the capture claimed the view
   tree — silent data loss through a public API. Hence set (3), "under no boundary at all".
2. **The provider frame is a boundary.** A composition is how its host surface declares its screen,
   not a surface. As a boundary, a native tap on a toolbar beside the `ComposeView`, on an
   `AndroidView` interop button inside it, or on a masked shell Activity's own chrome lost the
   `TrackedScreen` inside it; and a provider nested inside another's composition hid its declarations
   from the outer provider's taps, which the outer observer cannot localize out of. Hence the
   two-part boundary criterion.
3. **The provider claims its host view** (the stopgap for 2). Sibling providers in one `ComposeView`
   share the same `LocalView`, so the last one to claim won and the other's taps were mis-attributed.
4. **Plain insertion order among the survivors.** A surface adopted late — an Activity that predates
   the capture's install is picked up at its next resume — pushes its frame *after* the composition
   it hosts, and its mask then blanked the `TrackedScreen` inside it. Hence the container rank.
5. **The container rank as a stable sort.** Equal ranks kept insertion order, so in exactly the case
   the rank exists for (the container pushed right after its content) the late mask still sorted
   after the content and blanked it. Android passed by structural accident — the empty provider frame
   between them. Hence the depth tie-break. Frames sharing a rank are always one ancestor chain (a
   rank is borrowed from one frame's index, only by its ancestors), so (rank, depth) is a total order.
6. **Link once, at composition.** A composition that existed before the capture was installed was
   never re-linked, so it stayed under no boundary and applied to every origin: an off-screen pager
   page lent its `TrackedScreen` to a tap on the page beside it — page A carried `PageB`. Hence the
   claim notification.
7. **One listener slot per view.** Every outermost provider in one `ComposeView` shares the host view;
   the second registration overwrote the first, which then never re-linked. Hence a list.
8. **A foreign claim is a boundary.** The sample app's `Application` installs its own captures on
   another stack, and the late-install test passed only because it read that stack's claim as a
   boundary. Hence off-stack parents are transparent, and the test runs on a plain `Application`.
9. **`update(handle, screen = …)` at resume without `parent`.** `update` replaces the whole frame,
   parent link included, so every fragment was re-rooted at its first resume. Only the tap-payload
   test for an opted-out fragment caught it.
10. **Delete the history fallback alone.** Every tap under a `NavHost` would have lost its screen; see
    *Declarations are frames*.
11. **Push the route frame from the tracker-keyed effect.** A tracker swap (logout) removed and
    re-pushed it at the end of the list, above the still-mounted destination's `TrackedScreen`, whose
    screen it then overrode and whose section it dropped. All three review lenses found it
    independently.

The same review round also found that the `TrackScreenViews` KDoc called the route and destination
frames "nested". They are siblings.

## Verified on a device

Before 0.9.0, on the local `Pixel_9` AVD (API 35) at `fe8f6e5`, the framework-dependent cases of
`ComposeTapOriginTest` were ported to `androidTest` and driven by real Espresso taps
(Instrumentation → `ViewRootImpl` → `Window.Callback`). Tap payloads:

| case | payload |
|---|---|
| masked fragment beside the Activity's own composition | own → `Main`, masked → no screen |
| two sibling pages, B added after A | A → `PageA`, B → `PageB` |
| interop `Button` (`AndroidView`) inside `TrackedScreen("Detail")` | native capture reports it, `Detail` |
| native chrome beside a masked sibling | `Main` |
| late install (captures installed after two pages composed, then pause/resume) | A → `PageA`, own → `Main` |
| `NavHost` route beside the Activity's own composition | own → `Main`, nav → `home` |
| real `ViewPager2` + `FragmentStateAdapter`, `offscreenPageLimit = 1`, A → B → C → B → A | every tap = the page on display |

Every row carried the expected payload. The interop row also confirms a premise: a `Button` hosted by
`AndroidView` inside a `ComposeView` is reached by the native capture's pressed walk. The rig lived
on a local branch (`rig/216-on-device`) that could not be merged — it swaps the instrumentation
runner — and no longer exists; the table above is the record. The ambient `current()` read on the
same rig did **not** hold for Compose-declared screens; that became #228.

## Trade accepted

A bare `TrackScreenView` is ambient within its composition, not scoped: composed after a sibling
(a sheet, a dialog), it takes the taps on the content behind it too. And it does not provide
`LocalScreenContext`, so nested in a `TrackedScreen` the autocaptured path names the inner screen
while an explicit `trackClick` names the enclosing one. `TrackedScreen` is the answer when the two
should agree.

## Version-dependent

Facts 1–4 are androidx.fragment behaviour, read on 1.8.9; fact 2 also depends on
`AbstractComposeView` creating its composition from `onAttachedToWindow`, read on CMP 1.11.1. Fact 5
is navigation-compose 2.9.2. The repository still uses fragment 1.8.9 and navigation-compose 2.9.2;
it has since moved to CMP 1.12.0, where fact 2 has not been re-measured.

On iOS nothing localizes: no pipeline claims a view, so the stack is boundary-free and, by the
invariant above, resolves as it did before. #223 did change iOS in the same direction as Android — a
bare `TrackScreenView` or a tracked destination is now visible to `autograph-uikit`'s native tap
capture through `current()`.

## Pinned by

| rule | test |
|---|---|
| a sibling's mask stays off the host's events | `ScopeStackOriginTest.a_sibling_surfaces_mask_does_not_reach_an_event_on_the_surface_hosting_it`, `AndroidTapOriginTest.aMaskRaisedByAnExcludedFragmentReachesTheTapsInsideItAndNotTheActivitysOwn`, `ComposeTapOriginTest.aMaskRaisedByAnExcludedFragmentDoesNotReachTheActivitysOwnComposition` |
| a later sibling page does not attribute | `AndroidTapOriginTest.aTapInOneNamedFragmentDoesNotCarryASiblingAddedLater`, `ComposeTapOriginTest.aTapOnOnePageDoesNotCarryTheScreenASiblingPageDeclaredLater` |
| descent stops at a nested boundary | `ScopeStackOriginTest.the_descent_from_an_origin_stops_at_a_nested_boundary` |
| set (3), frames under no boundary (refuted 1) | `ScopeStackOriginTest.a_frame_under_no_boundary_applies_to_every_origin`, `AndroidTapOriginTest.aFrameTheAppPushedByHandReachesEveryTapWhileTheCaptureIsInstalled`, `ComposeTapOriginTest.aFrameTheAppPushedByHandReachesEveryTapWhileTheNativeCaptureIsInstalled` |
| boundary-free invariant | `ScopeStackOriginTest.a_boundary_free_stack_resolves_identically_with_or_without_an_origin` |
| provider frame is not a boundary (refuted 2) | `ComposeTapOriginTest.aNativeTapOnInteropContentInsideACompositionCarriesTheScreenDeclaredAroundIt`, `…aMaskedShellActivitysOwnNativeChromeCarriesTheScreenItsCompositionDeclares`, `…aNestedProvidersDeclarationStaysVisibleToTheOuterProvidersTap`, `ScopeStackOriginTest.a_composition_inside_a_surface_is_that_surfaces_own_declaration` |
| sibling providers in one `ComposeView` (refuted 3) | `ComposeTapOriginTest.siblingProvidersInOneComposeViewResolveInteropTapsAsTheHostDoes` |
| container rank and its tie-break (refuted 4, 5) | `ScopeStackOriginTest.a_surface_adopted_late_ranks_no_later_than_the_content_it_hosts`, `…a_late_container_directly_over_its_content_still_ranks_before_it`, `…a_root_pushed_after_a_surfaces_content_still_wins_over_it` |
| tap-time linking | `AutocaptureObserverTest.anOriginLinksTheCompositionsRootUnderItsHostAtTapTime` |
| re-link on a late claim (refuted 6, 7) | `ComposeTapOriginTest.aCompositionThatPredatesTheInstallIsRelinkedWhenItsSurfaceIsClaimed`, `…everyOutermostProviderInOneComposeViewIsRelinkedOnALateInstall` |
| a fragment that predates the install is claimed at adoption | `AndroidTapOriginTest.aFragmentThatPredatesTheInstallIsClaimedWhenItsActivityIsAdopted` |
| off-stack parents are transparent (refuted 8) | `ScopeStackOriginTest.a_parent_off_this_stack_is_transparent_not_a_boundary`, `…an_origin_from_another_stack_resolves_to_nothing` |
| parent link kept at resume (refuted 9) | `AndroidTapOriginTest.aTapInAnOptedOutFragmentCarriesTheScreenOfTheSurfaceHostingItNotTheSiblingItCovers` |
| parent link re-read after a rebuild (fact 3) | `AndroidTapOriginTest.aChildStaysLinkedToItsParentAcrossARebuildOfBothViews` |
| decor, not content (fact 4) | `AndroidTapOriginTest.aTapOutsideTheContentViewStillBelongsToTheActivity` |
| claims released | `AndroidTapOriginTest.uninstallingTheScreenCaptureTakesItsClaimsWithIt` |
| no history at tap time (refuted 10) | `AutocaptureObserverTest.historyIsNeverConsultedWhenNoFrameNamesAScreen`, `ScreenTrackingUiTest.aBareTrackScreenViewDeclaresItsScreenForAutocapture`, `…navTrackScreenViewsDeclaresTheCurrentRoute` |
| route frame keyed on the stack alone (refuted 11) | `ScreenTrackingUiTest.aTrackerSwapDoesNotMoveTheRouteFrameAboveTheScreenOnDisplay` |
| untracked destination clears the route | `ScreenTrackingUiTest.anUntrackedDestinationDeclaresNoScreenRatherThanKeepingThePreviousRoute` |
| route does not attribute another surface | `ComposeTapOriginTest.aNavHostsRouteDoesNotAttributeTapsOnAnotherSurface` |

`ComposeTapOriginTest` (`sample-android`) is the one suite that sends real `MotionEvent`s through a
real `AutographProvider` with the native capture installed, on a plain `Application`; it is the only
place the Compose half of the Android origin path is exercised end to end.

The pairing above follows each test's name and its comment, not a mutant run, with one exception:
dropping `parent` from the resume-time `update` (refuted 9) was re-run on 2026-09-24 and fails
`aTapInAnOptedOutFragmentCarriesTheScreenOfTheSurfaceHostingItNotTheSiblingItCovers` and
`aChildStaysLinkedToItsParentAcrossARebuildOfBothViews` (2 of `AndroidTapOriginTest`'s 9). #222 and
#223 each list the mutants they ran, and each failed at least one test; neither list says which test
caught which mutant, so it is not repeated here as a pairing.

## Re-verifying after a change to origin resolution

Assert on a **real tap payload**, not on the ambient snapshot: the snapshot can show a mask raised
while the tap carries something else, and that divergence is what #222 fixed. On the Compose side,
feed `MotionEvent`s to `window.callback` and vary `downTime` per gesture — the capture reports once
per gesture. Run with a plain `Application`, never the sample's (refuted 8).

## Not here

- #220: the active bit, the one-way mask and why `remove` + `push` is not an un-mask
  (`ScopeStack.setActive` / `maskScreen` / `update` KDoc).
- #221: the fragment lifecycle ordering the selection logic rests on, and the mask decisions in
  `AndroidScreenCapture.onSurfaceResumed` / `endMounting` / `isCapturableActivity` (comments in
  `AndroidScreenCaptureImpl.kt`).
- #228: the ambient read following the display — [`228-ambient-propagation.md`](228-ambient-propagation.md).
