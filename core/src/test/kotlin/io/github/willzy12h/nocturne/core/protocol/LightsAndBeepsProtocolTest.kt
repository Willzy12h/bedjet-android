package io.github.willzy12h.nocturne.core.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Literal BedJet 3 bytes independently checked against Lull's public Codec.swift at f98821a6f53cbe80c00fc1ba11396a9433b322f0.
 * ESPHome's bedjet_codec.h independently documents byte 27: 0x10 means lights enabled, 0x01 means beeps muted.
 * Sources and verification results are recorded in docs/review/fixes2-ui-protocol-evidence.md.
 */
class LightsAndBeepsProtocolTest {
    @Test fun lightsOnSendsTheLedEnableButton() {
        assertArrayEquals(byteArrayOf(0x01, 0x46), Codec.setLed(true))
    }

    @Test fun lightsOffSendsTheLedDisableButton() {
        assertArrayEquals(byteArrayOf(0x01, 0x47), Codec.setLed(false))
    }

    @Test fun mutingBeepsSendsTheMuteButton() {
        assertArrayEquals(byteArrayOf(0x01, 0x48), Codec.setMuted(true))
    }

    @Test fun enablingBeepsSendsTheUnmuteButton() {
        assertArrayEquals(byteArrayOf(0x01, 0x49), Codec.setMuted(false))
    }

    @ParameterizedTest(name = "status byte 27 = {0}: lights={1}, muted={2}")
    @CsvSource(
        "00, false, false",
        "10, true, false",
        "01, false, true",
        "11, true, true",
        "ee, false, false",
        "fe, true, false",
        "ef, false, true",
        "ff, true, true",
    )
    fun statusFlagsRetainTheirPolarityIndependentlyOfEachOtherAndUnrelatedBits(flags: String, lights: Boolean, muted: Boolean) {
        val packet = "01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 $flags 00 01 53"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val extra = requireNotNull(Codec.parseStatus(packet)?.extra)

        assertEquals(lights, extra.ledEnabled, "Lights enabled for literal status byte 27 = $flags")
        assertEquals(muted, extra.beepsMuted, "Beeps muted for literal status byte 27 = $flags")
    }
}
