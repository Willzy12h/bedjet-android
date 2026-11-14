package io.github.willzy12h.nocturne;

import android.content.Context;
import android.text.format.DateFormat;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/**
 * The phone's 12- or 24-hour clock setting, for every test (see robolectric.properties). Robolectric's phone is
 * American and would read 12-hour; the tests expect 24-hour times unless one sets {@link #twentyFourHour} to false.
 */
@Implements(DateFormat.class)
public class ShadowPhoneClock {
    public static volatile boolean twentyFourHour = true;

    @Implementation
    public static boolean is24HourFormat(Context context) {
        return twentyFourHour;
    }

    @Resetter
    public static void reset() {
        twentyFourHour = true;
    }
}
