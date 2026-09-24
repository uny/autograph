# #135 — iOS tap capture in a process no accessibility client has touched

- Issue: [#135](https://github.com/uny/autograph/issues/135) (open: its SwiftUI half has no fix from
  public API)
- Shipped in: [#136](https://github.com/uny/autograph/pull/136) (`4ee718f`, 2026-07-26, the Compose
  half), [#190](https://github.com/uny/autograph/pull/190) (`2968ce2`, 2026-08-13, the UIKit half, #189)
  and [#192](https://github.com/uny/autograph/pull/192) (`06b97cc`, 2026-08-13, SwiftUI removed from
  native capture, #191); corrected or extended by [#137](https://github.com/uny/autograph/pull/137)
  (`91c6576`), [#160](https://github.com/uny/autograph/pull/160) (`efe77b6`, #157),
  [#168](https://github.com/uny/autograph/pull/168) (`4491808`, #156) and
  [#172](https://github.com/uny/autograph/pull/172) (`b33c040`, #170)
- Code: `deepestAccessibilityHitPath` and the file header (`autograph-uikit`, `AccessibilityTree.kt`),
  `resolveNativeTapTargetByHitTest` and the file header (`NativeHitTestResolution.kt`),
  `installAutographNativeTapCapture` / `warnOnceIfANativeTapResolvedToNothing`
  (`NativeTapCapture.kt`), and the Compose caller `resolveIosElement` (`autograph-compose`,
  `ElementResolver.ios.kt`)
- Full records: [#189's device measurement](https://github.com/uny/autograph/issues/189),
  [the #191 spike comment](https://github.com/uny/autograph/issues/191#issuecomment-5280843143),
  [the CMP 1.12.0 cold A/B on #224](https://github.com/uny/autograph/issues/224#issuecomment-5644606985)

This is the lab notebook for cold accessibility on iOS. The KDoc on those declarations states the
contract; this file keeps what was measured, what was refuted, and what depends on a particular
library version. Expect parts of it to go stale — that is why it lives here and not in the published
API docs.

Scope: what "cold" is, why Compose capture survives it and native capture only partly does, the
side effect the Compose walk has on CMP, and the one-per-process warning that replaced silence. The
design measurements behind the `hitTest` resolver itself — that it declines a disabled control, the
`UIScrollView` barrier — stay in its KDoc; see *Not here* at the end.

## The problem

UIKit and SwiftUI build the accessibility element tree **on demand**: only once an accessibility
client has asked for it — VoiceOver, Voice Control, the Accessibility Inspector, an XCUITest runner.
Until then every tap on both iOS pipelines resolved to nothing, **for the life of the process**,
through two different mechanisms. For UIKit and SwiftUI the tree the native pipeline hit-tested did not
exist (fact 2). For Compose it did — every bridged element was present and correct — but the view the
walk starts from reported an empty frame, and the walk gave up there (fact 1). The tap itself was
never in doubt: each element's own `onClick` fired. So the failure was an absent value, not a wrong
one, and downstream it read as "nobody tapped this".

It stayed hidden behind green CI by construction. An XCUITest runner is itself an accessibility
client, so the suite establishes the very precondition the code under test needs: on `main` at the
time, `iosAppUITests/testPlainButtonAttribution` passed while a manual tap on the same button in the
same build reported nothing. No assertion added to that suite can catch this.

## The rule that shipped

Three pipelines, three different answers.

1. **Compose Multiplatform: captured cold.** The walk starts from the Compose host's
   `OverlayInputView` and treats that starting node as *where to search*, not as a candidate to filter
   on — containment gates every node except it (#136). Below it, CMP's bridged elements are present
   and correct cold, because CMP builds them on an activation path that reading the tree is enough to
   trigger (fact 5). That is a dependency on a CMP implementation detail, not a guarantee, which is
   why a CMP bump needs a manual cold-device check (#154, `CONTRIBUTING.md`).
2. **UIKit: captured cold, through `hitTest`** (#189). `UIView.hitTest` never consults
   accessibility, and in a cold process it returns the real `UIButton` carrying its
   `accessibilityIdentifier` — the string reported as `target`. It is now the only native resolver.
3. **SwiftUI: not captured at all, warm or cold** (#191). SwiftUI has no per-element backing view for
   `hitTest` to find, and its accessibility tree — the only enumeration it has — is exactly what needs
   a client. The accessibility resolver that used to run behind `hitTest` did name SwiftUI elements,
   but only warm, so what it captured was conditioned on the user running assistive technology or on
   the tap coming from a test runner. That is **biased capture, not partial capture**, and its silence
   elsewhere is indistinguishable from nobody tapping, so it was removed rather than kept for the
   population it served. SwiftUI surfaces are instrumented explicitly.

A native tap that resolves to nothing is **not silent**: the first one in the process logs a single
`NSLog` line saying why (fact 9). Only `NativeHitTestResolution.Unresolved` may spend it; a `Dropped`
tap is the library working as asked (an ignored region, Compose-owned content, a reserved
identifier), and in a hybrid app the first tap is quite likely to be one.

## Measured facts it rests on

Each fact carries the conditions it was measured under; none of them is wider than that.

1. **Compose, cold, the starting node** (#135/#136; freshly created iPhone 17 Pro simulator, iOS 26.2,
   CMP 1.11.1). `OverlayInputView` reports `accessibilityFrame = [0, 0, 0, 0]` cold and
   `[0, 62, 402, 840]` once any client has touched the app, while every bridged element beneath it
   already carries a correct frame, identifier and traits. Gating on the starting node returned
   `null` for three taps in a row in one process. A 12-point fixture matrix, oracle = which `onClick`
   fired: **0/12 → 10/12**, never the element underneath; the two misses were disabled clickables,
   #134's separate gap.
2. **Native, cold, on the simulator** (#137; same simulator setup). From the `UIWindow`:

   | | cold | after any accessibility client has run |
   |---|---|---|
   | `UIWindow.accessibilityFrame` | `[0, 0, 0, 0]` | `[0, 0, 402, 874]` |
   | nodes reachable | 88, **every frame `[0,0,0,0]`** | same views, real frames |
   | `SwiftUI.AccessibilityNode` elements | none | present, with identifiers |
   | any `UIAccessibilityTraitButton` | none | yes |
   | tap on `native_plain_button` | nothing reported | reported |

3. **A physical device is cold the same way** (#189; one iPad Pro 11" 3rd gen, iOS 26.2.1, unplugged,
   rebooted, launched from the home screen, first tap of each process, labels read off the device's
   own screen because an attached Mac warms the state under test):

   | | cold, UIKit button | cold, SwiftUI button | warm (Voice Control on) |
   |---|---|---|---|
   | non-zero `accessibilityFrame` | **0/15** | 0/15 | **15/15** |
   | any traits / button trait | 0 / 0 | 0 / 0 | 3 / 1 |
   | `hitTest` returns | **`UIButton`** | `PlatformGroupContainer` | `UIButton` |
   | …its `accessibilityIdentifier` | **`cold_uikit_button`** | nil | `cold_uikit_button` |

   The *same 15 views* go 0/15 → 15/15 with nothing changed but a client running, which is what makes
   the zero-frame, no-traits signature a valid test for coldness. **How often a real launch stays cold
   is not measured**; this establishes the mechanism on hardware, not a frequency.
4. **No SwiftUI identifier reaches the view hierarchy, cold or warm** (#191 spike; standalone SwiftUI
   app on the simulator). `hitTest` plus the superview walk over a real `UITableView`, a real
   `UICollectionView`, a SwiftUI `List`, `Form`, `Picker` and a plain `Button`: only UIKit-set
   identifiers appear; with VoiceOver on, the output is byte-identical. The nearest miss is `Picker`,
   whose `hitTest` reaches a real, enabled `UISegmentedControl` that does not carry the SwiftUI-set
   identifier.
5. **Why CMP survives cold** (source read, #157/#160; names checked against the klibs). Since CMP 1.8
   (compose-multiplatform-core#1780) the bridge is built on demand, not at layout. The activation call
   site does not distinguish callers: `AccessibilityRoot.accessibilityElements()` calls
   `activateAccessibilityIfNeeded()` for whoever asks, and this walk asks. Two gates sit in front of it,
   neither tied to assistive technology: the traversal itself, and `AccessibilityMediator.isEnabled`,
   which a reversed layer walk at scene setup leaves on unless a `focusable` layer sits above the scene
   — it disables only a scene that could not receive the tap anyway. `focusItemsInRect` and
   `accessibilityHitTest` activate the same way behind the same gate. The pre-1.8 opt-out,
   `AccessibilitySyncOptions` with its `WhenRequiredByAccessibilityServices` default, would have gated
   the bridge behind a running screen reader; #1780 removed it, so consumers cannot turn it off.
   compose-multiplatform-core#2416 (2025-09) added support for UI Automation reaching child elements —
   non-screen-reader clients, deliberately — and #2760 (2026-02) added `accessibilityHitTest` as a third
   entry point. Those are endpoints, not a survey of the commits between them.
6. **The accessibility root is not on the view `LocalUIView` returns** (traced in #37, CMP 1.11.1,
   before #135; setup unrecorded). `LocalUIView.current.accessibilityElements()` was empty, and the root
   was found on a *sibling* subview several levels down (`ComposeContainerView.subviews[2]`,
   `OverlayInputView`, in the traced case — not a contract). Walking `subviews` alongside
   `accessibilityElements` at every `UIView` node finds it wherever it lives. **Not reconciled with
   fact 1**, which names `OverlayInputView` as `LocalUIView.current` itself on the same CMP version;
   the walk finds the root either way, so nothing shipped depends on which is right.
7. **`hitTest` reaches the device's `UIButton` cold, in a controlled A/B** (#190; the same iPad, iOS
   26.6 — a later OS than fact 3 — same protocol). `main` before the fix: `Last event target (none
   yet)`; the fix: `native_first_button`, carrying `{"screen": "FirstScreen"}`. `Screen views` read
   `FirstScreen` on both arms — it fires from the `viewDidAppear:` swizzle, which never touches
   accessibility — so arm A's silence is a dropped tap in a live library, not a dead app.
8. **The `UIAccessibilityContainer` method form does not warm the tree** (#191 spike; controlled A/B,
   same binary, only VoiceOver differing). `accessibilityElementCount()` / `accessibilityElement(at:)`,
   which the walk had never called: cold, 0/4 non-zero frames and the method form returns 0 elements;
   warm, 4/4 and 3, and the target identifier is found. This was the last untried route to a cold
   SwiftUI capture; the option space is closed.
9. **The warning fires on the first `Unresolved` tap, whatever the cause** (#192). Its predecessor
   (#172) first asked whether the whole tree carried the cold signature (fact 3), excluding Compose
   hosts because the walk itself warms them. Once `hitTest` resolved UIKit without reading the tree,
   coldness stopped deciding whether a tap resolved: SwiftUI and untagged controls resolve to nothing
   warm too, and a coldness gate would have told a warm app losing every tap nothing.

## The side effect on CMP (#156)

**Measured, and closed as a non-issue: the walk flips CMP's internal "a screen reader is active"
belief** (`LocalPlatformScreenReader.current.isActive`, set from `AccessibilityRoot`'s `element` setter
on tree sync). Cold simulator (iPhone 17 Pro, iOS 26.2, CMP 1.11.1), one A/B: a single tap that runs
the walk flips it `false → true`; an identical tap through plain Compose with autocapture off leaves it
`false` across repeats.

Expected rather than new: it is fact 5's activation call site, which cannot tell an app from a screen
reader, recording exactly that. Its only reader in the 1.11.1 klib is `isScreenReaderFocusable`, inside
CMP's own focus-traversal code; the flag is `@InternalComposeUiApi`, so no application code downstream
of Compose can observe it. Whether it perturbs real screen-reader traversal order is not independently
measurable: attaching a real AT sets the same flag by itself, so there is no walk-free baseline once
one is attached. The walk cannot create a state a genuine AT visit would not; it can only make CMP
believe that visit came earlier and more often.

## Refuted along the way

Each was believed, some shipped, and each was overturned by a later measurement or source read. None of
them is the rule.

1. **"The native walk's `UIWindow` reports a valid frame when cold."** Written into #136 as the reason
   the starting-node exemption did not reach native capture. Measured false by #137: the window reports
   `[0, 0, 0, 0]` cold too. The conclusion stood; the reason was fact 2.
2. **"CMP bridges its semantics unconditionally, from the first layout pass."** #137's correction of
   (1), itself corrected by #160 (#157): since CMP 1.8 the bridge is built on demand, and "populated at
   layout" was never observable from application code, because reading the tree is what populates it.
   "Unconditional" reads as nothing to watch; the accurate reason names the dependency that makes the
   CMP-bump check necessary.
3. **"The tree is absent under Compose."** An earlier attempt read `LocalUIView.current.accessibilityElements()`
   directly, found it empty, and concluded the bridge was not there. It was on a sibling subview
   (fact 6).
4. **"The zero-frame, no-traits signature is not a valid cold test."** Two independent model reviews
   argued a plain `UIView` is not an accessibility element, or that it reports a real frame even
   cold. The warm arm of fact 3 refutes both: the same 15 views go 0/15 → 15/15.
5. **"A `hitTest` route cannot work, because SwiftUI's backing view does not carry
   `.accessibilityIdentifier`."** An earlier rejection of the route, recorded in #189's body. Right
   about SwiftUI, over-generalised to kill it for UIKit too (fact 3).
6. **"Keep the accessibility resolver behind `hitTest` for warm SwiftUI."** #190 kept it; #192 removed
   it as biased capture (*The rule that shipped*, 3).
7. **"`hitTest` makes a SwiftUI `List`'s identified container shadow its rows."** #191 as filed blamed
   `hitTest` for `native_list` claiming `native_row_2`. In the #191 rig (fact 4) no SwiftUI identifier
   reached the view hierarchy, which points at the warm accessibility walk instead — the XCUITest
   runner had warmed it. The sample-app observation #190 recorded, before the `UIScrollView` barrier
   existed, was not re-run against that rig, so the two have not been reconciled; the barrier's KDoc
   says so.
8. **"Warn only when the tree is cold."** #172's gate, removed by #192 (fact 9).

## Verified on a device

- **UIKit, cold, on hardware**: fact 7 — the controlled A/B on the physical iPad, and the disabled
  `UIButton` in the same session invented no event.
- **Compose, cold, on CMP 1.12.0** ([#224](https://github.com/uny/autograph/issues/224#issuecomment-5644606985),
  before #246 bumped the dependency). Two iPhone 17 Pro / iOS 26.2 simulators created fresh, each used
  once, no accessibility client ever attached. Non-vacuity guard: a throwaway probe logged
  `LocalUIView.current`'s own `accessibilityFrame` at every tap, and it read `0.0x0.0` on all eight
  taps — fact 1's cold signature — so neither arm was warmed by accident.

  | tap | CMP 1.11.1 | CMP 1.12.0 |
  |---|---|---|
  | `plain_button` (first interaction) | `plain_button` | `plain_button` |
  | inner of two nested clickables | `inner_button` | `inner_button` |
  | outer, beside the inner | `outer_container` | `outer_container` |
  | outer, below the inner | `outer_container` | `outer_container` |

  Identical, 4/4. The two apps were confirmed to embed different CMP versions before tapping.

## Trade accepted

SwiftUI taps are not autocaptured, on any device, in any state. The alternative on offer was capturing
them for exactly the users running assistive technology, and for test runs.

UIKit capture requires an `accessibilityIdentifier` on the control; an untagged one resolves to nothing
and spends the warning.

Compose capture rests on CMP's activation path staying indifferent to who asks. A CMP release that gated
it on a running screen reader would silence iOS Compose autocapture with CI fully green.

## Version-dependent

- Facts 1, 5, 6 and the #156 side effect were measured or read on **CMP 1.11.1**. The repository is on
  **CMP 1.12.0** (#246). Re-read on 1.12.0's `ui-iosarm64` klib: `AccessibilityRoot`,
  `activateAccessibilityIfNeeded`, `focusItemsInRect` and `accessibilityHitTest` are present;
  `AccessibilitySyncOptions` is still absent. The gate's source on 1.11.1 was named
  `ComposeSceneMediator.isAccessibilityEnabled`; 1.12.0 has no `isAccessibilityEnabled` and has an
  `isFocusEnabled` instead, the name #160 read on the development branch. That is symbol presence, not
  the wiring: the gate's logic has not been re-read on 1.12.0. What *was* re-measured on 1.12.0 is the
  behaviour — the 4/4 A/B above.
- The #156 flag (`LocalPlatformScreenReader`) and its reader (`isScreenReaderFocusable`) are both
  present in the 1.12.0 klib, by name; the flip itself was not re-measured there.
- The UIKit/SwiftUI facts (2, 3, 4, 7, 8) are iOS behaviour on iOS 26.2 (simulator) and 26.2.1 / 26.6
  (the one iPad). Nothing here has been run on another OS release or another device.

## Pinned by

Coldness itself cannot be pinned in CI: any harness that drives the UI is an accessibility client and
warms the process before the first assertion. What is pinned is each rule's consequence, against
hand-built trees that carry the cold shape.

| rule | test |
|---|---|
| the starting node is not a filter (fact 1) | `AccessibilityTreeTest.resolvesADescendantWhenTheStartingNodeReportsAnEmptyFrame`, `…resolvesADescendantDrawnOutsideTheStartingNodesOwnFrame` |
| the exemption does not loosen the rest of the descent | `AccessibilityTreeTest.doesNotReportAClickableStartingNodeForAPositionOutsideIt`, `…doesNotAttributeToAClickableStartingNodeWhenOnlyItsChildContainsThePosition`, `…returnsNullWhenThePositionMissesTheRoot`, `…stillPrunesAnIntermediateContainerThatDoesNotContainThePosition` |
| `hitTest` names a control with no accessibility state at all, and the fixture is checked cold | `NativeHitTestResolutionTest.resolvesAControlInATreeWithNoAccessibilityStateAtAll` |
| the same, from the pipeline's end | `NativeTapCaptureTest.aTapOnAUIKitControlIsReportedFromAColdTree` |
| a tap visible only on the accessibility tree is not reported (#191) | `NativeTapCaptureTest.aTapVisibleOnlyOnTheAccessibilityTreeIsNoLongerReported` |
| the same, warm, end to end — warm is the condition under which the removed fallback *would* fire | `NativeSampleUITests.testNativeSwiftUIButtonIsNotReported` (`sample-ios`) |
| the warning fires once, and only for `Unresolved` | `NativeTapCaptureTest.anUnresolvedTapWarnsOnce`, `…aSecondCallDoesNotWarnAgain`, `…anUnresolvedTapReachesTheWarning`, `…aTapVetoedAsComposeOwnedDoesNotSpendTheWarning`, `…aTapThatResolvesToNothingDoesSpendTheWarning` |

## Re-verifying after a CMP bump

Run `CONTRIBUTING.md`'s cold-device check: a simulator created fresh (`shutdown`/`boot` does not reset
the accessibility state, and a warmed device is spent), no client attached, one Compose tap as the very
first interaction. Add a controlled arm on the old version and a non-vacuity probe of
`LocalUIView.current`'s `accessibilityFrame`, as #224 did, so a null result is attributable. Then
re-read fact 5's names in the new klib and update *Version-dependent*.

A native change that reads accessibility state again would need the device protocol of fact 3: unplugged,
rebooted, launched from the home screen, results read off the screen.

## Not here

- **The `hitTest` resolver's own design measurements** — a disabled `UIControl` is declined by
  hit-testing outright (so there is no disabled veto), and a `UIScrollView` is a barrier rather than
  an interactive element. Both are in `resolveNativeTapTargetByHitTest`'s and `isNativeInteractive`'s
  KDoc.
- **The disabled-clickable veto on the Compose walk** (#134, `isAccessibilityDisabled`) is not a cold
  phenomenon; see `AutocaptureConfig`'s KDoc.
- **Overlapping siblings on the walk** (#140): [`140-overlap-ordering.md`](140-overlap-ordering.md).
- The user-facing account of all this is the README's iOS capture warning; this file does not restate
  its guidance.
