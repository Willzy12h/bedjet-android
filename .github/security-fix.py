"""Temporary, exact-match patch application in an isolated CI checkout; no network or credentials."""
from pathlib import Path
import re

ROOT = Path('.')
CORE = 'core/src/main/kotlin/io/github/willzy12h/nocturne/core/'
APP = 'app/src/main/kotlin/io/github/willzy12h/nocturne/'

def change(path, old, new, count=1):
    p = ROOT / path
    text = p.read_text()
    assert text.count(old) == count, (path, 'anchor count', text.count(old), old[:100])
    p.write_text(text.replace(old, new))

def transform(path, function):
    p = ROOT / path
    before = p.read_text()
    after = function(before)
    assert after != before, (path, 'no change')
    p.write_text(after)

change(CORE+'protocol/Model.kt',
       'val fitsUnitLimits: Boolean get() = endsAtClockTime || durationMinutes <= longestMinutes',
       'val fitsUnitLimits: Boolean get() = CommandSafety.stepFields(this) == null')
change(CORE+'protocol/Model.kt',
       'if (mode != Mode.HEAT || temperature < 60) return Limits.MAX_STEP_MINUTES',
       'if (mode == Mode.TURBO) return Limits.TURBO_MINUTES\n            if (mode != Mode.HEAT || temperature < 60) return Limits.MAX_STEP_MINUTES')

controller = CORE+'link/BedJetController.kt'
change(controller, 'import io.github.willzy12h.nocturne.core.protocol.Codec',
       'import io.github.willzy12h.nocturne.core.protocol.CommandSafety\nimport io.github.willzy12h.nocturne.core.protocol.DeviceStatus\nimport io.github.willzy12h.nocturne.core.protocol.Codec')
change(controller, 'suspend fun programSteps(slot: Int): List<ProgramStep> {',
       'suspend fun programSteps(slot: Int): List<ProgramStep> {\n        validateSlot(slot)')
change(controller, 'suspend fun store(program: StoredProgram, slot: Int) {',
       'suspend fun store(program: StoredProgram, slot: Int) {\n        validateSlot(slot)\n        validateProgram(program)')
change(controller, 'beforePrepare: suspend () -> Unit = {}, beforeStart: () -> Unit = {}) = coroutineScope {\n        syncClock()',
       'beforePrepare: suspend () -> Unit = {}, beforeStart: () -> Unit = {}) = coroutineScope {\n        validateSlot(slot)\n        val programme = StoredProgram("", programSteps(slot))\n        validateProgram(programme)\n        syncClock()')
change(controller, 'link.sendGuarded(Codec.startProgram(slot)) {\n                beforeStart()',
       'link.sendGuarded(Codec.startProgram(slot)) {\n                validateProgram(programme)\n                beforeStart()')
change(controller, '''    suspend fun setTemperature(halfDegrees: Int) = link.send(Codec.setTemperature(halfDegrees))
    suspend fun setFan(step: Int) = link.send(Codec.setFan(step))
    suspend fun setRuntime(minutes: Int) = link.send(Codec.setRuntime(minutes))''', '''    suspend fun setTemperature(halfDegrees: Int) {
        val status = currentStatus()
        requireSafe(CommandSafety.controls(status.mode, halfDegrees, status.fanStep))
        if (!status.mode.usesFan || status.minTemperature > status.maxTemperature ||
            halfDegrees !in status.minTemperature..status.maxTemperature)
            throw BedJetError.InvalidProgram("The requested temperature is outside the current control range.")
        if (status.remainingSeconds > ProgramStep.longestMinutes(status.mode, halfDegrees, status.fanStep) * 60)
            throw BedJetError.ProgramTooLong()
        link.send(Codec.setTemperature(halfDegrees))
    }
    suspend fun setFan(step: Int) {
        val status = currentStatus()
        requireSafe(CommandSafety.controls(status.mode, status.targetTemperature, step))
        if (!status.mode.usesFan) throw BedJetError.InvalidProgram("Choose a running mode first.")
        if (status.remainingSeconds > ProgramStep.longestMinutes(status.mode, status.targetTemperature, step) * 60)
            throw BedJetError.ProgramTooLong()
        link.send(Codec.setFan(step))
    }
    suspend fun setRuntime(minutes: Int) {
        if (minutes !in 1..CommandSafety.manualMaximum(currentStatus())) throw BedJetError.ProgramTooLong()
        link.send(Codec.setRuntime(minutes))
    }''')
change(controller, '''        setMode(mode)
        if (mode.usesTemperature) setTemperature(temperature.coerceIn(mode.temperatureRange))
        if (mode.usesFan) setFan(fanStep)
        // The unit resets the timer when the mode changes, so the timer goes last.
        setRuntime(minutes)''', '''        // Validate the complete request before changing mode: no partially applied unsafe run.
        requireSafe(CommandSafety.manual(mode, temperature, fanStep, minutes))
        setMode(mode)
        if (mode.usesTemperature) link.send(Codec.setTemperature(temperature))
        if (mode.usesFan) link.send(Codec.setFan(fanStep))
        // Do not validate against the previous mode's cached status during this owned batch.
        link.send(Codec.setRuntime(minutes))''')
change(controller, '    private companion object {', '''    fun validateProgram(program: StoredProgram) = requireSafe(CommandSafety.programme(program, now().toLocalTime()))

    private fun currentStatus(): DeviceStatus {
        if (link.state.value != LinkState.READY) throw BedJetError.NotConnected()
        return link.latestSourcedStatus()?.status ?: throw BedJetError.NotConnected()
    }
    private fun validateSlot(slot: Int) {
        if (slot !in 0 until Limits.SLOTS) throw BedJetError.InvalidProgram("Choose a valid BedJet slot.")
    }
    private fun requireSafe(problem: CommandSafety.Problem?) {
        if (problem == CommandSafety.Problem.DURATION) throw BedJetError.ProgramTooLong()
        if (problem != null) throw BedJetError.InvalidProgram("The request exceeds the BedJet safety limits.")
    }

    private companion object {''')

codec = CORE+'protocol/Codec.kt'
change(codec, 'if (b.size < 20) return null\n        val mode = Mode.fromCode(b[9]) ?: return null', '''if (!isStatusStart(packet)) return null
        val mode = Mode.fromCode(b[9]) ?: return null
        // Recognisable framing is not semantic validity. Never publish these as fresh receipts.
        if (b[5] > 59 || b[6] > 59 || b[12] > 59 || b[10] > 19 ||
            b[13] > b[14] || b[14] > 86 || (mode.usesFan && b[8] > 86) ||
            (b.size >= 30 && b[28] > Limits.STEPS_PER_PROGRAM)) return null''')
gatt = APP+'ble/GattLink.kt'
change(gatt, '        val parsed = Codec.parseStatus(chunk) ?: return', '''        val parsed = Codec.parseStatus(chunk) ?: run {
            synchronized(startStatusLock) {
                if (current === session) { session.lastHead = null; session.lastExtra = null }
            }
            return
        }''')
change(gatt, '''        if (parsed.extra != null) {
            synchronized(startStatusLock) {
                if (current !== session || !selected(session.address)) return''', '''        if (parsed.extra != null) {
            synchronized(startStatusLock) {
                if (current !== session || !selected(session.address)) return
                session.lastHead = null''')
slider = APP+'ui/ScaledSlider.kt'
change(slider, 'import androidx.compose.ui.Modifier', 'import androidx.compose.ui.res.stringResource\nimport io.github.willzy12h.nocturne.R\nimport androidx.compose.ui.Modifier')
change(slider, '    val style = MaterialTheme.typography.labelMedium\n', '''    val style = MaterialTheme.typography.labelMedium
    if (!value.isFinite() || !range.start.isFinite() || !range.endInclusive.isFinite() ||
        range.start > range.endInclusive || ticks.isEmpty() || ticks.any { !it.first.isFinite() } || steps < 0) {
        Text(stringResource(R.string.remote_control_unavailable), style = style,
            modifier = modifier.fillMaxWidth().testTag("$tag-unavailable"))
        return
    }
''')

actions = APP+'night/NightActions.kt'
change(actions, '''            return@owned refused(R.string.runtime_restore_imported_out_of_range)
        connected {''', '''            return@owned refused(R.string.runtime_restore_imported_out_of_range)
        if (original.program?.let { CommandSafety.programme(it, graph.now().toLocalTime()) } != null)
            return@owned refused(R.string.runtime_restore_unsafe)
        connected {''')
change(actions, '''        expectedOriginal: SlotBackup? = null): CommandOutcome {
        val target''', '''        expectedOriginal: SlotBackup? = null): CommandOutcome {
        if (program != null && CommandSafety.programme(program, graph.now().toLocalTime()) != null)
            return refused(R.string.runtime_restore_unsafe)
        val target''')
# The existing owner captures target/night and serialises all callers; add only policy preflight.
for signature in ('private suspend fun quick(preset: ActionPreset, message: String): CommandOutcome {',
                  'private suspend fun timedManual(preset: ActionPreset, kind: EventKind, label: String): CommandOutcome {'):
    change(actions, signature, signature+'\n        if (!safePreset(preset)) return refused(R.string.runtime_command_out_of_range)')
change(actions, '    private suspend fun runPreset(preset: ActionPreset) {', '''    private fun safePreset(preset: ActionPreset): Boolean {
        val bounds = limits()
        return CommandSafety.manual(preset.mode, PlanCompiler.temperatureFor(preset.mode, preset.temperature, bounds),
            PlanCompiler.fanStepFor(preset.fanPercent, bounds), preset.minutes) == null
    }
    private suspend fun runPreset(preset: ActionPreset) {''')
# Add an explicit import regardless of existing wildcard imports.
change(actions, 'package io.github.willzy12h.nocturne.night\n',
       'package io.github.willzy12h.nocturne.night\n\nimport io.github.willzy12h.nocturne.core.protocol.CommandSafety\n')
p=ROOT/(APP+'night/RemoteDuration.kt')
p.write_text('''package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.protocol.CommandSafety
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus

/** A reported maximum may narrow, never widen, the command policy. */
internal fun manualRuntimeMaximum(status: DeviceStatus): Int = CommandSafety.manualMaximum(status)
''')

# LIB-01: remove the incoming legacy Binder endpoint and gate every outbound construction/permission path.
manifest='app/src/main/AndroidManifest.xml'
change(manifest, '    <queries><package android:name="com.google.android.apps.healthdata" /></queries>\n', '')
change(manifest, '        <activity android:name=".ui.MainActivity"', '''        <!-- Legacy provider signer checks are incomplete; Android 14+ uses the platform service. -->
        <service android:name="androidx.health.platform.client.impl.sdkservice.HealthDataSdkService" tools:node="remove" />

        <activity android:name=".ui.MainActivity"''')
for filename in ('AndroidSleepSource.kt','HealthSleepExporter.kt'):
    path=APP+'platform/'+filename
    transform(path, lambda s: s.replace('HealthConnectClient.getOrCreate(context)', 'HealthConnectAccess.client(context)')
        .replace('HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE','HealthConnectAccess.available(context)')
        .replace('HealthConnectClient.getSdkStatus(graph.context) == HealthConnectClient.SDK_AVAILABLE','HealthConnectAccess.available(graph.context)'))
for filename in ('FeatureSections.kt','PlatformAutomationSettings.kt'):
    path=APP+'ui/features/'+filename
    change(path, 'import androidx.health.connect.client.HealthConnectClient',
           'import io.github.willzy12h.nocturne.platform.HealthConnectAccess')
    transform(path, lambda s: re.sub(r'HealthConnectClient\.getSdkStatus\(graph.context\)\s*!=\s*HealthConnectClient.SDK_AVAILABLE',
                                    '!HealthConnectAccess.available(graph.context)',s))
    change(path, '    val scope=rememberCoroutineScope()' if filename=='FeatureSections.kt' else '    val result by graph.platform.healthExport.state.collectAsState()',
           ('    val scope=rememberCoroutineScope()\n' if filename=='FeatureSections.kt' else '    val result by graph.platform.healthExport.state.collectAsState()\n')+
           '    if (!HealthConnectAccess.platformSupported) Text(stringResource(R.string.health_platform_required))')
    transform(path,lambda s:s.replace('graph.context.getString(R.string.feature_health_absent)', 'HealthConnectAccess.unavailableMessage(graph.context)'))
    if filename=='FeatureSections.kt':
        change(path,'if (!graph.repository.settings.value.demoMode) {','if (!graph.repository.settings.value.demoMode && HealthConnectAccess.platformSupported) {',count=2)
    else:
        change(path,'if (current.demoMode || expected != graph.repository.settingsPublication.value) current',
               'if (!HealthConnectAccess.platformSupported || current.demoMode || expected != graph.repository.settingsPublication.value) current')

# The original library tests proved the vulnerability. Under containment the class is deliberately
# unregistered; directly constructing it would test an unused dependency, not the app's attack surface.
(ROOT/'app/src/test/kotlin/io/github/willzy12h/nocturne/platform/HealthProviderSignatureSecurityTest.kt').unlink()
# Keep the exact release inventory strict, with the legacy endpoint forbidden rather than allowlisted.
transform('.github/workflows/release_manifest.py',lambda s:'\n'.join(line for line in s.split('\n') if '("service", "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService"' not in line))
fixture=ROOT/'.github/workflows/fixtures/release-manifest-reviewed.xml'
s=fixture.read_text()
s,n=re.subn(r'\s*<service\b[^>]*android:name="androidx.health.platform.client.impl.sdkservice.HealthDataSdkService"[^>]*(?:/>|>.*?</service>)', '', s, flags=re.S)
assert n==1, ('release fixture legacy service',n)
fixture.write_text(s)
transform('.github/workflows/test_release_manifest.py',lambda s:s.replace('self.assertEqual(41, len(report["components"]))','self.assertEqual(40, len(report["components"]))').replace('"41 components"','"40 components"'))
print('Security patch applied; tests must pass before publication.')
