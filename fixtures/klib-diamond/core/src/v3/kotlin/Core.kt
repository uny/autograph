package fixture

/**
 * NEGATIVE CONTROL. Not a change ADR 0001 permits — this is a deliberate ABI break, present only
 * to prove the fixture can go red. `send` gains a required parameter, so the v1 implementor in the
 * old klib no longer satisfies the interface.
 */
public interface Transport {
    public fun send(event: String, priority: Int)

    public fun flush() {
        lastFlushDefaultRan = true
    }
}

public var lastFlushDefaultRan: Boolean = false

public class Envelope internal constructor(public val id: String) {
    public val extra: String = "extra-$id"
}

public object Envelopes {
    public fun of(id: String): Envelope = Envelope(id)
}

public enum class Kind { A, B, C }
