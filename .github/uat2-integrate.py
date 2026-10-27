"""Exact integration edits, verified in a disposable checkout before the branch receives them."""
from pathlib import Path
C='core/src/main/kotlin/io/github/willzy12h/nocturne/core/'
A='app/src/main/kotlin/io/github/willzy12h/nocturne/'
T='app/src/test/kotlin/io/github/willzy12h/nocturne/'
def edit(path, old, new, count=1):
    p=Path(path);s=p.read_text()
    assert s.count(old)==count,(path,s.count(old),old[:100])
    p.write_text(s.replace(old,new))

edit(C+'store/Settings.kt','    val quickControlsCollapsed: Boolean = false,', '''    val quickControlsCollapsed: Boolean = false,
    /** Explicit mode overrides; absent entries retain legacy Warm/Cool values losslessly. */
    val quickDefaults: List<ActionPreset> = emptyList(),
    val setupChecklistCompleted: Boolean = false,
    val setupDismissedOptional: Set<String> = emptySet(),''')
edit(C+'store/SetupChecklist.kt','(reopened || item.key !in settings.setupDismissedOptional)',
     'item.key !in settings.setupDismissedOptional')
edit(C+'store/BackupValidator.kt','        val settings = bundle.settings\n', '''        val settings = bundle.settings
        if (!QuickDefaults.validOverrides(settings.quickDefaults)) invalid("settings.quickDefaults")
        if (settings.setupDismissedOptional.any { it !in setOf("health", "widget") }) invalid("settings.setupDismissedOptional")
''')
edit(A+'ui/StatusHeader.kt','CONNECTING(R.string.status_connecting,','CONNECTING(R.string.uat_connecting,')
edit('app/src/main/res/values/strings_uat2.xml','</resources>',
     '    <string name="uat_connecting">Connecting…</string>\n</resources>')

edit(A+'ui/AppViewModel.kt','    val settings = repo.settings\n', '''    val settings = repo.settings
    private val _setupChecklistOpen = MutableStateFlow(false)
    val setupChecklistOpen = _setupChecklistOpen.asStateFlow()
    private val _setupChecklistRequest = MutableStateFlow(0)
    val setupChecklistRequest = _setupChecklistRequest.asStateFlow()
    fun openSetupChecklist() {
        updateSettings { it.copy(setupDismissedOptional = emptySet()) }
        _setupChecklistOpen.value = true
        _setupChecklistRequest.value += 1
    }
    fun closeSetupChecklist() { _setupChecklistOpen.value = false }
''')
edit(A+'ui/MainActivity.kt','    var tab by rememberSaveable { mutableStateOf(Tab.TONIGHT) }', '''    var tab by rememberSaveable { mutableStateOf(Tab.TONIGHT) }
    val setupRequest by model.setupChecklistRequest.collectAsState()
    LaunchedEffect(setupRequest) { if (setupRequest > 0) tab = Tab.TONIGHT }''')

path=A+'ui/TonightScreen.kt';p=Path(path);s=p.read_text()
start=s.index('/** What the command owner is doing:')
end=s.index('/** Where a wake label sits:',start)
s=s[:start]+s[end:]
s=s.replace('    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {',
'''    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {''',1)
a='\n}\n\n/** Where a wake label sits:'
assert s.count(a)==1
s=s.replace(a,'\n    BusyIndicator(model)\n    }\n}\n\n/** Where a wake label sits:',1)
a='            // Long fallback explanations must remain readable without consuming the controls\' viewport.'
assert s.count(a)==1
s=s.replace(a,'            SetupChecklistCard(model)\n'+a,1)
assert s.count('            BusyIndicator(model)\n')==1
s=s.replace('            BusyIndicator(model)\n','',1)
a='        if (!collapsed) QuickButtons(model, settings, guard)'
assert s.count(a)==1
s=s.replace(a,'        if (!collapsed) ModeQuickButtons(model, settings, guard)',1)
start=s.index('/** Active markers follow a reported mode;')
end=s.index('@Composable\nprivate fun Note',start)
s=s[:start]+s[end:]
p.write_text(s)

edit(A+'ui/SettingsScreen.kt','    var snapshotLabel by remember { mutableStateOf("") }',
     '    var snapshotLabel by remember { mutableStateOf("") }\n    var quickDefaultsOpen by remember { mutableStateOf(false) }')
edit(A+'ui/SettingsScreen.kt','        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)',
'''        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = model::openSetupChecklist, modifier = Modifier.testTag("open-setup-checklist")) {
            Text(stringResource(R.string.uat_setup_title))
        }
        OutlinedButton(onClick = { quickDefaultsOpen = true }, modifier = Modifier.testTag("open-quick-defaults")) {
            Text(stringResource(R.string.uat_quick_defaults))
        }
        if (quickDefaultsOpen) AlertDialog(onDismissRequest = { quickDefaultsOpen = false },
            title = { Text(stringResource(R.string.uat_quick_defaults)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickDefaultsSection(model, settings)
            } }, confirmButton = { TextButton(onClick = { quickDefaultsOpen = false }) { Text(stringResource(R.string.uat_setup_done)) } })''')
edit(A+'ui/SettingsScreen.kt','Format.clock(next.time), next.creator ?: stringResource(R.string.settings_unknown_app)',
     'Format.clock(next.time), io.github.willzy12h.nocturne.night.alarmSourceName(context, next.identifiedCreator)')
edit(A+'ui/SettingsScreen.kt','            if (next?.creator != null && !next.counts) {',
'''            if (next != null) Text(stringResource(R.string.uat_alarm_raw_creator,
                next.creator ?: stringResource(R.string.uat_alarm_null_creator)),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("alarm-raw-creator"))
            if (next?.creator != null && !next.counts) {''')
path=A+'night/AlarmExplanation.kt';p=Path(path);s=p.read_text()
s=s.replace('"com.sec.android.app.clockpackage" ->','in samsungClockPackages ->')
s=s.replace('null -> context.getString(R.string.alarm_source_unknown_app)',
            'null, "", "android" -> context.getString(R.string.uat_alarm_unidentified)')
s=s.replace('alarmSourceName(context, alarm?.creator)','alarmSourceName(context, alarm?.identifiedCreator)')
s=s.replace('alarmSourceName(context, found.creator)','alarmSourceName(context, found.identifiedCreator)')
assert s!=p.read_text();p.write_text(s)

edit(A+'ui/SetupChecklistCard.kt','import android.appwidget.AppWidgetManager',
     'import android.app.ActivityManager\nimport android.appwidget.AppWidgetManager')
edit(A+'ui/SetupChecklistCard.kt',
'''        if (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true) add(SetupItem.BATTERY)''',
'''        if (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true &&
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted != true) add(SetupItem.BATTERY)''')
edit(A+'ui/SetupChecklistCard.kt',
'''                val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))''',
'''                val intent = Intent(if (context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true)
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS else AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"))''')

# Update selectors to the user-authorised six-mode UI, not back to ambiguous Warm/Boost labels.
edit(T+'ui/TemporaryActionDispatchTest.kt','ui.control(ui.s(R.string.feature_warm))',
     'ui.checkControl(ui.compose.onNodeWithTag("quick-mode-DRY"), "Dry quick")',count=4)
edit(T+'ui/TemporaryActionDispatchTest.kt',
'''    @Test fun boostPreviewShowsItsActualTurboThenHeatAndOffRetainsItsExistingGuard() {
        ui.control(boostLabel(graph.context, graph.repository.settings.value.boostMinutes)).performClick()
        dialogText("Turbo · fan 100% · 10 min").assertExists()
        dialogText("Heat · 40°C · fan 100% · 5 min").assertExists()''',
'''    @Test fun turboPreviewIsBoundedWhileLegacyBoostSplittingAndOffGuardRemainIntact() {
        // Boost is still a legacy widget/shortcut action, no longer Tonight's Turbo button.
        val legacy = ui.model.quickAction(Command.BOOST).temporary.request
        val preview = graph.actions.previewTemporary(legacy)
        val details = temporaryDetails(graph.context, legacy, preview, false)
        assertTrue(details.contains("Turbo · fan 100% · 10 min"))
        assertTrue(details.contains("Heat · 40°C · fan 100% · 5 min"))
        ui.checkControl(ui.compose.onNodeWithTag("quick-mode-TURBO"), "Turbo quick").performClick()
        dialogText("Turbo · fan 100% · 10 min").assertExists()
        dialogText("Heat · 40°C · fan 100% · 5 min").assertDoesNotExist()''')

edit(T+'ui/UatLayoutAndSetupTest.kt','    @Test fun U5_everyModeCapturesTheReviewedPresetAndPreservesTemporaryOrEndConsent()',
'''    @Test fun U3_busyOverlayDoesNotMoveAnyQuickTargetAndLeavesNoIdleSlot() {
        val model = model(true)
        compose.setContent { NocturneTheme(model.settings.value, model.now()) { TonightScreen(model, Modifier, onEdit = {}) } }
        compose.onNodeWithTag("quick-controls").performScrollTo()
        val tags = (QuickDefaults.modes + Mode.STANDBY).map { "quick-mode-${it.name}" }
        val before = tags.associateWith { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        compose.onNodeWithTag("busy-overlay").assertDoesNotExist()
        val busy = ReflectionHelpers.getField<MutableStateFlow<Boolean>>(model, "_busy")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { busy.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("busy-overlay").assertIsDisplayed()
        tags.forEach { assertEquals(it, before[it], compose.onNodeWithTag(it).getUnclippedBoundsInRoot()) }
        compose.runOnIdle { busy.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("busy-overlay").assertDoesNotExist()
    }

    @Test fun U5_everyModeCapturesTheReviewedPresetAndPreservesTemporaryOrEndConsent()''')
print('Integrated U1–U5; behavioural tests and command-safety regressions are still mandatory.')
