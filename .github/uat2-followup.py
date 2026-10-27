from pathlib import Path
A='app/src/main/kotlin/io/github/willzy12h/nocturne/'
T='app/src/test/kotlin/io/github/willzy12h/nocturne/'
def edit(path, old, new, count=1):
    p=Path(path);s=p.read_text();assert s.count(old)==count,(path,s.count(old),old[:80]);p.write_text(s.replace(old,new))

# A standalone, initially empty fixture needs a stable host viewport just like real Tonight.
edit(T+'ui/UatLayoutAndSetupTest.kt',
     'NocturneTheme(model.settings.value, model.now()) { SetupChecklistCard(model) }',
     'NocturneTheme(model.settings.value, model.now()) { Box(Modifier.fillMaxSize()) { SetupChecklistCard(model) } }')
edit(T+'ui/UatLayoutAndSetupTest.kt',
     'assertFalse("No clipped or ellipsised label: $label", results.single().hasVisualOverflow)',
     'assertFalse("No clipped label: $label; size=${results.single().size}; lines=${results.single().lineCount}; width=${results.single().didOverflowWidth}; height=${results.single().didOverflowHeight}", results.single().hasVisualOverflow)')
edit(A+'ui/QuickDefaultsSection.kt',
     'Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)',
     'Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)')

# A reported programme phase is not a manual Quick action; expiry is not active either.
edit(A+'ui/QuickDefaultsSection.kt',
     'it.mode == preset.mode && it.fanPercent == preset.fanPercent &&',
     'it.extra?.programStep == 0 && it.remainingSeconds in 1..(preset.minutes * 60 + 120) &&\n        it.mode == preset.mode && it.fanPercent == preset.fanPercent &&')
edit(A+'ui/QuickDefaultsSection.kt',
     '    val runtime by model.runtime.collectAsState()',
     '    val runtime by model.runtime.collectAsState()\n    val night by model.activeNight.collectAsState()')
edit(A+'ui/QuickDefaultsSection.kt',
     'runtime.slotWrite == null && owner.runningLabel == null }',
     'runtime.slotWrite == null && owner.runningLabel == null &&\n        (it?.mode == Mode.STANDBY || night == null || (night?.trackingManual == true &&\n            night?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED)) }')

# A static indeterminate marker must never announce an invented completion percentage.
edit(A+'ui/BusyOverlay.kt','import androidx.compose.foundation.layout.*',
     'import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.progressSemantics\nimport androidx.compose.ui.Alignment')
edit(A+'ui/BusyOverlay.kt',
     'else LinearProgressIndicator(progress = { .5f }, modifier = Modifier.fillMaxWidth())',
     '''else Box(Modifier.fillMaxWidth().height(4.dp).progressSemantics()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .24f))) {
                Box(Modifier.fillMaxWidth(.35f).height(4.dp).align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.primary))
            }''')

# Keep every old pulse/lifecycle and legacy Boost identity assertion, with the authorised new controls.
path=T+'ui/ActiveIndicatorTest.kt';p=Path(path);s=p.read_text()
s=s.replace('import io.github.willzy12h.nocturne.core.protocol.Mode',
'''import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.night.NightActions.Command''')
s=s.replace('ui.compose.onNode(hasText(ui.s(R.string.feature_warm)) and hasClickAction())',
            'ui.compose.onNodeWithTag("quick-mode-HEAT")')
s=s.replace('''        ui.compose.onNodeWithTag("quick-mode-HEAT").performScrollTo().performClick()
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.EXTENDED_HEAT }''',
'''        ui.compose.onNodeWithTag("quick-mode-EXTENDED_HEAT").performScrollTo().performClick()
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.EXTENDED_HEAT }''')
s=s.replace('''    private fun width() =''', '''    private fun legacyAction() = reportedQuickAction(ui.graph.link.status.value, ui.graph.link.state.value,
        ui.graph.repository.settings.value, ui.graph.repository.activeNight.value,
        ui.graph.repository.runtime.value.slotWrite, ui.model.tonightsProfile()?.limits ?: ProfileLimits())
    private fun width() =''')
s=s.replace('''        ui.waitUntil { ui.graph.link.status.value?.let { it.mode == Mode.TURBO && it.extra?.programStep == 0 } == true }
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertDoesNotExist()''',
'''        ui.waitUntil { ui.graph.link.status.value?.let { it.mode == Mode.TURBO && it.extra?.programStep == 0 } == true }
        assertNull("Manual Turbo must not invent the legacy uploaded Boost identity", legacyAction())
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertExists()''')
s=s.replace('''        ui.compose.onNode(hasText(expectedBoostActionLabel(ui.graph.repository.settings.value.boostMinutes)) and hasClickAction())
            .performScrollTo().performClick()''',
'''        ui.compose.runOnUiThread { ui.model.run(Command.BOOST) }''')
s=s.replace('''            ui.graph.link.status.value?.let { it.mode == Mode.TURBO && (it.extra?.programStep ?: 0) > 0 } == true }
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertExists()''',
'''            ui.graph.link.status.value?.let { it.mode == Mode.TURBO && (it.extra?.programStep ?: 0) > 0 } == true }
        assertEquals(Command.BOOST, legacyAction())
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertDoesNotExist()''')
s=s.replace('''            ui.graph.repository.updateSettings { it.copy(boostMinutes = if (it.boostMinutes == 15) 30 else 15) }
        } }
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertDoesNotExist()''',
'''            ui.graph.repository.updateSettings { it.copy(boostMinutes = if (it.boostMinutes == 15) 30 else 15) }
        } }
        assertNull("Changed defaults must not identify the running legacy programme", legacyAction())
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertDoesNotExist()''')
s=s.replace('''        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.HEAT }
        ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertDoesNotExist()''',
'''        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.HEAT }
        assertNull("Identical legacy presets cannot identify a Warm or Cool request", legacyAction())
        // The new mode grid has exactly one Heat button: it may show the actual reported manual mode.
        ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertExists()''')
assert s!=p.read_text();p.write_text(s)

# The grid is taller by design; scroll it without weakening order/collapse persistence checks.
path=T+'ui/TonightLayoutTest.kt';p=Path(path);s=p.read_text()
s=s.replace('ui.compose.onNode(hasText(ui.s(R.string.feature_warm)) and hasClickAction())',
            'ui.compose.onNodeWithTag("quick-mode-HEAT")')
s=s.replace('        quick.performClick()\n', '        quick.performScrollTo().performClick()\n')
s=s.replace('        warm.assertIsDisplayed()', '        warm.performScrollTo().assertIsDisplayed()')
assert s!=p.read_text();p.write_text(s)

# Diagnose the resumed standalone fixture using a stable physical viewport, not an initially empty root.
edit(T+'ui/UatLayoutAndSetupTest.kt',
     'Box(Modifier.fillMaxSize()) { SetupChecklistCard(model) }',
     'Box(Modifier.requiredSize(320.dp, 640.dp)) { SetupChecklistCard(model) }')
edit(T+'ui/UatLayoutAndSetupTest.kt',
     '        compose.onNodeWithTag("setup-notifications").assertIsDisplayed()',
     '''        val notificationNode = compose.onNodeWithTag("setup-notifications")
        println("RESUMED_SETUP bounds=" + notificationNode.fetchSemanticsNode().boundsInRoot +
            "; root=" + compose.onRoot().fetchSemanticsNode().boundsInRoot +
            "; states=" + setupSnapshot(app, model.settings.value, false))
        notificationNode.assertIsDisplayed()''')
print('Applied UAT corrections while retaining receipt, layout, lifecycle and legacy-action assertions.')

# The checklist promises notification permission, not just a manager switch. Android 13+ needs both.
edit(A+'ui/SetupChecklistCard.kt',
     'if (Notifications.canPost(context)) add(SetupItem.NOTIFICATIONS)',
     '''if ((Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && Notifications.canPost(context)) add(SetupItem.NOTIFICATIONS)''')
edit(T+'ui/UatLayoutAndSetupTest.kt',
     '            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)',
     '''            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertFalse("Revoked runtime permission cannot count as complete",
                io.github.willzy12h.nocturne.core.store.SetupItem.NOTIFICATIONS in setupSnapshot(app, model.settings.value, false).complete)''')
