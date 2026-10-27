"""Exact integration edits, verified in a disposable checkout before the branch receives them."""
from pathlib import Path
C='core/src/main/kotlin/io/github/willzy12h/nocturne/core/'
A='app/src/main/kotlin/io/github/willzy12h/nocturne/'
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
# Render work as a sibling overlay; its size never participates in scroll/Quick measurement.
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
# Remove the superseded four-button body, retaining the shared public connection helpers.
start=s.index('/** Active markers follow a reported mode;')
end=s.index('@Composable\nprivate fun Note',start)
s=s[:start]+s[end:]
p.write_text(s)

edit(A+'ui/SettingsScreen.kt','        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)',
'''        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = model::openSetupChecklist, modifier = Modifier.testTag("open-setup-checklist")) {
            Text(stringResource(R.string.uat_setup_title))
        }
        Group(stringResource(R.string.uat_quick_defaults)) { QuickDefaultsSection(model, settings) }''')
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
print('Integrated U1–U5; behavioural tests and command-safety regressions are still mandatory.')
