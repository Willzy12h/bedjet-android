package io.github.willzy12h.nocturne.night

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.AlarmApps
import io.github.willzy12h.nocturne.core.store.Settings

/**
 * The clock apps on this phone: apps that set or show alarms for the person. A calendar or reminder app is not one,
 * so its alarms never move a night.
 */
object ClockApps {
    data class App(val packageName: String, val label: String)

    /**
     * Google Clock and Samsung Clock count even where the phone hides them from the lookup. Samsung's other package
     * names are for display only; they count only when the lookup finds them installed as a clock app.
     */
    val builtIn: Set<String> = Settings().alarmPackages

    fun installed(context: Context): List<App> {
        val pm = context.packageManager
        val found = listOf(AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SHOW_ALARMS).flatMap { action ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.queryIntentActivities(Intent(action), 0)
            }.getOrDefault(emptyList())
        }
        return found.mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            // The names Tonight uses: Samsung and Google both call theirs plain "Clock".
            .map { App(it.packageName, runCatching { alarmSourceName(context, it.packageName) }.getOrDefault(it.packageName)) }
            .let { apps -> distinguished(apps) { name, pkg -> context.getString(R.string.alarm_app_with_package, name, pkg) } }
            .sortedWith(compareBy({ it.packageName !in builtIn }, { it.label.lowercase() }))
    }

    /** Apps that would still share a name are told apart by their package, so the person can see which is which. */
    internal fun distinguished(apps: List<App>, withPackage: (String, String) -> String): List<App> {
        val counts = apps.groupingBy { it.label.trim().lowercase() }.eachCount()
        return apps.map { if ((counts[it.label.trim().lowercase()] ?: 0) > 1) it.copy(label = withPackage(it.label, it.packageName)) else it }
    }

    /** The packages whose alarms count for [choice]; [chosen] names the app for [AlarmApps.ONE_APP]. */
    fun packagesFor(choice: AlarmApps, installed: List<App>, chosen: String? = null): Set<String> = when (choice) {
        AlarmApps.CLOCK_APPS -> builtIn + installed.map { it.packageName }
        AlarmApps.ONE_APP -> setOfNotNull(chosen)
        AlarmApps.NONE -> emptySet()
    }

    /** Settings with [choice] applied, as the person picked it. */
    fun choose(settings: Settings, choice: AlarmApps, installed: List<App>, chosen: String? = null): Settings =
        settings.copy(alarmApps = choice, alarmAppsChosen = true,
            alarmPackages = if (choice == AlarmApps.NONE) settings.alarmPackages else packagesFor(choice, installed, chosen))
            .withoutUnidentifiedAlarms()

    /** The one app chosen for [AlarmApps.ONE_APP], for showing the choice again. */
    fun chosenApp(settings: Settings): String? = settings.alarmPackages.takeIf { settings.alarmApps == AlarmApps.ONE_APP }
        ?.firstOrNull()
}
