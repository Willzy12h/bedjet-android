package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

/** R5: malformed files must be rejected before they can replace durable data. */
class BackupStrictValidationTest {
    private val at = "2026-10-24T22:30Z"
    private val profile = Presets.summerCool
    private val night = NightRecord("night", profile.id, profile.name, at, at, false)
    private val good = BackupBundle(createdAt = at, profiles = listOf(profile), settings = Settings())
    private fun rejected(bundle: BackupBundle) = assertTrue(BackupValidator.validate(bundle).isNotEmpty())
    private fun encoded(schema: String = "2", extra: String = "") =
        """{"schema":$schema,"createdAt":"$at","profiles":[],"settings":{}$extra}"""

    @Test fun rejectsZeroAndNegativeSchemasAsBackupErrors() {
        listOf("0", "-1").forEach { schema -> assertThrows<BackupException> { BackupCodec.decode(encoded(schema)) } }
    }

    @Test fun rejectsQuotedAndFractionalSchemaNumbers() {
        listOf("\"2\"", "2.0", "null", "true").forEach { schema ->
            assertThrows<BackupException> { BackupCodec.decode(encoded(schema)) }
        }
    }

    @Test fun rejectsQuotedNumbersAndBooleansInOrdinaryFieldsToo() {
        assertThrows<BackupException> { BackupCodec.decode(encoded().replace("\"settings\":{}", "\"settings\":{\"workingSlot\":\"2\"}")) }
        assertThrows<BackupException> { BackupCodec.decode(encoded().replace("\"settings\":{}", "\"settings\":{\"fahrenheit\":\"true\"}")) }
        assertThrows<BackupException> { BackupCodec.decode(encoded().replace("\"createdAt\":\"$at\"", "\"createdAt\":123")) }
    }

    @Test fun rejectsUnknownFieldsAtEveryDepthInsteadOfSilentlyIgnoringThem() {
        assertThrows<BackupException> { BackupCodec.decode(encoded(extra = ",\"unexpected\":true")) }
        assertThrows<BackupException> { BackupCodec.decode(encoded().replace("\"settings\":{}", "\"settings\":{\"fan\":200}")) }
    }

    @Test fun rejectsDuplicateKeysIncludingEscapedEquivalentKeys() {
        assertThrows<BackupException> { BackupCodec.decode(encoded(extra = ",\"schema\":2")) }
        assertThrows<BackupException> { BackupCodec.decode(encoded(extra = ",\"sche\\u006da\":2")) }
    }

    @Test fun rejectsInvalidUtf8RatherThanReplacingBytesWithACharacter() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        assertThrows<BackupException> { BackupValidator.readWithinLimit(ByteArrayInputStream(bytes)) }
    }

    @Test fun directDecodeEnforcesTheFileSizeLimitToo() {
        val text = encoded(extra = ",\"oversized\":\"${"x".repeat(BackupValidator.MAX_BYTES)}\"")
        assertThrows<BackupException> { BackupCodec.decode(text) }
    }

    @Test fun rejectsExcessiveNestingBeforeRecursiveDecoding() {
        assertThrows<BackupException> { BackupCodec.decode(encoded(extra = ",\"deep\":" + "[".repeat(80) + "0" + "]".repeat(80))) }
    }

    @Test fun rejectsBlankOrOversizedIdentityAndDisplayFields() {
        rejected(good.copy(profiles = listOf(profile.copy(id = ""))))
        rejected(good.copy(profiles = listOf(profile.copy(id = "x".repeat(129)))))
        rejected(good.copy(profiles = listOf(profile.copy(name = "x".repeat(121)))))
        rejected(good.copy(history = listOf(night.copy(events = listOf(HistoryEvent(at, EventKind.ERROR, "x".repeat(2049)))))))
        rejected(good.copy(history = listOf(night.copy(deviceId = "ble:" + "A".repeat(129)))))
    }

    @Test fun rejectsOversizedCollectionsEvenWithinTheFileByteLimit() {
        rejected(good.copy(profiles = List(129) { profile.copy(id = "p$it") }))
        rejected(good.copy(profiles = listOf(profile.copy(start = List(65) { profile.start.first() }))))
        rejected(good.copy(history = List(2049) { night.copy(id = "n$it") }))
        rejected(good.copy(slotBackups = List(385) { SlotBackup("ble:$it", 0, null, at) }))
        rejected(good.copy(history = listOf(night.copy(temperatures = List(4097) { TempSample(at, 50, 50) }))))
    }

    @Test fun rejectsDuplicateHistoryAndCaseEquivalentOriginalIdentities() {
        rejected(good.copy(history = listOf(night, night.copy(profileName = "Other"))))
        rejected(good.copy(slotBackups = listOf(SlotBackup("ble:AA:BB", 0, null, at), SlotBackup("ble:aa:bb", 0, null, at))))
    }

    @Test fun rejectsMalformedRecordedValuesAndRevisionTimes() {
        rejected(good.copy(history = listOf(night.copy(steps = listOf(RecordedStep(Block.START, Mode.COOL, 44, 101, at, 10))))))
        rejected(good.copy(history = listOf(night.copy(temperatures = listOf(TempSample(at, -1, 256))))))
        rejected(good.copy(history = listOf(night.copy(revisions = listOf(AppliedProgram(start = "bad", wake = at, revision = -1))))))
    }

    @Test fun rejectsMalformedBedJetAssociationsInsteadOfAcceptingAnyBlePrefix() {
        rejected(good.copy(slotBackups = listOf(SlotBackup("ble:not-an-address", 0, null, at))))
        rejected(good.copy(history = listOf(night.copy(deviceId = "ble:00:11:22:33:44:ZZ"))))
        rejected(good.copy(settings = Settings(targetDevice = "ble:../other")))
    }

    @Test fun rejectsOversizedSettingsFieldsAndCollections() {
        rejected(good.copy(settings = Settings(alarmPackages = setOf("x".repeat(256)))))
        rejected(good.copy(settings = Settings(alarmPackages = (0..128).map { "org.clock$it" }.toSet())))
        rejected(good.copy(settings = Settings(tonightSectionOrder = List(65) { "night" })))
        rejected(good.copy(settings = Settings(selectedProfileId = "x".repeat(129))))
    }

    @Test fun snapshotsRefusePathTraversalAndCannotDeleteOutsideTheirDirectory(@TempDir dir: Path) {
        val snapshots = Files.createDirectory(dir.resolve("snapshots"))
        val outside = dir.resolve("outside.json")
        Files.writeString(outside, """{"createdAt":"$at","manual":true,"bundle":${BackupCodec.encode(good)}}""")
        val store = SnapshotStore(snapshots) { ZonedDateTime.parse(at) }
        assertNull(store.load("../outside"))
        runCatching { store.delete("../outside") }
        assertTrue(Files.exists(outside), "An invalid snapshot id must never delete a neighbouring file")
    }

    @Test fun snapshotsRejectOversizedOrDuplicateKeyPayloads(@TempDir dir: Path) {
        val file = dir.resolve("snap-1000-m.json")
        val store = SnapshotStore(dir) { ZonedDateTime.parse(at) }
        Files.writeString(file, """{"createdAt":"$at","manual":true,"bundle":${encoded(extra = ",\"schema\":2")}}""")
        assertNull(store.load("snap-1000-m"))
        Files.writeString(file, """{"createdAt":"$at","manual":true,"bundle":${encoded(extra = ",\"padding\":\"${"x".repeat(BackupValidator.MAX_BYTES)}\"")}}""")
        assertNull(store.load("snap-1000-m"))
    }

    @Test fun snapshotsIgnoreOversizedHeadersDuringListing(@TempDir dir: Path) {
        Files.writeString(dir.resolve("snap-1000-m.json"), """{"createdAt":"$at","label":"${"x".repeat(4097)}","manual":true,"bundle":{}}""")
        assertTrue(SnapshotStore(dir) { ZonedDateTime.parse(at) }.list().isEmpty())
    }

    @Test fun knownRetiredSettingsStillImportFromOlderBackups() {
        val text = encoded().replace("\"settings\":{}", "\"settings\":{\"updateRepo\":\"old/project\",\"autoUpdateCheck\":true,\"platform\":{\"lullHandover\":true}}")
        assertEquals(emptyList<io.github.willzy12h.nocturne.core.plan.Profile>(), BackupCodec.decode(text).profiles)
    }

}
