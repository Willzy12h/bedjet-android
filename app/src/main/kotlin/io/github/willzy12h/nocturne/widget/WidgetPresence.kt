package io.github.willzy12h.nocturne.widget

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Android 16 and later: Android itself watches for the chosen BedJet and tells Nocturne when it comes into range, so an
 * asleep widget wakes without any background reads. The person allows it once ([associate]); the system then binds
 * [WidgetPresenceService] when the BedJet appears. Earlier Android versions keep the other wake-ups (opening the app,
 * a grey widget tap).
 */
object WidgetPresence {
    /** Device presence by association, with [DevicePresenceEvent], arrived in Android 16 (API 36). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    private fun address(context: Context): String? {
        val settings = context.graph.repository.settings.value
        return if (settings.demoMode) null else DeviceId.address(settings.deviceId())
    }

    @RequiresApi(36)
    private fun association(context: Context): AssociationInfo? {
        val address = address(context) ?: return null
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
        return runCatching { manager.myAssociations }.getOrNull()
            ?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
    }

    /** Whether the chosen BedJet is associated, so its presence wakes the widget. */
    fun associated(context: Context): Boolean = supported && association(context) != null

    /** Whether to offer the one-time prompt: Android 16+, a real BedJet chosen, not yet allowed. */
    fun offered(context: Context): Boolean = supported && address(context) != null && association(context) == null

    /**
     * Asks Android once to let Nocturne see the chosen BedJet's presence. [done] gets true once it is allowed and
     * presence is being observed, false if it was declined or failed.
     */
    fun associate(activity: Activity, done: (Boolean) -> Unit) {
        if (!supported) { done(false); return }
        associateOn36(activity, done)
    }

    @RequiresApi(36)
    private fun associateOn36(activity: Activity, done: (Boolean) -> Unit) {
        val address = address(activity) ?: run { done(false); return }
        val manager = activity.getSystemService(CompanionDeviceManager::class.java) ?: run { done(false); return }
        val filter = BluetoothLeDeviceFilter.Builder().setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build()).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        runCatching {
            manager.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    runCatching { activity.startIntentSender(intentSender, null, 0, 0, 0) }.onFailure { done(false) }
                }
                override fun onAssociationCreated(associationInfo: AssociationInfo) { done(observe(activity, associationInfo.id)) }
                override fun onFailure(error: CharSequence?) { done(false) }
            })
        }.onFailure { done(false) }
    }

    @RequiresApi(36)
    private fun observe(context: Context, id: Int): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching {
            manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
        }.isSuccess
    }

    /**
     * The chosen BedJet came into range. While the widget is grey this wakes it with one read (the screen may be off:
     * the person is home); an awake widget ignores it, since our own reads can make the BedJet advertise again.
     */
    @RequiresApi(36)
    internal fun appeared(context: Context, associationId: Int) {
        if (association(context)?.id != associationId) return
        val settings = context.graph.repository.settings.value
        val now = System.currentTimeMillis()
        if (!WidgetLinkRules.presenceReads(WidgetLink.facts(context), now, settings.deviceId() != null, settings.demoMode)) return
        WidgetLink.presenceRead(context, now)
        context.graph.scope.launch {
            try {
                // Only a good read wakes it; a miss leaves the widget as it was rather than restarting the tries.
                if (context.graph.actions.readForWidget() == io.github.willzy12h.nocturne.night.WidgetRead.READ)
                    WidgetLink.markRead(context, System.currentTimeMillis())
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { android.util.Log.w("Widget", "Presence read ended")
            } finally {
                WidgetRefresh.sync(context)
                WidgetReconnect.redraw(context)
            }
        }
    }
}

/** Bound by Android while the associated BedJet is in range (Android 16 and later). */
class WidgetPresenceService : CompanionDeviceService() {
    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) WidgetPresence.appeared(applicationContext, event.associationId)
    }
}
