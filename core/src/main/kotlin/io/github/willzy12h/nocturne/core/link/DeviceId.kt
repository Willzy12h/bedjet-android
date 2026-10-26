package io.github.willzy12h.nocturne.core.link

/**
 * Names the BedJet a night, command or backup belongs to: [DEMO] for the simulated unit, or `ble:<MAC>` for a real
 * one. Stored as a plain string so saved data stays readable.
 */
object DeviceId {
    const val DEMO = "demo"
    private const val BLE_PREFIX = "ble:"

    fun ble(address: String): String = BLE_PREFIX + address.uppercase()

    fun isDemo(id: String?): Boolean = id == DEMO

    /** Whether two ids name the same unit; Bluetooth addresses compare without regard to case. */
    fun same(a: String?, b: String?): Boolean = a.equals(b, ignoreCase = true)

    /** The Bluetooth address of a real BedJet's id, or null for the demo unit or anything unrecognised. */
    fun address(id: String?): String? = id?.takeIf { it.startsWith(BLE_PREFIX) }?.removePrefix(BLE_PREFIX)
}
