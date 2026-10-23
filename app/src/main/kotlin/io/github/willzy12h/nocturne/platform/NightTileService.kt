package io.github.willzy12h.nocturne.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.latestStatusFrom
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.ui.MainActivity
import kotlinx.coroutines.launch

/** Navigation never takes a radio lease. Tonight's existing controls own the quick action and confirmation. */
class NightTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        // graph.scope is Main.immediate and latestStatusFrom never suspends (the latest report is already held), so
        // this runs at once, inside onStartListening, while qsTile is still ours to update.
        graph.scope.launch {
            val night=graph.repository.activeNight.value
            // Only the chosen BedJet's own report can show it idle; one from another unit leaves it unknown.
            val target=graph.repository.settings.value.deviceId()
            val knownIdle=graph.link.state.value == LinkState.READY && graph.link.latestStatusFrom(target)?.isRunning == false
            qsTile?.apply {
                state=state(night,knownIdle)
                subtitle=getString(when {
                    night?.confidence == Confidence.CONFIRMED -> R.string.feature_running
                    night == null && knownIdle -> R.string.feature_idle
                    else -> R.string.feature_unknown
                })
                updateTile()
            }
        }
    }
    // The PendingIntent overload exists only on API34+. The deprecated call is limited to API31–33.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val intent=openIntent(this)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(intent)
    }
    companion object {
        fun state(night: ActiveNight?, knownIdle: Boolean): Int =
            if (night?.confidence == Confidence.CONFIRMED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        fun openIntent(context: Context) = Intent(context, MainActivity::class.java)
            .putExtra("openTonight",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
