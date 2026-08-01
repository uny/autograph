package fixture

public interface Transport {
    public fun send(event: String)

    /** ADDED in v2 — a default-bodied member, which ADR 0001 §2c permits without a major bump. */
    public fun flush() {
        lastFlushDefaultRan = true
    }
}

/** Observable side effect, so the test can tell the default body actually executed. */
public var lastFlushDefaultRan: Boolean = false

public class Envelope internal constructor(public val id: String) {
    /** ADDED in v2 — a property on a class whose constructor is internal (ADR 0001 §2a). */
    public val extra: String = "extra-$id"
}

public object Envelopes {
    public fun of(id: String): Envelope = Envelope(id)
}

/** ADDED in v2 — a new enum constant (ADR 0001 §2e says this is frozen; measuring the cost). */
public enum class Kind { A, B, C }
