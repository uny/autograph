package fixture.consumer

import fixture.Kind
import fixture.Transport
import fixture.lastFlushDefaultRan
import fixture.dependent.DependentTransport
import fixture.dependent.describe
import fixture.dependent.envelopeId
import fixture.dependent.makeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mixed-version diamond. Everything under `fixture.dependent` comes from a klib compiled
 * against core 1.0; `fixture.*` resolves to core 1.1.
 */
class DiamondTest {

    /** ADR 0001 §2c: a default-bodied member added to an SPI, called on an old implementor. */
    @Test
    fun defaultBodiedMemberAddedToSpi() {
        val t: Transport = DependentTransport()
        t.send("hello")
        assertEquals("hello", (t as DependentTransport).sent)

        lastFlushDefaultRan = false
        t.flush()
        assertTrue(lastFlushDefaultRan, "the v1.1 default body should have run")
    }

    /** ADR 0001 §2a: a property added to a class with an internal constructor. */
    @Test
    fun propertyAddedToInternalConstructorClass() {
        val e = makeEnvelope("e1") // constructed inside the OLD klib
        assertEquals("e1", envelopeId(e)) // read back through the OLD klib
        assertEquals("extra-e1", e.extra) // the NEW property, read here
    }

    /** ADR 0001 §2e: a new enum constant, met by an exhaustive `when` compiled without it. */
    @Test
    fun enumConstantAdded() {
        assertEquals("a", describe(Kind.A))
        assertEquals("b", describe(Kind.B))

        val outcome = try {
            "returned:" + describe(Kind.C)
        } catch (t: Throwable) {
            "threw:" + t::class.simpleName
        }
        println("ENUM_C_OUTCOME=$outcome")
    }
}
