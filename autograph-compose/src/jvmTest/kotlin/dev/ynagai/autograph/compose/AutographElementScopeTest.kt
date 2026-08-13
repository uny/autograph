package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers [AutographElementScope] on the semantics half — the Android/JVM actual, which writes the
 * same node [autocaptureScope] does, so what is pinned here is that wrapping produces the attribution
 * the modifier did plus the shapes the modifier could not express.
 *
 * **In `jvmTest`, not `commonTest`, and that is the honest placement rather than a build detail.**
 * The iOS actual deliberately writes something else — a traversal group and a reserved `testTag` —
 * because the UIKit bridge carries no custom semantics, so `resolveTapAt` finds no scope there and
 * these assertions genuinely fail on iOS. Left in `commonTest` they would have had to be weakened
 * until they no longer said anything on either platform.
 *
 * **No test in this repo can see the iOS half.** Whether Compose Multiplatform bridges a traversal
 * group into an accessibility *ancestor* is a property of the bridge, which neither
 * `autograph-uikit`'s hand-built `UIView` trees nor `compose.uiTest`'s iOS scene populates — an
 * assertion about it would pass without exercising it. That half is measured on device: see
 * `ZzMeasure185.kt` on the spike branch, and the checklist on the pull request.
 *
 * As in [AutocaptureScopeTest], the hit test is real: nothing tells the resolver which element was
 * meant, so a scope collected off the wrong element's ancestry fails these rather than passing them.
 */
@OptIn(ExperimentalTestApi::class)
class AutographElementScopeTest {

    private fun ComposeUiTest.centreOf(tag: String): Offset =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center

    private fun ComposeUiTest.semanticsRoot(): SemanticsNode = onRoot(useUnmergedTree = true).fetchSemanticsNode()

    @Test
    fun scopesATapOnTheWrappedElement() = runComposeUiTest {
        setContent {
            AutographElementScope("article_id" to "42") {
                Box(Modifier.testTag("row").size(20.dp).clickable {})
            }
        }
        waitForIdle()

        val target = semanticsRoot().resolveTapAt(centreOf("row"))
        assertEquals("row", target?.identifier)
        assertEquals("42", target?.scope?.get("article_id")?.jsonPrimitive?.content)
    }

    /**
     * The shape the API exists for, and the one a positional or mount-order design cannot do: the
     * same composable mounted many times over different data, each tap carrying its own row's scope.
     */
    @Test
    fun siblingRowsAttributeToTheirOwnScope() = runComposeUiTest {
        setContent {
            Column {
                for (id in listOf("a", "b", "c")) {
                    AutographElementScope("article_id" to id) {
                        Box(Modifier.testTag("row_$id").size(20.dp).clickable {})
                    }
                }
            }
        }
        waitForIdle()

        for (id in listOf("a", "b", "c")) {
            val target = semanticsRoot().resolveTapAt(centreOf("row_$id"))
            assertEquals("row_$id", target?.identifier)
            assertEquals(id, target?.scope?.get("article_id")?.jsonPrimitive?.content, "row_$id")
        }
    }

    @Test
    fun nestedScopesMergeWithTheInnerWinningAKeyClash() = runComposeUiTest {
        setContent {
            AutographElementScope("section" to "feed", "article_id" to "outer") {
                AutographElementScope("article_id" to "inner") {
                    Box(Modifier.testTag("row").size(20.dp).clickable {})
                }
            }
        }
        waitForIdle()

        val scope = assertNotNull(semanticsRoot().resolveTapAt(centreOf("row"))?.scope)
        assertEquals("feed", scope["section"]?.jsonPrimitive?.content)
        assertEquals("inner", scope["article_id"]?.jsonPrimitive?.content)
    }

    /**
     * A `clickable` drawn outside the wrapper's own bounds is still scoped — an overhanging badge, an
     * `offset` decoration. Semantics ancestry is structural here, so this holds on Android for free;
     * it is the case the iOS walk needed its containment-gate exemption for, and stating it as a test
     * is what makes the two platforms answerable to the same promise.
     */
    @Test
    fun scopesAClickableDrawnOutsideTheWrappersOwnBounds() = runComposeUiTest {
        setContent {
            AutographElementScope("article_id" to "42") {
                Box(Modifier.size(20.dp)) {
                    Box(
                        Modifier
                            .testTag("badge")
                            .offset(x = 30.dp, y = 30.dp)
                            .requiredSize(20.dp)
                            .clickable {},
                    )
                }
            }
        }
        waitForIdle()

        val target = semanticsRoot().resolveTapAt(centreOf("badge"))
        assertEquals("badge", target?.identifier)
        assertEquals("42", target?.scope?.get("article_id")?.jsonPrimitive?.content)
    }

    /**
     * The wrapper must not become the tap's name. On Android nothing tempts it to — the wrapper has no
     * `testTag` and no click action — but the iOS actual puts a reserved identifier on this very node,
     * so the two platforms have to agree that the element keeps its own name.
     */
    @Test
    fun theWrappedElementKeepsItsOwnIdentifier() = runComposeUiTest {
        setContent {
            AutographElementScope("article_id" to "42") {
                Box(Modifier.testTag("row").size(20.dp).clickable {})
            }
        }
        waitForIdle()

        assertEquals("row", semanticsRoot().resolveTapAt(centreOf("row"))?.identifier)
    }

    @Test
    fun anEmptyScopeContributesNothing() = runComposeUiTest {
        setContent {
            AutographElementScope {
                Box(Modifier.testTag("row").size(20.dp).clickable {})
            }
        }
        waitForIdle()

        assertEquals(EmptyJsonObject, semanticsRoot().resolveTapAt(centreOf("row"))?.scope)
    }
}
