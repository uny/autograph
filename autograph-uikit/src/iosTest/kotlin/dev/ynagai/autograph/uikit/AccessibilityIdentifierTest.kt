package dev.ynagai.autograph.uikit

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.setValue
import platform.UIKit.UIAccessibilityElement
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIButton
import platform.UIKit.UIView
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [accessibilityIdentifierOrNull] across the two kinds of object the walk actually meets: the
 * `UIAccessibilityElement` family that Compose Multiplatform bridges its semantics into, and the
 * plain `UIView` family a UIKit/SwiftUI app is built from.
 *
 * Identifiers are set through key-value coding because Kotlin/Native offers no other route on a
 * `UIView` — which is the very gap this file exists to pin.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class AccessibilityIdentifierTest {

    private fun Any.setIdentifier(value: String) {
        (this as NSObject).setValue(value, forKey = "accessibilityIdentifier")
    }

    /**
     * The binding gap, asserted directly so this file explains itself if it ever starts failing.
     *
     * In Objective-C `UIView` adopts `UIAccessibilityIdentification` via a category, and
     * Kotlin/Native's cinterop does not model category-added conformance — so the protocol cast that
     * used to be this function's only route is statically and dynamically false for every UIKit view.
     * Should a future Kotlin version start modelling it, this assertion flips and the fallback below
     * becomes redundant rather than load-bearing; that is worth being told about.
     */
    @Test
    fun uiViewDoesNotConformToTheIdentificationProtocolInKotlinNative() {
        assertFalse(UIView() is UIAccessibilityIdentificationProtocol)
        assertFalse(UIButton() is UIAccessibilityIdentificationProtocol)
        assertTrue(UIAccessibilityElement(accessibilityContainer = UIView()) is UIAccessibilityIdentificationProtocol)
    }

    /**
     * The regression this fix is for: before the key-value fallback, every `UIView` answered null and
     * the native pipeline resolved nothing at all — a `UIButton` would pass the clickability
     * predicate and then be dropped for having no identifier.
     */
    @Test
    fun readsTheIdentifierOffAPlainUIView() {
        val view = UIView()
        view.setIdentifier("share_button")

        assertEquals("share_button", view.accessibilityIdentifierOrNull())
    }

    @Test
    fun readsTheIdentifierOffAUIKitControl() {
        val button = UIButton()
        button.setIdentifier("submit")

        assertEquals("submit", button.accessibilityIdentifierOrNull())
    }

    /** The Compose path: unchanged, and still served by the protocol route. */
    @Test
    fun readsTheIdentifierOffABridgedAccessibilityElement() {
        val element = UIAccessibilityElement(accessibilityContainer = UIView())
        element.setIdentifier("compose_tag")

        assertEquals("compose_tag", element.accessibilityIdentifierOrNull())
    }

    @Test
    fun returnsNullWhenNoIdentifierWasSet() {
        assertNull(UIView().accessibilityIdentifierOrNull())
        assertNull(UIAccessibilityElement(accessibilityContainer = UIView()).accessibilityIdentifierOrNull())
    }

    /**
     * The walk hands this function whatever the tree contains, so it must stay total.
     *
     * An Objective-C exception crossing into Kotlin is not catchable there — it terminates the
     * process. That is why the fallback asks `respondsToSelector` before calling the getter rather
     * than trying and recovering, and why this test asserts on objects that answer no to it: an
     * `NSObject` without the property, and values that are not Objective-C objects at all.
     */
    @Test
    fun returnsNullRatherThanThrowingForAnObjectWithoutTheProperty() {
        assertNull(NSObject().accessibilityIdentifierOrNull())
        assertNull(("a plain string" as Any).accessibilityIdentifierOrNull())
        assertNull((42 as Any).accessibilityIdentifierOrNull())
    }
}
