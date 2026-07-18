package dev.ynagai.autograph.context

import kotlin.test.Test
import kotlin.test.assertEquals

class AutocaptureDefaultsTest {

    /**
     * Pins the wire value, not the constant's existence. Every autocaptured tap a caller doesn't
     * rename lands in their analytics under this exact string, so changing it silently retires the
     * event downstream — dashboards and funnels keep querying the old name and quietly go to zero.
     * A rename is a breaking change for consumers even though nothing in this repo fails to compile,
     * which is precisely why it needs a test to say so. The README documents this same value.
     */
    @Test
    fun defaultEventNameIsTheDocumentedWireValue() {
        assertEquals("Element Clicked", DEFAULT_AUTOCAPTURE_EVENT_NAME)
    }
}
