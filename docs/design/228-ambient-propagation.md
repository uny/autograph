# #228 — the ambient read follows the display for Compose-declared screens

- Issue: [#228](https://github.com/uny/autograph/issues/228) (closed)
- Shipped in: [#231](https://github.com/uny/autograph/pull/231) (`0d24fcc`, 2026-09-13), first released in 0.9.1
- Code: `ScopeStack.recompute` / `ScopeStack.current(origin)` (`autograph-context`),
  `ProviderFrame` / `ProviderOrigin` (`autograph-compose`, `ScopedContext.kt`),
  `AndroidScreenCapture.reserveFrame` / `onSurfaceResumed` (`autograph-android`, `AndroidScreenCaptureImpl.kt`)

This is the lab notebook for the rule. The KDoc on those declarations states the contract; this file
keeps what was measured, what was refuted, and what depends on a particular library version. Expect
parts of it to go stale — that is why it lives here and not in the published API docs.

## The problem

`ScopeStack`'s active bit ([`setActive`](../../autograph-context/src/commonMain/kotlin/dev/ynagai/autograph/context/ScopeStack.kt))
answers "is this surface the one on display?". Before #228 it applied only to a frame's **own**
contribution. That was enough for surfaces the native Android capture names itself, but not for a
screen declared *inside* a surface — a `TrackedScreen` composed in a `ViewPager2` page:

- A pager page composes at `STARTED`, before it is ever shown, so its `TrackedScreen` frame was born
  active.
- Nothing deactivated it when the page was demoted: the provider had no frame of its own that
  followed the lifecycle, and the host surface's bit did not reach what was nested under it.

So the ambient `ScopeStack.current()` named the page **most recently composed**, not the one on
display. Measured on the `rig/216-on-device` app (Pixel 9 AVD, API 35), log-only cases:

| step | fragments | ambient before | ambient after #231 |
|---|---|---|---|
| resumed | — | `Main` | `Main` |
| paused (`STARTED`) | — | `Main` | `null` (`masked=false`) |
| resumed again | — | `Main` | `Main` |
| page=1 | `PageA:STARTED, PageB:RESUMED, PageC:STARTED` | `PageC` | `PageB` |
| page=2 | `PageA:STARTED, PageB:STARTED, PageC:RESUMED` | `PageC` | `PageC` |
| page=1 | `PageA:STARTED, PageB:RESUMED, PageC:STARTED` | `PageA` | `PageB` |
| page=0 | `PageA:RESUMED, PageB:STARTED, PageC:STARTED` | `PageA` | `PageA` |

(7/7 green after the fix; the tap payloads were unchanged, 6/6.) The rig was a local branch, not
merged to `main`; the table above is the record.

## The rule that shipped

Four changes. The first two are the fix; the last two are holes the first two opened, each found in
review after the PR's own thesis turned out to be false.

1. **Ambient: an inactive frame takes everything nested under it with it.** `recompute()` drops a
   frame whose on-stack ancestor is inactive, whatever the frame's own bit says. This covers a frame
   pushed *while* its surface is demoted — the measured shape — which per-`TrackedScreen` observers
   would not.
2. **The Compose provider's frame follows `LocalLifecycleOwner`.** `ProviderFrame` is active exactly
   while the owner is `RESUMED`, seeded from the owner's state at composition. Seeding explicitly
   matters: `addObserver`'s catch-up dispatch replays events up to the current state, which would
   leave a `STARTED` owner's frame active. One observer per provider; (1) does the rest.
3. **A Compose tap always resolves from the composition's own frame, never ambiently.**
   `ProviderOrigin.resolve` used to fall back to the ambient `current()` whenever nothing claimed the
   host view. Nothing claims a host view on iOS, or on Android without the native capture, so that
   fallback was the path of *every* Compose tap there. Under (1), it dropped a visible `STARTED`
   page's own `TrackedScreen` — a `ViewPager2` neighbour peeking beside the current page:
   correct→absent, and on a shared stack correct→wrong (it took the current page's screen). The same
   probe read `PageB` at the base and `null` at the PR's original tip.
4. **The Android capture selects every surface it sees resume**, including one that declares
   nothing. Selecting only frames that name or mask a screen looked equivalent while an empty frame's
   bit was inert. Under (1), a resumed Compose-hosting fragment the adopter had opted out by name —
   or a Compose child fragment contained by a named screen — silenced every `TrackedScreen` and
   `AutographScope` composed inside it for as long as it was on display, and the ambient screen fell
   back to an older surface's: a wrong value, not an absent one.

The origin-taking `current(origin)` applies (1) too, with an **own-surface exemption**: an inactive
frame on the origin's lineage, or beneath the origin with no boundary between, drops its own
contribution and nothing else. The origin is the pipeline asserting the event happened in that
surface, and the surface's own demotion (its host paused, its page peeking beside the current one) is
not evidence against that. A demoted *sibling* surface is still silenced for the event, exactly as it
is ambiently — on a shared, boundary-free stack (iOS; Android without the native capture) that is the
only thing keeping a demoted neighbour page's screen off a tap on the current one.

The reserved-then-deactivated frame in `AndroidScreenCapture.reserveFrame` predates #228 but
gained a second purpose from it: a `TrackedScreen` composed inside a cached, not-yet-shown page is
born silent because its surface's frame is.

## Refuted along the way

- **"Taps were already right; they resolve from an origin."** The PR's opening thesis. False on iOS,
  on JVM, and on Compose-only Android, where the origin path fell back to the ambient read — which is
  what (3) fixes.
- **The issue's option 2 alone (deactivate the provider's frame).** Silences nothing: the frame is
  empty and the bit did not propagate, so every `TrackedScreen` under it kept its voice.
- **Per-`TrackedScreen` lifecycle observers.** Fix the disposed case but not a frame pushed while the
  host is already demoted.
- **A lineage-only exemption in `current(origin)`.** Exempting only the origin's ancestors lost the
  `TrackedScreen` composed beside a native tap on a demoted page (origin = the page's frame), because
  the Compose provider frame inside that page mirrors the same demotion and sits *beneath* the
  origin, not above it. Hence "lineage, or beneath with no boundary between".
- **`a_demoted_pager_pages_declarations_do_not_reach_the_selected_page` asserted "ambiently,
  insertion order still names B".** That line was the bug, pinned; it flipped to `A`.

## Trade accepted

A composition whose host no surface claims — a plain `Dialog` with its own provider — no longer
borrows a claimed native surface's screen through the ambient read. Absent, never wrong.

## Version-dependent: iOS

Not measured on a device: the Kotlin/Native test runner cannot host a `ComposeUIViewController`
(`MetalRedrawer requires MTLDevice`). Read from the **CMP 1.11.1** source instead: `CMPViewController.m`
maps `viewWillAppear` → `composeContainerWillAppear` and `viewDidDisappear` →
`composeContainerDidDisappear`, and `ComposeContainerLifecycleDelegate` turns those into `RESUMED` /
`CREATED` (`STARTED` while the scene is inactive). So (2) reaches iOS with no code of its own: two
Compose tabs, or a pushed Compose screen, previously left the last *composed* one ambient for
`NativeTapCapture` / `ExplicitElementCapture`. Native iOS frames are removed on `viewDidDisappear:`,
never deactivated, so nothing changed for them.

The repository has since moved to CMP 1.12.0; the mapping has not been re-read there.

## Pinned by

| rule | test |
|---|---|
| (1) ambient propagation | `ScopeStackTest.ambiently_an_inactive_frame_takes_its_descendants_with_it` |
| an inactive link still carries lineage (origin read) | `ScopeStackTest.an_inactive_frame_still_carries_the_lineage_of_its_descendants` |
| demoted page vs selected page | `ScopeStackOriginTest.a_demoted_pager_pages_declarations_do_not_reach_the_selected_page` |
| own-surface exemption, lineage half | `ScopeStackOriginTest.an_origin_under_a_demoted_surface_still_sees_the_declarations_beneath_it` |
| own-surface exemption, beneath half | `ScopeStackOriginTest.a_demoted_frame_beneath_the_origin_in_its_own_surface_does_not_gate_what_is_under_it` |
| sibling still silenced | `ScopeStackOriginTest.a_demoted_sibling_off_the_origins_lineage_is_silenced_for_that_origin_too`, `ProviderSelectionUiTest.aTapInTheCurrentCompositionDoesNotPickUpADemotedSiblingsScreen` |
| (2) provider follows lifecycle | `ProviderSelectionUiTest` (paused host, not-yet-resumed host, page scope, replaced owner) |
| (3) no ambient fallback | `ProviderSelectionUiTest.aTapInADemotedCompositionStillSeesItsOwnScreenWithNoHostClaimed` |
| (4) select on every resume | `AndroidScreenCaptureTest.aResumedFragmentThatDeclaresNothingStillLetsWhatIsComposedInsideItSpeak` |

The three original `ProviderSelectionUiTest` cases (paused host, not-yet-resumed host, page scope)
fail 3/3 with `ScopedContext.kt` reverted. The three added in review each fail against their targeted
mutant: `aTapInADemotedComposition…` with the ambient fallback restored, `aTapInTheCurrentComposition…`
with `current(origin)` applying the bit per frame instead of propagating it, and
`theFrameFollowsAReplacedLifecycleOwnerNotTheOneItWasComposedUnder` with the `lifecycle` key dropped.
The lineage-only exemption is caught at the stack, by
`a_demoted_frame_beneath_the_origin_in_its_own_surface_does_not_gate_what_is_under_it`.

## Re-verifying after a change to selection semantics

The reads disagree across these cases, so probe all six. On **each stack shape** (a boundary-free
shared stack, and the Android boundary stack): the ambient `current()`, which takes no origin, and
`current(origin)` from **each origin** (the current page and the demoted page). `runComposeUiTest` on
JVM exercises the unclaimed-host path exactly, since the host lookup is `{ null }` there.

## Out of scope at the time

`HorizontalPager`'s retained off-screen pages (one lifecycle owner, no signal); `Screen Viewed`
re-emission when a Compose page returns from demotion; giving the iOS readers an origin.
