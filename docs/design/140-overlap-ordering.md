# #140 — overlapping siblings on the iOS accessibility walk

- Issue: [#140](https://github.com/uny/autograph/issues/140) (closed, option 3: documented, not fixed)
- Shipped in: [#146](https://github.com/uny/autograph/pull/146) (`e7d3893`, 2026-07-30), documentation
  only; narrowed by [#192](https://github.com/uny/autograph/pull/192) (`06b97cc`, #191), which took
  the native pipeline off this walk
- Code: `deepestAccessibilityHitPath` (`autograph-uikit`, `AccessibilityTree.kt`), its one shipped
  caller `resolveIosElement` (`autograph-compose`, `ElementResolver.ios.kt`), and the gap as
  `AutocaptureConfig` describes it (`autograph-compose`, `Autocapture.kt`)
- Full spike record: [the spike comment on #140](https://github.com/uny/autograph/issues/140#issuecomment-5117543732)

This is the lab notebook for the overlap tie-break. The KDoc on those declarations states the
contract; this file keeps what was measured, what was refuted, and what depends on a particular
library version. Expect parts of it to go stale — that is why it lives here and not in the published
API docs.

Scope: which of two overlapping **siblings** the walk names, and why no ranking replaced the one it
uses. The rest of the walk's contract — the starting-node exemption, the clickable-branch preference,
the termination bounds, the scope-container exemption — stays in its KDoc. Why the walk runs at all
in a process no accessibility client has touched is the cold-accessibility topic, not this one.

## The problem

The walk searches a node's children in reverse and takes the first branch that contains the tap, as
a stand-in for "the one drawn on top". Among `subviews` that is true z-order. Among
`accessibilityElements` it is whatever order the provider emitted, and Compose Multiplatform's bridge
does not emit siblings in draw order. So where two sibling clickables overlap and both contain the
tap, the walk can name the one underneath — a **wrong value, not an absent one**. Split out of #134,
whose tap matrix turned it up: `f1_badge`, drawn on top of `f1_row2` and overhanging into it, fired
its own `onClick` while autocapture reported `f1_row2`.

The issue as filed asserted two things about the cause, both of which turned out wrong (*Refuted
along the way*, 1 and 2).

## Measured facts it rests on

Spike on 2026-07-29: iPhone 17 Pro simulator, iOS 26.2, **CMP 1.11.1**. Ten Compose fixtures (G1–G10),
all bridged, the tree flat under one container — these fixtures declared no traversal group; a tree
with one is not flat (#188). Oracle throughout: **which element's `onClick`
actually fired**. Coordinates below are window pixels.

1. **The bridge trims a covered sibling's frame, where the remainder is still a rectangle.** It
   subtracts the occluding sibling's rect from the *covered* sibling's `accessibilityFrame`:

   | element | Compose `boundsInWindow` | reported `accessibilityFrame` |
   |---|---|---|
   | `g3_small` (covered, full-width strip) | (30,858)-(174,1002) | **(30,930)**-(174,1002) |
   | `g8_big` (covered, full-width strip) | (30,1386)-(510,1530) | (30,1386)-(510,**1458**) |
   | `g6_right` (covered, full-height strip) | (300,1866)-(540,2010) | (**420**,1866)-(540,2010) |

   Three fixtures, one per direction (top, bottom, left); no other fixture was trimmed. Where it
   applies, the covered element stops containing the tap, so the overlap is settled before the
   tie-break is consulted. A **corner** overlap leaves an L-shaped remainder: not trimmed, both
   siblings keep full frames, both contain the tap.
2. **The trim follows draw order, not declaration order.** G3 and G7 have identical geometry *and*
   identical declaration order and differ only by `Modifier.zIndex`; only the covered one is trimmed.
3. **The emitted order fits `(left, top)` lexicographically — x-primary.** A fit to nine fixtures,
   not a contract. What they do establish is what it is *not*: not declaration order (G3/G7), and not
   y-primary reading order (`g10_big`, top 2286, is emitted *after* `g10_small`, top 2358).
4. **Bridged elements are not view-backed.** Every one dumps `isUIView=false`
   (`AccessibilityElement` / `AccessibilityRoot`). The whole `UIView` subtree under the Compose host
   is four views — `ComposeContainerView` → `BackgroundInputView` / `SurfaceMetalView` /
   `OverlayInputView` — all zero-framed, none per element. Compose draws into one Metal surface.
5. **The measured native (SwiftUI) tree did not trim.** Measured warm, under an XCUITest runner,
   because a cold native tree is empty and its silence must not be read as "no trim". `n8_top`
   covers `n8_big`'s bottom half with exactly the full-width strip CMP trimmed, and `n8_big` still
   reports the same area as the un-overlapped `n0_solo` (69120). The G1-shaped misattribution reproduced end to end
   through the native resolver of the time (`REPORTED n1_big` / `ORACLE n1_small`). No UIKit
   hierarchy was run.

## The failure condition

**Compose Multiplatform**: the tie-break names the wrong element iff **both** hold —

1. the overlap is **not trimmable** (a corner overlap, or an occluder sitting entirely inside), and
2. the element on top sorts **earlier** under (3): further left, or the same `left` and higher up.

Verified against all ten fixtures. Measured to resolve correctly: a full-width overlay over content
and a horizontal overlap (the trim settles them), and a badge overhanging to the **top-right** (its
corner overlap is not trimmable, but the badge sorts later and wins the tie-break on its own). The
measured failure is a corner overhang **straight up** — the two share a `left`, and the one on top
has the smaller `top`. A strictly *leftward* overhang follows from (3) but was never run.

**UIKit / SwiftUI**: in the one geometry measured, the full-width strip CMP trims, SwiftUI did not
trim (5), so there the tie-break decides an overlap Compose settles. Whether a native tree ever trims
is not established: one SwiftUI geometry is not a sweep, and no UIKit hierarchy was run. If none
does, condition 1 always holds and the walk fails whenever the on-top element sorts earlier —
**strictly broader** than Compose.

## Refuted along the way

1. **"The accessibility tree carries no z-order signal."** #140's central premise. The trim (1) is
   one, and it tracks draw order (2).
2. **"The bridge orders siblings by reading position."** #140's body, and three places in the code
   and CHANGELOG before #146. Refuted by (3): the order is not y-primary.
3. **Recover draw order from the `UIView` hierarchy** (#140's option 2). Dead on (4): there is no
   per-element view to read an order from.
4. **Replace the tie-break with a ranking** (#140's option 1). Scored against the oracle over the
   five ambiguous (two-candidate, untrimmed) cases, all three rules the issue named fail — the status
   quo included:

   | fixture | oracle | last-emitted (the walk) | smallest-area | first-emitted |
   |---|---|---|---|---|
   | G1 | `g1_small` | ✗ | ✓ | ✓ |
   | G2 | `g2_small` | ✓ | ✓ | ✗ |
   | G7 | `g7_small` | ✓ | ✓ | ✗ |
   | G9 | `g9_big` | ✓ | ✗ | ✗ |
   | G10 | `g10_big` | ✓ | ✗ | ✗ |

   Smallest-area was the front-runner and survived a first round only because the fixture built to
   refute it (G3, the larger element on top) had a *full-width* overlap: the trim removed the covered
   element from the candidate set before the rule was tested. A corner overlap with the larger element
   on top (G9, G10) is what killed it. "Most-specific frame", the other ranking #140 floated,
   coincides with smallest-area on every fixture and was not scored separately. The current rule is
   the best of the three, wrong only on G1.
5. **"Untrimmed across a full edge ⇒ on top."** A full-edge overlap is exactly the trimmable case, so
   an untrimmed candidate spanning one cannot be the covered element. With last-emitted as fallback
   it scores 5/5 — but it was fitted after seeing those five points, and on the native tree measured
   nothing was trimmed (5), so "untrimmed" carries no information there. The walk is shared, so the rule
   could not live in it; confining it to the Compose adapter needs the walk to expose its candidate
   set, a public API shape change. Not proposed.
6. **"The measured failure is up and to the left."** Written in the first three commits of #146 from
   the shape pictured while building the fixture; the fixture's coordinates say straight up (a shared
   `left`). Caught in review.

## Why documented rather than fixed

Every candidate rule is refuted (4, 5), and the one alternative mechanism is dead (3). The misreport
also needs a narrow shape on Compose — an untrimmable overlap *and* the on-top element sorting
earlier — so the measured-safe cases (a button inside a clickable row, a top-right badge, a full-width
banner or sheet, a horizontal overlap) cover the common layouts.

At the time there was a second, stronger reason: the native resolver ran this walk twice per tap, and
the `preferClickableBranches = false` pass decided Compose **ownership** — a privacy boundary — so any
tie-break change moved that too. #191 took the native pipeline off this walk (it resolves through
`UIView.hitTest`, which resolves overlaps itself), so that reason no longer binds. The first one still
does.

## Trade accepted

On Compose Multiplatform iOS, a tap on an element overhanging its sibling to the left or straight up,
through an overlap the bridge cannot trim, is reported as the sibling underneath. Wrong, not absent.

## Since #191

The native pipeline no longer uses this walk, so fact (5) no longer reaches it. It still describes the
walk itself, in two places: `resolveIosElement` in `autograph-compose` — the one shipped caller —
descends into UIKit interop (`UIKitView` / `UIKitViewController`) hosted *inside* a composition,
where it walks native nodes; and `deepestAccessibilityHitPath` is public (`@AutographInternalApi`),
so any other caller that walks a UIKit or SwiftUI tree with it may meet the broader condition, as far
as (5) generalizes. No overlap inside interop was measured.

## Version-dependent

Every fact above was measured on **CMP 1.11.1** (iOS 26.2 simulator). The trim, the emitted order and
the absence of per-element views are all bridge implementation details, free to change. The repository
has since moved to CMP 1.12.0 (#246); none of them has been re-measured there.

## Pinned by

The measurements themselves are not pinned in-repo. The spike rig (`ZzMeasure140.kt`, a native
`ZzNativeTrimView`) was temporary and never committed; the trim and the emitted order are bridge
behaviour, and a unit test that sets `accessibilityFrame` by hand cannot observe either.

| property | test |
|---|---|
| the walk's reverse-order tie-break among overlapping subviews | `AccessibilityTreeTest.prefersTheLastChildWhenBoundsOverlap` |
| the tie-break survives the clickable-branch preference when nothing is clickable | `AccessibilityTreeTest.keepsThePreferenceForTheTopmostBranchWhenNoBranchIsClickable` |
| the native resolver is immune: `hitTest` names the view drawn on top | `NativeHitTestResolutionTest.resolvesAnOverlapToTheViewDrawnOnTop` |

## Re-verifying after a CMP bump or a tie-break change

Rebuild the spike's fixtures in a real `.app` (as `sample-ios` hosts one) and read the oracle from
`onClick`, not from the walk. At minimum: one trimmable full-width overlap (does the covered frame
still shrink?), G3/G7 (does the trim still follow `zIndex`?), G10 (is the order still not y-primary?),
and G1 plus G9/G10 (the rule's worst case and smallest-area's). A changed tie-break must be scored
against all five ambiguous cases above, not only the one it was written for.

## Not here

- The cold-accessibility topic — why the walk sees bridged Compose elements in a process no
  accessibility client has touched, why a UIKit/SwiftUI walk sees nothing there, and #189/#191's move
  of the native pipeline onto `hitTest` — has its own notes.
- The *across-groups* half of the tie-break (a node's `accessibilityElements` searched after all of
  its `subviews`) predates #140 (`9fb056d`, #69) and was not part of the spike; it stays in the walk's
  KDoc.
- A `clickable` escaping a traversal group's published frame (#188) is a drop, not a misattribution,
  and is described in `AutocaptureConfig`'s KDoc.
