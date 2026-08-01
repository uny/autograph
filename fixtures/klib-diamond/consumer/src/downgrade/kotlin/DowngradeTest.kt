package fixture.consumer

import fixture.Transport
import fixture.dependent.NewerDependentTransport
import fixture.dependent.readExtra
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reverse diamond: a dependent klib compiled against core 1.1, linked against core 1.0.
 * Everything it uses from 1.1 is simply absent.
 */
class DowngradeTest {

    @Test
    fun overridingAMemberTheOldCoreLacks() {
        val t: Transport = NewerDependentTransport()
        t.send("hello")
        assertEquals("hello", (t as NewerDependentTransport).sent)
    }

    @Test
    fun readingAPropertyTheOldCoreLacks() {
        assertEquals("extra-e1", readExtra("e1"))
    }
}
