package dev.ynagai.autograph.context

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
 * to prevent. It lives in `autograph-context` because that is the lowest module the sharers depend on.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an Autograph-internal API shared between Autograph's own modules. It is not " +
        "a supported public API and can change or be removed in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class AutographInternalApi
