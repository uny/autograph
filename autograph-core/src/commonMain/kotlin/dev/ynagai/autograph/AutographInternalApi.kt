package dev.ynagai.autograph

/**
 * Marks a declaration that exists only so Autograph's own modules can share it across a module
 * boundary — not a supported API for library users. It may change or disappear in any release,
 * including a patch.
 *
 * Kotlin's `internal` does not cross module boundaries, so a helper that two Autograph modules must
 * share — the accessibility-tree walk `autograph-uikit` lends `autograph-compose`, or the
 * `ScopeStack.emitScreenView` coupling the iOS and Android native pipelines reuse — has to be
 * `public`. This annotation is how such a declaration says "public for mechanism, not for you": the
 * alternative, each module keeping its own copy, is exactly the duplication these shared helpers exist
 * to prevent. It lives in `autograph-core` because that is the lowest module the sharers depend on —
 * `autograph-core` itself became one when [Envelope]'s constructor was made `internal` and
 * `autograph-test` needed a way to build one anyway.
 *
 * Declarations marked with it sit outside every stability tier in
 * [ADR 0001](../../../../../../../docs/adr/0001-public-api-evolution.md).
 *
 * **It is not the marker for an entry point a library user is told to call.** `installAutographNative*`
 * and the handles they return carried it until #240: the README documents them as the supported way to
 * instrument a native surface, and ADR 0001 lists them as the review-enforced public surface of their
 * artifacts, so the annotation was contradicting both — it forced every adopter to opt out of a
 * guarantee the project had already made, and it is invisible to Swift callers anyway (a
 * `RequiresOptIn` does not reach the Objective-C header) and to the klib dump, so it could not even
 * enforce the boundary it claimed. What belongs here is machinery: [Envelope]'s construction for
 * `autograph-test`, the accessibility-tree walk `autograph-uikit` lends `autograph-compose`, the
 * `ScopeStack` internals the capture pipelines share. If a declaration needs an opt-in because it is
 * *not finished*, that is a different statement and wants its own annotation.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an Autograph-internal API shared between Autograph's own modules. It is not " +
        "a supported public API and can change or be removed in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class AutographInternalApi
