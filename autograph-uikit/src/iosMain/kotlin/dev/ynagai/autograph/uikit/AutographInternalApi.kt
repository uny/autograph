package dev.ynagai.autograph.uikit

/**
 * Marks a declaration that exists only so Autograph's own modules can share it across a module
 * boundary — not a supported API for library users. It may change or disappear in any release,
 * including a patch.
 *
 * Kotlin's `internal` doesn't cross module boundaries, so the accessibility-tree walk this module
 * holds has to be `public` for `autograph-compose` to reuse it. The alternative — letting each
 * module keep its own copy of the walk — is what this module exists to prevent: the walk's two
 * known coordinate-space bugs were each found only by exercising the real path on a device, and a
 * second copy would be a second place for them to come back.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an Autograph-internal API shared between Autograph's own modules. It is not " +
        "a supported public API and can change or be removed in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class AutographInternalApi
