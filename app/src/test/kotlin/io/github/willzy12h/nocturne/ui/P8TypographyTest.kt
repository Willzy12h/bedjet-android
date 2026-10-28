package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.willzy12h.nocturne.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class P8TypographyTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun packagedFontLicencesPreserveTheCompleteUpstreamNotices() {
        val expected = java.io.File("../docs/redesign/fonts/FIGTREE-OFL.txt").readText() + "\n\n" +
            java.io.File("../docs/redesign/fonts/BRICOLAGE-OFL.txt").readText()
        assertEquals(expected, org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.font_licences_text))
    }

    @Test fun renderedScreenTitleAndBodyUseDesignFamilies() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme {
                androidx.compose.foundation.layout.Column {
                    Text("Screen heading", style = MaterialTheme.typography.headlineMedium)
                    Text("Body copy", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } }
        ui.compose.waitForIdle()
        fun style(text: String): androidx.compose.ui.text.TextStyle {
            val layouts = mutableListOf<TextLayoutResult>()
            ui.compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        assertEquals(FontFamily(Font(R.font.bricolage_bold, FontWeight.Bold)), style("Screen heading").fontFamily)
        assertEquals(FontFamily(Font(R.font.figtree_regular, FontWeight.Normal), Font(R.font.figtree_medium, FontWeight.Medium),
            Font(R.font.figtree_semibold, FontWeight.SemiBold), Font(R.font.figtree_bold, FontWeight.Bold)), style("Body copy").fontFamily)
    }
}
