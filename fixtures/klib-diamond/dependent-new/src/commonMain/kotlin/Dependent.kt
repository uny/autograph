package fixture.dependent

import fixture.Envelope
import fixture.Envelopes
import fixture.Transport

/** Compiled against core 1.1: it overrides flush(), a member core 1.0 does not have. */
public class NewerDependentTransport : Transport {
    public var sent: String? = null
    public var flushed: Boolean = false
    override fun send(event: String) { sent = event }
    override fun flush() { flushed = true }
}

/** Reads `extra`, a property that exists only from core 1.1. */
public fun readExtra(id: String): String = Envelopes.of(id).extra

public fun envelopeIdNew(e: Envelope): String = e.id
