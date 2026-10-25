@file:OptIn(AutographInternalApi::class, ExperimentalObjCRefinement::class)

package dev.ynagai.autograph

import kotlinx.serialization.json.JsonElement
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * A transport that runs [transform] on every event before handing it to this one: to redact a
 * property, add one, or drop the event (return null) for sampling (#302).
 *
 * ```kotlin
 * val tracker = Autograph {
 *     transport(SegmentTransport(analytics).intercept { event -> event.withProperties(event.properties - "email") })
 * }
 * ```
 *
 * Prefer this to a hand-written [Transport] wrapper. The returned transport forwards every member —
 * [Transport.stampsInPipeline], [Transport.connect], [Transport.flush], [Transport.reset] — and routes
 * both of an event's overloads through [transform] exactly once, so an event that carries
 * [EventMetadata] is transformed like any other and keeps its metadata. It implements
 * [MetadataAwareTransport] exactly when this transport does (looking through [DebugTransport] and other
 * `intercept` layers), so the tracker warns about a lost capability once and names the transport that
 * loses it, as it does for this transport unwrapped. A delegate written in Swift receives nested values
 * as their JSON text, as it does from the tracker itself (#299).
 *
 * Reach for the tracker's own hooks first: a key every event needs belongs in [DefaultProperties], and
 * a rule about which events are allowed belongs in [AutographConfig.validator]. Both run before this.
 *
 * **What [transform] sees and may change.** Each `track`, `screen` and `identify` call becomes one
 * [TransportEvent]. Only the properties of the event [transform] returns are read: the type and name
 * (the user id, for `identify`) cannot change, and the [Envelope] and [EventMetadata] travel past
 * [transform] untouched. [transform] runs after [AutographConfig.validator], so what it returns is not
 * validated again.
 *
 * **Dropping an event.** On a transport the core stamps for (`stampsInPipeline == false`, such as
 * `SegmentTransport` on iOS) the envelope was stamped before [transform] runs, so a dropped event
 * leaves a gap in `seq`. On a pipeline transport (`SegmentTransport` on Android) nothing is stamped
 * for it, so it leaves none. A warehouse that reads `seq` gaps as loss counts sampled events as lost
 * on the first kind only.
 *
 * **Threading.** [transform] runs on the caller's thread for a pipeline transport and on the tracker's
 * serial dispatcher otherwise, so it must be thread-safe and fast. If it throws, the event is not
 * delivered and the tracker reports the failure through [AutographConfig.logger], as it does for a
 * transport that throws.
 *
 * Kotlin only: hidden from Swift and Objective-C. A Swift closure would receive [TransportEvent.properties]
 * as a `[String: JsonElement]` dictionary and trap on a nested value (#299).
 */
@HiddenFromObjC
public fun Transport.intercept(transform: (TransportEvent) -> TransportEvent?): Transport =
    if (innermost() is MetadataAwareTransport) {
        MetadataAwareInterceptingTransport(this, transform)
    } else {
        InterceptingTransport(this, transform)
    }

/**
 * One event as [intercept]'s transform sees it. Produced by the library, never constructed by callers
 * (ADR 0001 §2a), so it can gain properties as additions.
 */
@HiddenFromObjC
public class TransportEvent internal constructor(
    /** Which call produced the event: one of [TransportEventTypes]. */
    public val type: String,
    /** The event or screen name, or the user id for [TransportEventTypes.IDENTIFY]. */
    public val name: String,
    /** The event's properties, or the traits for [TransportEventTypes.IDENTIFY]. */
    public val properties: Map<String, JsonElement>,
) {
    /** This event with [properties] in place of its own. */
    public fun withProperties(properties: Map<String, JsonElement>): TransportEvent = TransportEvent(type, name, properties)

    override fun toString(): String = "TransportEvent(type=$type, name=$name, properties=$properties)"
}

/**
 * The values [TransportEvent.type] takes. String constants rather than an enum, so a new type is an
 * addition rather than a break (ADR 0001 §2e).
 */
@HiddenFromObjC
public object TransportEventTypes {
    public const val TRACK: String = "track"
    public const val SCREEN: String = "screen"
    public const val IDENTIFY: String = "identify"
}

/**
 * The first transport under this one, through any nesting of the library's own wrappers
 * ([DebugTransport] and [intercept]), that is not one of them: the transport whose capabilities decide
 * what happens to an event's metadata. A wrapper the app wrote is not looked through.
 */
internal fun Transport.innermost(): Transport = when (this) {
    is DebugTransport -> wrapped.innermost()
    is InterceptingTransport -> delegate.innermost()
    else -> this
}

internal open class InterceptingTransport(
    val delegate: Transport,
    private val transform: (TransportEvent) -> TransportEvent?,
) : Transport {

    override val stampsInPipeline: Boolean get() = delegate.stampsInPipeline

    override fun connect(envelopes: EnvelopeSource) {
        delegate.connect(envelopes)
    }

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        val props = transformed(TransportEventTypes.TRACK, name, properties) ?: return
        delegate.track(name, props, envelope)
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        val props = transformed(TransportEventTypes.SCREEN, name, properties) ?: return
        delegate.screen(name, props, envelope)
    }

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {
        val props = transformed(TransportEventTypes.IDENTIFY, userId, traits) ?: return
        delegate.identify(userId, props, envelope)
    }

    override fun flush() {
        delegate.flush()
    }

    override fun reset() {
        delegate.reset()
    }

    /** The properties [transform] returns for this event, ready for [delegate], or null to drop it. */
    protected fun transformed(type: String, name: String, properties: Map<String, JsonElement>): Map<String, JsonElement>? {
        val event = transform(TransportEvent(type, name, properties.asJsonObject())) ?: return null
        return event.properties.asJsonObject().deliverableTo(delegate)
    }
}

/**
 * The [InterceptingTransport] for a delegate that can take an event's metadata. Chosen at construction,
 * because a wrapper chain is immutable: the capability is declared exactly when it is real.
 */
internal class MetadataAwareInterceptingTransport(
    delegate: Transport,
    transform: (TransportEvent) -> TransportEvent?,
) : InterceptingTransport(delegate, transform), MetadataAwareTransport {

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) {
        val props = transformed(TransportEventTypes.TRACK, name, properties) ?: return
        val aware = delegate as? MetadataAwareTransport
        if (aware != null) aware.track(name, props, envelope, metadata) else delegate.track(name, props, envelope)
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) {
        val props = transformed(TransportEventTypes.SCREEN, name, properties) ?: return
        val aware = delegate as? MetadataAwareTransport
        if (aware != null) aware.screen(name, props, envelope, metadata) else delegate.screen(name, props, envelope)
    }
}
