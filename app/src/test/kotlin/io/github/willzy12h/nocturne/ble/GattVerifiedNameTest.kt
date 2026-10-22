package io.github.willzy12h.nocturne.ble

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.Codec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GattVerifiedNameTest {
    private val address="AA:BB:CC:DD:EE:FF"
    private val timings=GattLink.Timings(operationMs=300,connectMs=500,mtuFallbackMs=100,firstStatusMs=100,retryBackoffMs=listOf(1),remainderEveryMs=10_000)
    @Test fun verifiedNameComesFromExactCharacteristicNotQueryDisplayName() = runBlocking {
        val fake=FakeRadio();fake.behaviour=FakeRadio.Behaviour(nameBytes=" BedJet Ω \u0000ignored".toByteArray())
        val names=mutableListOf<Pair<String,String?>>()
        val link=GattLink(fake.radio,{DeviceId.ble(address)},timings,onVerifiedName={device,name->names+=device to name})
        try {
            link.connect()
            assertEquals(DeviceId.ble(address) to " BedJet Ω ",names.lastOrNull())
            assertTrue(fake.ports.single().reads.contains(Attr.NAME))
            assertEquals("BEDJET 3",Codec.parseText(link.query(Codec.Query.DEVICE_NAME)))
        } finally {link.shutdown()}
    }
    @Test fun callbackUsesSessionAddressEvenIfSelectedTargetChangesDuringQuery() = runBlocking {
        val fake=FakeRadio();fake.upcoming+=FakeRadio.Behaviour(nameBytes="Original unit".toByteArray())
        fake.behaviour=FakeRadio.Behaviour(nameBytes="Replacement unit".toByteArray())
        val replacement="11:22:33:44:55:66"
        var target=DeviceId.ble(address)
        val names=mutableListOf<Pair<String,String?>>()
        val link=GattLink(fake.radio,{target},timings,onName={_,_->target=DeviceId.ble(replacement)},
            onVerifiedName={device,name->names+=device to name})
        try {
            link.connect()
            // The name read on the first session stays bound to that session's address.
            assertTrue(names.contains(DeviceId.ble(address) to "Original unit"))
            assertFalse(names.contains(DeviceId.ble(replacement) to "Original unit"))
            // IR-02: the target changed during the attach, so the link ends on the new BedJet, not the old one.
            assertEquals(listOf(address,replacement),fake.connects)
            assertTrue(fake.ports.first().closed)
            assertEquals(DeviceId.ble(replacement) to "Replacement unit",names.last())
        }
        finally {link.shutdown()}
    }
    @Test fun unknownNameDoesNotPreventOrdinaryControl() = runBlocking {
        val fake=FakeRadio();val names=mutableListOf<Pair<String,String?>>()
        val link=GattLink(fake.radio,{DeviceId.ble(address)},timings,onVerifiedName={device,name->names+=device to name})
        try {
            link.connect();link.send(Codec.setFan(4))
            assertEquals(LinkState.READY,link.state.value)
            assertEquals(DeviceId.ble(address) to null,names.lastOrNull())
        } finally {link.shutdown()}
    }
    @Test fun timedOutOptionalReadRetiresSessionAndLateNameCannotBindToRetry() = runBlocking {
        val fake=FakeRadio();fake.behaviour=FakeRadio.Behaviour(nameBytes="unverified".toByteArray(),answersNames=false)
        val names=mutableListOf<Pair<String,String?>>()
        val link=GattLink(fake.radio,{DeviceId.ble(address)},timings,onVerifiedName={device,name->names+=device to name})
        try {
            link.connect()
            assertEquals(2,fake.ports.size)
            assertTrue(fake.ports.first().closed)
            assertEquals(LinkState.READY,link.state.value)
            fake.ports.first().sink.onRead(Attr.NAME,"late".toByteArray(),GATT_SUCCESS)
            fake.drain()
            assertFalse(names.any {it.second!=null})
        } finally {link.shutdown()}
    }
}
