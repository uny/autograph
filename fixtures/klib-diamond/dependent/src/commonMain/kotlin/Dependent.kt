package fixture.dependent

import fixture.Envelope
import fixture.Envelopes
import fixture.Kind
import fixture.Transport

/**
 * Stands in for `SegmentTransport`: an SPI implementor living in a *different* module, compiled
 * against core 1.0. In v1, `Transport` has one member, so this class overrides exactly one.
 */
public class DependentTransport : Transport {
    public var sent: String? = null
    override fun send(event: String) {
        sent = event
    }
}

/** Exercises the §2a value type across the module boundary. */
public fun makeEnvelope(id: String): Envelope = Envelopes.of(id)

public fun envelopeId(e: Envelope): String = e.id

/**
 * An exhaustive `when` over the v1 enum, with no `else`. Compiled against v1 it is total; v2 adds
 * a constant this branch list has never heard of.
 */
public fun describe(k: Kind): String = when (k) {
    Kind.A -> "a"
    Kind.B -> "b"
}
