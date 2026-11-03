package io.github.willzy12h.nocturne.core.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StatusValidationSecurityTest {
    private fun packet() = ByteArray(30).also {
        it[1] = 0x56; it[3] = 1; it[5] = 10; it[7] = 50; it[8] = 60
        it[9] = Mode.HEAT.code.toByte(); it[10] = 5; it[11] = 1
        it[13] = 45; it[14] = 80; it[17] = 45
    }
    @Test fun reversedControlRangesAreNotStatusEvidence() {
        val bytes = packet().also { it[13] = 100; it[14] = 40 }
        assertTrue(Codec.isStatusStart(bytes))
        assertNull(Codec.parseStatus(bytes), "Recognisable does not mean semantically valid")
        assertNull(Codec.parseStatus(bytes.copyOf(20)))
    }
    @Test fun nonNormalisedTimersAndOutOfRangeFanAreRejected() {
        for ((offset, value) in listOf(5 to 60, 6 to 60, 12 to 60, 10 to 20, 28 to 33)) {
            assertNull(Codec.parseStatus(packet().also { it[offset] = value.toByte() }), "Byte $offset")
        }
    }
    @Test fun equalAndNormalRangesAndSplitHeadsRemainValid() {
        assertNotNull(Codec.parseStatus(packet()))
        assertNull(Codec.parseStatus(packet().copyOf(20))!!.extra)
        val equal = packet().also { it[13] = 60; it[14] = 60 }
        assertEquals(60, Codec.parseStatus(equal)!!.minTemperature)
        assertEquals(60, Codec.parseStatus(equal)!!.maxTemperature)
    }
}
