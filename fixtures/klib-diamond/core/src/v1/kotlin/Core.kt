package fixture

/**
 * Stands in for autograph's `Transport` — ADR 0001 §2c, a caller-implemented SPI.
 */
public interface Transport {
    public fun send(event: String)
}

/**
 * Stands in for `Envelope` — ADR 0001 §2a, a library-produced value type whose construction is
 * frozen behind an `internal` constructor so fields can be added without an ABI break.
 */
public class Envelope internal constructor(public val id: String)

/** Stands in for the `EnvelopeSource.stamp()` factory: the only way a caller obtains one. */
public object Envelopes {
    public fun of(id: String): Envelope = Envelope(id)
}

/** ADR 0001 §2e — the case set is frozen for the major version. Measured here anyway. */
public enum class Kind { A, B }
