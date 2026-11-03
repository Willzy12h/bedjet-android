package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Changing a covered row must not change the opaque interior of the real lifted StepCard. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-xhdpi")
class EditorDragOcclusionTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 320, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun liftedCardCoversLaterRowInMidnight() = inspect(Palette.MIDNIGHT_INK)
    @Test fun liftedCardCoversLaterRowInOvercast() = inspect(Palette.OVERCAST)

    private fun pixels(area: Rect): IntArray {
        ui.compose.waitForIdle()
        val view = ui.compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val width = area.width.toInt()
            val height = area.height.toInt()
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, area.left.toInt(), area.top.toInt(), width, height) }
        } finally { bitmap.recycle() }
    }

    private fun inspect(palette: Palette) {
        val lowerMode = mutableStateOf(Mode.DRY)
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now(), paletteOverride = palette) {
                Surface(color = palette.tokens.ground) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepCard(Phase(Mode.COOL, 44, 30, 30), "Cool", "Fan 30%", "30 min", Modifier.testTag("drag-front"),
                            canMoveUp = false, canMoveDown = true, onMove = {}, onDrop = {}, onHeightChanged = {}, onClick = {})
                        StepCard(Phase(lowerMode.value, 56, 50, 10), if (lowerMode.value == Mode.DRY) "Dry" else "Heat",
                            "Fan 50%", "10 min", Modifier.testTag("drag-behind"),
                            canMoveUp = true, canMoveDown = false, onMove = {}, onDrop = {}, onHeightChanged = {}, onClick = {})
                    }
                }
            }
        } }
        val front = ui.compose.onNodeWithTag("drag-front").assertIsDisplayed()
        val from = front.fetchSemanticsNode().boundsInRoot
        val behind = ui.compose.onNodeWithTag("drag-behind").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = ui.compose.density.density
        val distance = behind.center.y - from.center.y
        // Well inside the translated card, clear of its rounded corners, shadow and two-degree rotation.
        val interior = Rect(from.left + 24f * density, from.top + distance + 18f * density,
            from.right - 24f * density, from.bottom + distance - 18f * density)
        val exposedDry = pixels(interior)
        ui.compose.runOnIdle { lowerMode.value = Mode.HEAT }
        val exposedHeat = pixels(interior)
        assertFalse("The lower row visibly changes when uncovered", exposedDry.contentEquals(exposedHeat))
        ui.compose.runOnIdle { lowerMode.value = Mode.DRY }
        front.performTouchInput {
            down(Offset(23f * density, centerY))
            advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveBy(Offset(0f, distance))
        }
        ui.compose.waitForIdle()
        ui.capture("drag-over-dry-${palette.name}")
        try {
            val overDry = pixels(interior)
            ui.compose.runOnIdle { lowerMode.value = Mode.HEAT }
            ui.capture("drag-over-heat-${palette.name}")
            val overHeat = pixels(interior)
            assertArrayEquals("$palette: underlying row contents must not bleed through the opaque lifted card", overDry, overHeat)
        } finally { front.performTouchInput { cancel() } }
    }
}
