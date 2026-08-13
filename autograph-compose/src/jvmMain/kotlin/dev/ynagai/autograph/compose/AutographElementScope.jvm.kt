package dev.ynagai.autograph.compose

import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject

/**
 * Same node as Android's, and deliberately so even though desktop autocapture resolves nothing yet
 * ([rememberElementResolver] there returns null for every tap): `compose.uiTest` on the JVM is where
 * the semantics half of this API is exercised, against the same `resolveTapAt` Android runs. A stub
 * here would leave the merge rules, the ancestry read and the recomposition behaviour with no test
 * that touches them at all.
 */
internal actual fun Modifier.autographElementScopeMarker(properties: JsonObject): Modifier =
    this then AutocaptureScopeElement(properties)
