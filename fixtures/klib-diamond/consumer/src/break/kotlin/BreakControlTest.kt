package fixture.consumer

import fixture.Transport
import fixture.dependent.DependentTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NEGATIVE CONTROL. core 1.2 gave `Transport.send` a required parameter, so the implementor in
 * the old klib no longer satisfies the interface. This arm is expected to FAIL — if it ever
 * passes, the fixture has gone inert and every green result above it is worthless.
 */
class BreakControlTest {

    @Test
    fun anActualAbiBreakIsDetected() {
        val t: Transport = DependentTransport()
        t.send("hello", 1)
        assertEquals("hello", (t as DependentTransport).sent)
    }
}
