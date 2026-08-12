package dev.ynagai.autograph.compose

/**
 * THROWAWAY record of #185 **option C**'s triage — delete with the spike. Nothing executes here; the
 * measurement *was* the compiler, and this file exists so the answer is not re-derived.
 *
 * Option C wanted a shared identity rather than geometry or accessibility ancestry: Compose registers
 * `semanticsId -> scope`, and the iOS walk reads the resolved accessibility element's `semanticsId`
 * back off the bridge. It is the cleanest of the options on paper — it publishes nothing, changes no
 * accessibility grouping, and consumes no identifier slot, so it would cost the host app nothing at
 * all. It is also [#68](https://github.com/uny/autograph/issues/68)'s own second reopening condition.
 *
 * **It is dead on the Compose side, so the iOS half was never worth measuring.** The whole scheme
 * needs a `Modifier.Node` to learn *its own* semanticsId through public API. On CMP 1.11.1 it cannot:
 *
 * ```
 * requireLayoutNode().semanticsId
 * //  e: Unresolved reference 'requireLayoutNode'.
 *
 * requireSemanticsInfo().semanticsId
 * //  e: Cannot access 'fun DelegatableNode.requireSemanticsInfo(): SemanticsInfo':
 * //     it is internal in file.
 * //  e: Cannot access 'interface SemanticsInfo : LayoutInfo': it is internal in file.
 * ```
 *
 * `LayoutInfo.semanticsId` *is* public, and `SemanticsInfo` extends `LayoutInfo` — which is exactly
 * the trap. **Bytecode publicness is not Kotlin visibility**: Kotlin `internal` stays `ACC_PUBLIC` in
 * the class file, so `javap` shows `LayoutNode.getSemanticsId()` and `SemanticsInfo` as public and
 * both are unusable. Only the compiler answers this question; see the repo's own note on the same
 * trap in the ABI-validation context.
 *
 * So the blocker #68 named is unchanged in CMP 1.11.1: no public route from a `Modifier.Node` to any
 * `LayoutInfo`. Reopening C needs Compose Multiplatform to make one of `requireSemanticsInfo`,
 * `SemanticsInfo`, or an equivalent node-to-`LayoutInfo` accessor public — not any change here.
 */
@Suppress("unused")
private const val OPTION_C_TRIAGE = "dead: Modifier.Node cannot read its own semanticsId (CMP 1.11.1)"
