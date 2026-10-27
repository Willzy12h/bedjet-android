"""Exact security regression corrections, applied only to a disposable verification checkout."""
from pathlib import Path
C = 'core/src/main/kotlin/io/github/willzy12h/nocturne/core/'
A = 'app/src/test/kotlin/io/github/willzy12h/nocturne/'
def edit(path, old, new, count=1):
    p=Path(path); s=p.read_text()
    assert s.count(old)==count,(path,s.count(old),old[:80])
    p.write_text(s.replace(old,new))

# Standby fan checks cannot heat or start a mode. They still need wire-range validation.
edit(C+'link/BedJetController.kt', '''        if (!status.mode.usesFan) throw BedJetError.InvalidProgram("Choose a running mode first.")
        if (status.remainingSeconds > ProgramStep.longestMinutes(status.mode, status.targetTemperature, step) * 60)''', '''        // Standby fan preferences support the non-heating setup check; no mode is started here.
        if (status.mode.usesFan && status.remainingSeconds >
            ProgramStep.longestMinutes(status.mode, status.targetTemperature, step) * 60)''')

# Start tests deliberately hold clock/start writes, not the new read-only programme preflight.
edit(A+'ble/FakeRadio.kt','    var behaviour = Behaviour()','''    var behaviour = Behaviour()
    /** Opt-in safe slot reply for controller start-boundary tests. */
    var readableProgramme = false''')
edit(A+'ble/FakeRadio.kt','            if (!holdWrites) post { sink.onWrite(attr, GATT_SUCCESS) }',
'''            val programmeQuery = readableProgramme && data.size >= 3 && data[0].toInt() == 0x41 &&
                (data[1].toInt() and 0xff) in 7..18
            if (!holdWrites || programmeQuery) post { sink.onWrite(attr, GATT_SUCCESS) }''')
edit(A+'ble/FakeRadio.kt','''                    post { sink.onRead(attr, echo + "BEDJET 3".toByteArray() + 0, GATT_SUCCESS) }''',
'''                    val request = echo[0].toInt() and 0xff
                    val payload = if (readableProgramme && request in 7..18) {
                        // A ten-minute Cool step: the start tests test receipt ordering, not unsafe slots.
                        byteArrayOf(1, 0, 5, 50, 0, 10)
                    } else "BEDJET 3".toByteArray() + 0
                    post { sink.onRead(attr, echo + payload, GATT_SUCCESS) }''')
p=Path(A+'ble/GattLinkTest.kt');s=p.read_text()
a='        val controller = BedJetController(link) { ZonedDateTime.now() }'
assert s.count(a)>=9,s.count(a)
p.write_text(s.replace(a,'        fake.readableProgramme = true\n'+a))

# These tests exercise cancellation settlement, not an unsafe temperature in the running Cool phase.
edit(A+'night/ManualCancellationSettlementTest.kt','confirms = { it.targetTemperature == 72 }',
     'confirms = { it.targetTemperature == 50 }')
edit(A+'night/ManualCancellationSettlementTest.kt','            it.setTemperature(72)',
     '            it.setTemperature(50) // Valid in both Cool and Heat; cancellation assertions stay unchanged.')
edit(A+'night/SendConfirmationTest.kt', '''    @Test fun anotherUnitsAlreadyMatchingValueCannotConfirmTheWrite() = runBlocking {
        demo.connect()
        foreign''', '''    @Test fun anotherUnitsAlreadyMatchingValueCannotConfirmTheWrite() = runBlocking {
        demo.connect()
        demo.send(Codec.setMode(Mode.HEAT)!!)
        foreign''')

# Preserve the invalid-live-setpoint scenario using a physical/other-controller change.
# APP-01 correctly prevents this same setpoint being created by Nocturne itself.
edit(A+'ui/RemoteDurationPreservationTest.kt', '''        // This is the existing live Remote temperature route: it sends the value chosen within reported limits.
        val temperature = graph.actions.remote(app.getString(R.string.remote_temperature_set), affectsProgramme = true,
            confirms = { it.targetTemperature == 82 }) { it.setTemperature(82) }
        assertEquals(OutcomeKind.CONFIRMED, temperature.kind)''', '''        // Simulate a physical remote changing the unit beyond Nocturne's safe replay range.
        // Nocturne's APP-01 boundary must not be bypassed to arrange this fixture.
        val external = reports.value!!.let { it.copy(status = it.status.copy(targetTemperature = 82)) }
        reports.value = external
        statuses.value = external.status
        if (!observedOnly) graph.repository.setActiveNight(graph.repository.activeNight.value!!.copy(trackingManual = true))''')
print('Applied bounded source/fixture corrections. Behavioural GREEN remains mandatory.')
