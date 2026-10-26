package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.ParcelUuid
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.SubmissionTarget
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** No state-sharing claim: the only interop packet is Lull's derived service UUID. */
class PhoneHandover(private val graph: Graph) {
    suspend fun release() = graph.actions.releaseForOtherPhone()
    suspend fun takeBack() = graph.actions.takeBack()
    suspend fun peerRequest(device: String? = graph.repository.settings.value.deviceId()) = withContext(SubmissionTarget(device)) {
        graph.actions.releaseForOtherPhone(graph.clock.millis()+10_000,declineIfBusy=true)
    }
    private fun permitted(permission: String)=graph.context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    private fun name(): String? {
        val settings=graph.repository.settings.value
        if (settings.demoMode || !settings.platform.lullHandover) return null
        val device = settings.deviceId() ?: return null
        return graph.bleLink.deviceNameFor(device)?.takeIf {it.substringBefore('\u0000').isNotEmpty()}
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch() {
        graph.scope.launch {
            combine(graph.repository.settings,graph.bleLink.verifiedNames,graph.sessions.released,
                graph.sessions.statusDemand) {_,_,_,_->Unit}
                .flatMapLatest {graph.link.state}.collectLatest { state ->
                    val device=graph.repository.settings.value.deviceId() ?: return@collectLatest
                    val name=name() ?: return@collectLatest
                    if (!graph.sessions.hasStatusDemand || state != LinkState.READY || graph.sessions.released.value ||
                        !permitted(Manifest.permission.BLUETOOTH_SCAN)) return@collectLatest
                    val scanner=graph.context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return@collectLatest
                    val uuid=ParcelUuid(PlatformPolicy.handoverUuid(name))
                    coroutineScope {
                        var yielding:Job?=null
                        val callback=object:ScanCallback() {
                            override fun onScanResult(callbackType:Int,result:ScanResult) {
                                if (result.scanRecord?.serviceUuids?.contains(uuid) != true || yielding?.isActive==true) return
                                yielding=launch { peerRequest(device) }
                            }
                        }
                        try {
                            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(uuid).build()),
                                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),callback)
                            awaitCancellation()
                        } catch (_: SecurityException) { /* Revocation stops optional interop, never control. */
                        } finally {runCatching {scanner.stopScan(callback)}}
                    }
                }
        }
    }
    suspend fun request(): CommandOutcome {
        val name=name() ?: return refused(R.string.feature_handover_name)
        if (!permitted(Manifest.permission.BLUETOOTH_ADVERTISE)) return refused(R.string.feature_advertise_permission)
        val advertiser=graph.context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
            ?: return refused(R.string.feature_advertise_unavailable)
        val started=CompletableDeferred<Boolean>()
        val callback=object:AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect:AdvertiseSettings) {started.complete(true)}
            override fun onStartFailure(errorCode:Int) {started.complete(false)}
        }
        try {
            advertiser.startAdvertising(AdvertiseSettings.Builder().setConnectable(false).setTimeout(8_000).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(PlatformPolicy.handoverUuid(name))).setIncludeDeviceName(false).build(),callback)
            if (!withTimeout(3_000) {started.await()}) return refused(R.string.feature_advertise_unavailable)
            delay(8_000)
            return CommandOutcome(OutcomeKind.LOCAL,graph.context.getString(R.string.feature_handover_requested),origin=CommandOrigin.APP)
        } catch (e: CancellationException) {throw e
        } catch (_: Exception) {return refused(R.string.feature_advertise_unavailable)
        } finally {
            try { advertiser.stopAdvertising(callback) }
            catch (_: SecurityException) { /* Permission revoked. */ }
            catch (_: Exception) { /* Adapter disappeared; preserve the request outcome/cancellation. */ }
        }
    }
    private fun refused(resource:Int)=CommandOutcome(OutcomeKind.REFUSED,graph.context.getString(resource),origin=CommandOrigin.APP)
}
