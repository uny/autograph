package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.context.AutographInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.setValue
import platform.UIKit.UIAccessibilityElement
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIButton
import platform.UIKit.UIView
import platform.darwin.NSObject

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
     * A blank identifier is absent, not a target. UIKit's default is nil, so an empty or
     * whitespace-only string is an unset value that arrived through a template or a nil-coalesced
     * binding rather than a name anyone chose. Reported, it produces an event whose target is blank
     * in every dashboard — indistinguishable from an unnamed element except that it looks deliberate.
     *
     * Asserted on both routes, since the two families reach the getter differently.
     */
    @Test
    fun treatsABlankIdentifierAsAbsent() {
        val view = UIView()
        view.setIdentifier("")
        assertNull(view.accessibilityIdentifierOrNull())

        val spaces = UIView()
        spaces.setIdentifier("   ")
        assertNull(spaces.accessibilityIdentifierOrNull())

        val element = UIAccessibilityElement(accessibilityContainer = UIView())
        element.setIdentifier("")
        assertNull(element.accessibilityIdentifierOrNull())
    }

    /** Rejecting blanks must not turn into trimming a name the developer did choose. */
    @Test
    fun doesNotTrimAnIdentifierThatHasContent() {
        val view = UIView()
        view.setIdentifier("  share_button  ")

        assertEquals("  share_button  ", view.accessibilityIdentifierOrNull())
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
