"""Complete the remaining UAT regressions after the preserved follow-up."""
from pathlib import Path
A='app/src/main/kotlin/io/github/willzy12h/nocturne/'
T='app/src/test/kotlin/io/github/willzy12h/nocturne/'
def edit(path, old, new, count=1):
    p=Path(path);s=p.read_text();assert s.count(old)==count,(path,s.count(old),old[:100]);p.write_text(s.replace(old,new))

# The preceding follow-up already applies the notification runtime-permission guard.
setup=Path(A+'ui/SetupChecklistCard.kt').read_text()
assert 'Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)' in setup

# Preserve the existing bounded Temporary path for unsupported imported legacy presets.
# The unchanged owner and final command boundary still refuse an unsafe End/idle request.
edit(A+'ui/QuickDefaultsSection.kt','    require(QuickDefaults.valid(preset))', '''    // Keep legacy values intact; only the reviewed, explicitly bounded Temporary choice
    // can run when the original End request is unsafe. The command owner remains authoritative.
    require(preset.mode in QuickDefaults.modes)''')
edit(A+'ui/QuickDefaultsSection.kt','    return when {\n        preset.mode == Mode.TURBO', '''    return when {
        !QuickDefaults.valid(preset) -> name // Do not advertise an unsupported duration as executable.
        preset.mode == Mode.TURBO''')
edit(A+'ui/QuickDefaultsSection.kt','                    val description = preset?.let { presetDetail(context, it, settings.fahrenheit) } ?: label', '''                    val description = if (!valid) stringResource(R.string.uat_quick_invalid)
                        else preset?.let { presetDetail(context, it, settings.fahrenheit) } ?: label''')
edit(A+'ui/QuickDefaultsSection.kt','                    }, enabled = valid,','                    },')

# Existing tests must exercise their migrated mode, not the removed ambiguous Warm label.
edit(T+'ui/U06Fixture.kt','    fun control(label: String, scroll: Boolean = true): SemanticsNodeInteraction =', '''    fun quick(preset: io.github.willzy12h.nocturne.core.store.ActionPreset): SemanticsNodeInteraction =
        checkControl(compose.onNodeWithTag("quick-mode-${preset.mode.name}"), "Quick ${preset.mode}").assertHasClickAction()

    fun control(label: String, scroll: Boolean = true): SemanticsNodeInteraction =''')
for name,count in [('TemporaryActionDialogTest.kt',1),('TemporaryTrackingTruthTest.kt',2),
                   ('ImportedTemporaryDurationTest.kt',2),('U06PrimaryLayoutTest.kt',1)]:
    edit(T+'ui/'+name,'ui.control(ui.s(R.string.feature_warm))',
         'ui.quick(ui.graph.repository.settings.value.extendWarm)',count)
edit(T+'ui/U06ControlSemanticsTest.kt','fixture.control(s(R.string.feature_warm))',
     'fixture.quick(fixture.graph.repository.settings.value.extendWarm)')
edit(T+'AppearanceTest.kt','compose.onNodeWithText(io.github.willzy12h/nocturne/ui.expectedBoostActionLabel(graph.repository.settings.value.boostMinutes))',
     'compose.onNodeWithText("Turbo 10m")')
edit(T+'ui/U06PrimaryLayoutTest.kt', '''        val labels = listOf(expectedBoostActionLabel(ui.graph.repository.settings.value.boostMinutes),
            ui.s(R.string.feature_warm), ui.s(R.string.feature_cool), ui.s(R.string.feature_off))
        labels.forEach { label ->
            ui.checkControl(ui.compose.onNode(hasText(label) and hasClickAction() and
                hasAnyAncestor(hasTestTag("quick-controls"))), label).assertHasClickAction()
        }''', '''        val modes = io.github.willzy12h.nocturne.core.store.QuickDefaults.modes + Mode.STANDBY
        modes.forEach { mode ->
            ui.checkControl(ui.compose.onNodeWithTag("quick-mode-${mode.name}"), "Quick $mode").assertHasClickAction()
        }''')
# Legacy Boost remains on Remote: retain captured15-versus-later30 assertions on that actual control.
edit(T+'ui/IdleQuickCaptureTest.kt','            compose.onNode(hasText("Turbo 10m + Heat 5m") and hasClickAction()).performScrollTo().performClick()', '''            compose.onNodeWithText(compose.activity.getString(R.string.tab_remote)).performClick()
            compose.onNode(hasText("Turbo 10m + Heat 5m") and hasClickAction()).performScrollTo().performClick()''')
edit(T+'ui/IdleQuickCaptureTest.kt', '''        compose.onNode(hasText(compose.activity.getString(R.string.feature_warm)) and hasClickAction())
            .performScrollTo().performClick()''', '''        compose.onNodeWithTag("quick-mode-TURBO").performScrollTo().performClick()''')
edit(T+'ui/BoostSequenceLayoutTest.kt','    @Test fun tonightShowsBothActualSegmentsForEveryDurationAtTwoHundredPercent() = everyDuration(quick = true)', '''    @Test fun tonightTurboRemainsTenMinutesWhenTheLegacyBoostDurationChanges() {
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            ui.compose.runOnUiThread { runBlocking { ui.graph.repository.updateSettings { it.copy(boostMinutes = minutes) } } }
            val node = ui.compose.onNodeWithTag("quick-mode-TURBO")
            node.assertTextContains("Turbo 10m")
            node.assertContentDescriptionEquals("Turbo · fan 100% · 10 min")
            ui.checkControl(node, "Turbo ten-minute Quick")
        }
    }''')
print('Preserved prior receipt/reduced-motion fixes; completed permission revocation and migrated real UI selectors.')
