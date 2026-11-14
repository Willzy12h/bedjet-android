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
 * The apps whose alarms can set a wake-up. Clock apps (apps that set or show alarms for the person) count by default;
 * any other app, such as a white-noise or sleep app, counts only once the person ticks it, so a calendar or reminder
 * alarm never moves a night by itself.
 */
object ClockApps {
    /** [clock] apps tell Android they set or show alarms; [nextAlarm] is set for the app that set the phone's next alarm. */
    data class App(val packageName: String, val label: String, val clock: Boolean = true, val seen: Boolean = false,
        val nextAlarm: Boolean = false)

    /** Permissions an app asks for when it sets alarms of its own (sleep, white-noise and timer apps among them). */
    private val alarmPermissions = setOf("android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM",
        "com.android.alarm.permission.SET_ALARM")

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

    /**
     * Apps that aren't clock apps but may set alarms: those Android has named as setting the phone's next alarm
     * ([seen], newest knowledge from [SeenAlarmApps]) and, when [includeAll], every launchable app that asks for
     * permission to set alarms. Android never lists which apps have alarms set, so this is the closest it allows.
     * An app the person has [ticked] is always listed, seen or not, so its tick stays in view and can be removed.
     * Slow on a phone with many apps: call it off the main thread.
     */
    fun otherAlarmApps(context: Context, clockApps: List<App>, nextAlarmCreator: String?, includeAll: Boolean,
        ticked: Set<String> = emptySet()): List<App> {
        val pm = context.packageManager
        val clock = clockApps.map { it.packageName }.toSet() + builtIn + samsungClockPackages
        val seen = SeenAlarmApps.all(context) + listOfNotNull(nextAlarmCreator)
        val requesting = if (!includeAll) emptySet() else launchable(context).filterTo(mutableSetOf()) { pkg ->
            runCatching {
                val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                info.requestedPermissions.orEmpty().any { it in alarmPermissions }
            }.getOrDefault(false)
        }
        return merge(clock, seen, requesting + ticked, nextAlarmCreator, context.packageName) {
            runCatching { alarmSourceName(context, it) }.getOrDefault(it)
        }.let { apps -> distinguished(apps) { name, pkg -> context.getString(R.string.alarm_app_with_package, name, pkg) } }
    }

    /** Joins seen and permission-requesting apps, without clock apps or Nocturne; seen first, then by name. */
    internal fun merge(clock: Set<String>, seen: Collection<String>, requesting: Set<String>, nextAlarmCreator: String?,
        self: String, label: (String) -> String): List<App> =
        (seen + requesting).distinct()
            .filter { it.isNotBlank() && it != "android" && it != self && it !in clock }
            .map { App(it, label(it), clock = false, seen = it in seen, nextAlarm = it == nextAlarmCreator) }
            .sortedWith(compareBy({ !it.seen }, { it.label.lowercase() }))

    /** Calendar apps, whose reminders Tonight ignores without comment (see the manifest's calendar query). */
    fun calendarApps(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/event")
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList()).mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
    }

    private fun launchable(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList()).mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
    }

    /** The packages whose alarms count for [choice]. With [AlarmApps.CLOCK_APPS], [chosen] adds other apps to the
     * clock apps; with [AlarmApps.ONE_APP] ("only the apps you tick") it is the whole list. */
    fun packagesFor(choice: AlarmApps, installed: List<App>, chosen: Set<String> = emptySet()): Set<String> = when (choice) {
        AlarmApps.CLOCK_APPS -> builtIn + installed.map { it.packageName } + chosen
        AlarmApps.ONE_APP -> chosen
        AlarmApps.NONE -> emptySet()
    }
    fun packagesFor(choice: AlarmApps, installed: List<App>, chosen: String?): Set<String> =
        packagesFor(choice, installed, setOfNotNull(chosen))

    /** Settings with [choice] applied, as the person picked it. */
    fun choose(settings: Settings, choice: AlarmApps, installed: List<App>, chosen: Set<String>): Settings =
        settings.copy(alarmApps = choice, alarmAppsChosen = true,
            alarmPackages = if (choice == AlarmApps.NONE) settings.alarmPackages else packagesFor(choice, installed, chosen))
            .withoutUnidentifiedAlarms()
    fun choose(settings: Settings, choice: AlarmApps, installed: List<App>, chosen: String? = null): Settings =
        choose(settings, choice, installed, setOfNotNull(chosen))

    /** The other apps added to "any clock app": everything counted that isn't a clock app. */
    fun extraApps(settings: Settings, installed: List<App>): Set<String> =
        if (settings.alarmApps != AlarmApps.CLOCK_APPS) emptySet()
        else settings.alarmPackages - packagesFor(AlarmApps.CLOCK_APPS, installed.filter { it.clock }) - samsungClockPackages

    /** The apps ticked for [AlarmApps.ONE_APP], for showing the choice again. */
    fun chosenApps(settings: Settings): Set<String> =
        if (settings.alarmApps == AlarmApps.ONE_APP) settings.alarmPackages else emptySet()

    /** Whether [app] shows as ticked: every clock app under "any clock app", otherwise the apps that count. */
    fun isTicked(settings: Settings, app: App): Boolean = when (settings.alarmApps) {
        AlarmApps.CLOCK_APPS -> app.clock || app.packageName in settings.alarmPackages
        AlarmApps.ONE_APP -> app.packageName in settings.alarmPackages
        AlarmApps.NONE -> false
    }

    /**
     * The choice after tapping [app]'s tick box, given the [clockApps] on show. Unticking a clock app under "any clock
     * app" keeps every other tick; ticking an app under "no alarms" uses that app alone.
     */
    fun afterToggle(settings: Settings, clockApps: List<App>, app: App): Pair<AlarmApps, Set<String>> {
        val pkg = app.packageName
        fun Set<String>.flip() = if (pkg in this) this - pkg else this + pkg
        return when (settings.alarmApps) {
            AlarmApps.CLOCK_APPS -> if (app.clock) AlarmApps.ONE_APP to (onlyTicked(settings, clockApps).second - pkg)
                else AlarmApps.CLOCK_APPS to extraApps(settings, clockApps).flip()
            AlarmApps.ONE_APP -> AlarmApps.ONE_APP to settings.alarmPackages.flip()
            AlarmApps.NONE -> AlarmApps.ONE_APP to setOf(pkg)
        }
    }

    /** "Only the apps I tick", starting from what is ticked now (or, after "no alarms", what was ticked before). */
    fun onlyTicked(settings: Settings, clockApps: List<App>): Pair<AlarmApps, Set<String>> = AlarmApps.ONE_APP to when (settings.alarmApps) {
        AlarmApps.CLOCK_APPS -> clockApps.map { it.packageName }.toSet() + extraApps(settings, clockApps)
        else -> settings.alarmPackages
    }

    /** The one app chosen for [AlarmApps.ONE_APP], for showing the choice again. */
    fun chosenApp(settings: Settings): String? = chosenApps(settings).firstOrNull()
}
