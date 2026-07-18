package dev.ynagai.autograph.context

/**
 * The event name an autocaptured tap is reported under when the caller doesn't choose one.
 *
 * Lives here, next to [ScopeStack], because it has the same reason to be shared: a hybrid app runs
 * more than one capture pipeline (Compose, and — per #62/#63 — the native iOS and Android View
 * surfaces), and every one of them reports the same kind of event. If each pipeline carried its own
 * default they could drift apart silently, and the same user action would land in analytics under
 * two different names depending on which framework happened to draw the button. A pipeline that
 * cannot see this constant cannot stay in sync with it, so this module — the one every pipeline
 * already depends on to read ambient context at capture time — is the narrowest place that all of
 * them reach.
 */
public const val DEFAULT_AUTOCAPTURE_EVENT_NAME: String = "Element Clicked"
