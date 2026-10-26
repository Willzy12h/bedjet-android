package io.github.willzy12h.nocturne.widget

import io.github.willzy12h.nocturne.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetProviderTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val android = "http://schemas.android.com/apk/res/android"
    @Test fun quickProviderAdvertisesAResizableTwoByOneWithAPreviewAndNoPeriodicWakeup() {
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.quick_controls_widget).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            assertEquals("2", xml.getAttributeValue(android, "targetCellWidth"))
            assertEquals("1", xml.getAttributeValue(android, "targetCellHeight"))
            assertEquals(0, xml.getAttributeIntValue(android, "updatePeriodMillis", -1))
            assertTrue("Real preview layout", xml.getAttributeResourceValue(android, "previewLayout", 0) != 0)
            assertEquals(3, xml.getAttributeIntValue(android, "resizeMode", 0))
        }
    }
    @Test fun singleProviderHasAPreviewAndNoPeriodicWakeup() {
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.single_action_widget).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            assertEquals(0, xml.getAttributeIntValue(android, "updatePeriodMillis", -1))
            assertTrue("Real preview layout", xml.getAttributeResourceValue(android, "previewLayout", 0) != 0)
        }
    }
}
