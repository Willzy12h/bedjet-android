package io.github.willzy12h.nocturne.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import java.time.Instant
import java.time.ZonedDateTime
import java.util.WeakHashMap

/** Local screen consent is not a device setting and must not travel in a backup. */
private fun themePreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences("appearance-consent", Context.MODE_PRIVATE)

private fun Settings.themeSelection() = "$themeMode:$scheduledDarkMinute:$scheduledLightMinute"

private data class ConsentState(
    val lastDark: Boolean? = null,
    val holdSelection: String? = null,
    val holdUntil: Long? = null,
    val approvedSelection: String? = null,
    val approvedUntil: Long? = null,
)

private fun SharedPreferences.readConsent(): ConsentState = all.let {
    ConsentState(it["lastDark"] as? Boolean, it["holdSelection"] as? String, it["holdUntil"] as? Long,
        it["approvedSelection"] as? String, it["approvedUntil"] as? Long)
}

private fun SharedPreferences.writeConsent(state: ConsentState) = edit {
    if (state.lastDark == null) remove("lastDark") else putBoolean("lastDark", state.lastDark)
    putString("holdSelection", state.holdSelection)
    if (state.holdUntil == null) remove("holdUntil") else putLong("holdUntil", state.holdUntil)
    putString("approvedSelection", state.approvedSelection)
    if (state.approvedUntil == null) remove("approvedUntil") else putLong("approvedUntil", state.approvedUntil)
}

/** Values retain neither a Repository nor a Context; the disposable session has no disk backing. */
private class DemoConsent(val revision: Long, initial: ConsentState) {
    var state by mutableStateOf(initial)
}
private val demoConsents = WeakHashMap<Repository, DemoConsent>()

/** Both native dialogs and Compose resolve the same session, including after Activity recreation. */
private class ConsentScope(context: Context, private val displayedDemoMode: Boolean) {
    val preferences = themePreferences(context)
    private val repository = context.graph.repository
    private val revision = repository.sessionRevision.value
    val demo = synchronized(demoConsents) {
        if (repository.isDemoSession && repository.settings.value.demoMode) {
            demoConsents[repository]?.takeIf { it.revision == revision }
                ?: DemoConsent(revision, preferences.readConsent()).also { demoConsents[repository] = it }
        } else null
    }

    fun read(): ConsentState = demo?.state ?: preferences.readConsent()

    fun update(change: (ConsentState) -> ConsentState) {
        // A callback or SideEffect from the outgoing screen cannot change the incoming session.
        if (revision != repository.sessionRevision.value || displayedDemoMode != repository.settings.value.demoMode) return
        if (demo != null) {
            if (repository.isDemoSession && repository.settings.value.demoMode) demo.state = change(demo.state)
        } else if (!repository.isDemoSession) {
            val previous = preferences.readConsent()
            val next = change(previous)
            if (next != previous) preferences.writeConsent(next)
        }
    }
}

/** Native command confirmations stay calm while the main screen still owes a light-theme choice. */
internal fun nativeThemeIsDark(context: Context, settings: Settings, at: ZonedDateTime, systemDark: Boolean): Boolean {
    if (settings.isDarkTheme(at, systemDark)) return true
    val memory = ConsentScope(context, settings.demoMode).read()
    return memory.lastDark == true ||
        (memory.holdSelection == settings.themeSelection() && (memory.holdUntil ?: 0L) > at.toInstant().toEpochMilli())
}

/** A confirmed choice may publish asynchronously; retain its precise selection until that publication arrives. */
internal fun approveLightSelection(context: Context, settings: Settings, at: ZonedDateTime) {
    ConsentScope(context, settings.demoMode).update {
        it.copy(approvedSelection = settings.themeSelection(), approvedUntil = at.toInstant().plusSeconds(60).toEpochMilli(),
            holdUntil = null, holdSelection = null)
    }
}

internal data class ThemePresentation(
    val at: ZonedDateTime,
    val dark: Boolean,
    val lightDue: Boolean,
    val switchNow: () -> Unit,
    val stayDark: () -> Unit,
)

/** No light frame is rendered before consent, including the first frame after a process restart. */
@Composable
internal fun rememberThemePresentation(settings: Settings, now: () -> ZonedDateTime): ThemePresentation {
    val context = LocalContext.current
    val repository = context.graph.repository
    val sessionRevision by repository.sessionRevision.collectAsState()
    val consent = remember(repository, sessionRevision, settings.demoMode) { ConsentScope(context, settings.demoMode) }
    val preferences = consent.preferences
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Reading the revision ties external confirmation writes to this composition without keeping an Activity alive.
    val memory = consent.demo?.state ?: remember(preferences, revision) { preferences.readConsent() }
    val holdUntil = memory.holdUntil?.let(Instant::ofEpochMilli)
    val at = rememberThemeNow(settings, now, holdUntil)
    val requestedDark = settings.isDarkTheme(at, isSystemInDarkTheme())
    val selection = settings.themeSelection()
    val lastDark = memory.lastDark
    val held = memory.holdSelection == selection && holdUntil?.isAfter(at.toInstant()) == true
    val approved = memory.approvedSelection == selection &&
        (memory.approvedUntil ?: 0L) > at.toInstant().toEpochMilli()
    val lightDue = !requestedDark && !held && !approved && lastDark == true
    val dark = requestedDark || held || lightDue
    SideEffect {
        val expiredHold = holdUntil != null && (!held && (memory.holdSelection != selection || !holdUntil.isAfter(at.toInstant())))
        val expiredApproval = memory.approvedUntil != null && memory.approvedUntil <= at.toInstant().toEpochMilli()
        if (lastDark != dark || expiredHold || expiredApproval || (approved && !requestedDark)) {
            consent.update {
                it.copy(lastDark = dark,
                    holdUntil = if (expiredHold) null else it.holdUntil,
                    holdSelection = if (expiredHold) null else it.holdSelection,
                    approvedUntil = if (expiredApproval || (approved && !requestedDark)) null else it.approvedUntil,
                    approvedSelection = if (expiredApproval || (approved && !requestedDark)) null else it.approvedSelection)
            }
        }
    }
    return ThemePresentation(at, dark, lightDue,
        switchNow = {
            consent.update { ConsentState(lastDark = false) }
        },
        stayDark = {
            consent.update {
                it.copy(lastDark = true, holdSelection = selection,
                    holdUntil = nextDarkPeriod(settings, at).toInstant().toEpochMilli())
            }
        },
    )
}

private fun nextDarkPeriod(settings: Settings, at: ZonedDateTime): ZonedDateTime {
    val minute = settings.scheduledDarkMinute
    return (0L..1L).flatMap { day ->
        val boundary = at.toLocalDate().plusDays(day).atTime(minute / 60, minute % 60).atZone(at.zone)
        listOf(boundary.withEarlierOffsetAtOverlap(), boundary.withLaterOffsetAtOverlap())
    }.filter { it.isAfter(at) }.minBy { it.toInstant() }
}

@Composable
internal fun ThemeDuePrompt(theme: ThemePresentation) {
    if (theme.lightDue) AlertDialog(
        onDismissRequest = theme.stayDark,
        title = { Text(stringResource(R.string.theme_light_due)) },
        text = { Text(stringResource(R.string.theme_light_due_help)) },
        confirmButton = { TextButton(onClick = theme.switchNow) { Text(stringResource(R.string.theme_switch_now)) } },
        dismissButton = { TextButton(onClick = theme.stayDark) { Text(stringResource(R.string.theme_stay_dark)) } },
    )
}
