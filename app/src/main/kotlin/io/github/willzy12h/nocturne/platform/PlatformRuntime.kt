package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.store.deviceId
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

class PlatformRuntime(private val graph: Graph) {
    val handover=PhoneHandover(graph)
    fun start() {
        PlatformNotifications.channel(graph.context)
        graph.scope.launch {
            combine(graph.repository.settingsPublication,graph.repository.profiles) {_,_->Unit}.collect {
                ChargerJobs.sync(graph)
                BedtimeReminders.sync(graph)
            }
        }
        handover.watch()
    }
    suspend fun observe(status: io.github.willzy12h.nocturne.core.protocol.DeviceStatus,target:String?) {
        if (target == null || graph.link.state.value != LinkState.READY) return
        val name=if (graph.repository.settings.value.demoMode) "BEDJET-DEMO" else graph.bleLink.deviceName
        graph.repository.updateAutomation { it.copy(observed=DeviceObservation(target,name,status.mode.name,status.isRunning,graph.now().toString())) }
    }
}
