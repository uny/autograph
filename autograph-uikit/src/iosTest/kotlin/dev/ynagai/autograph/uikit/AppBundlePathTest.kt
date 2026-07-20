package dev.ynagai.autograph.uikit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundle-path containment used to exclude system-framework controllers, tested as pure string
 * logic. The interesting cases are the ones a real device run does not cover: a sibling directory that
 * merely shares a name *prefix* must not count as "under" the app, which is exactly why this compares
 * whole path components rather than doing a raw `startsWith`.
 */
class AppBundlePathTest {

    private val app = "/private/var/containers/Bundle/Application/ABC/MyApp.app"

    @Test
    fun the_app_bundle_itself_is_under_the_app() {
        assertTrue(app.isUnderPathComponents(app))
    }

    @Test
    fun an_embedded_framework_is_under_the_app() {
        // A feature-module framework lives inside the .app — its controllers are the app's screens.
        assertTrue("$app/Frameworks/Feature.framework".isUnderPathComponents(app))
    }

    @Test
    fun a_system_framework_is_not_under_the_app() {
        assertFalse("/System/Library/Frameworks/UIKit.framework".isUnderPathComponents(app))
    }

    @Test
    fun a_sibling_sharing_a_name_prefix_is_not_under_the_app() {
        // A raw startsWith would wrongly accept this; component-wise comparison rejects it.
        assertFalse("/private/var/containers/Bundle/Application/ABC/MyApp.app.extra".isUnderPathComponents(app))
        assertFalse("/private/var/containers/Bundle/Application/ABCDEF/Other.app".isUnderPathComponents(app))
    }

    @Test
    fun a_trailing_slash_on_either_side_does_not_change_the_answer() {
        assertTrue("$app/".isUnderPathComponents(app))
        assertTrue("$app/Frameworks/Feature.framework".isUnderPathComponents("$app/"))
    }
}
