package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.graph

/** A verified name for this target only; another connected unit's cached name is not attribution. */
fun bedJetTargetName(context: Context, deviceId: String?): String = when {
    deviceId == null -> context.getString(R.string.runtime_choose_target)
    DeviceId.isDemo(deviceId) -> context.getString(R.string.settings_demo)
    else -> context.graph.bleLink.deviceNameFor(deviceId) ?: deviceId.removePrefix("ble:")
}
