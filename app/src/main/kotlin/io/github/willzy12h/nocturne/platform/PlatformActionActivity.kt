package io.github.willzy12h.nocturne.platform

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.*
import io.github.willzy12h.nocturne.service.BedJetService
import java.time.format.DateTimeFormatter

/** Private, explicit widget/reminder preview. Existing-night identity comes from the rendered widget intent. */
class PlatformActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command=intent.getStringExtra("command")?.let { runCatching { NightActions.Command.valueOf(it) }.getOrNull() } ?: NightActions.Command.START_NIGHT
        val profileId=intent.getStringExtra("profile")
        val repo=graph.repository
        if (profileId != null && repo.profile(profileId) == null) {
            AlertDialog.Builder(this).setMessage(R.string.feature_deleted_profile).setPositiveButton(android.R.string.ok){_,_->finish()}.setOnCancelListener{finish()}.show()
            return
        }
        val dynamic=command == NightActions.Command.START_NIGHT && profileId == null
        val request=if (intent.getBooleanExtra("rendered",false) && !dynamic) readRequest(intent)
            else graph.actions.capture(command,profileId,requireIdentity=true)
        val profile=profileId?.let(repo::profile) ?: repo.tonightsProfile()
        val now=graph.now(); val settings=repo.settings.value
        val alarm=AlarmSource.describe(this,settings)
        val preview=profile?.let { NightPreview.forStartNow(it,now,alarm?.time,settings.resolveWakeOverride(now),settings,alarm?.creator) }
        val replacement=ReplacementPolicy.decide(command,repo.activeNight.value).needsConfirmation
        if (command != NightActions.Command.START_NIGHT && !replacement) {
            runRequest(request, false)
            return
        }
        val description=if (command == NightActions.Command.START_NIGHT) {
            preview?.refusalText(this) ?: profile?.let { getString(R.string.feature_preview_wake,it.name,preview?.wake?.time?.format(DateTimeFormatter.ofPattern("EEE HH:mm"))) }
                ?: getString(R.string.feature_deleted_profile)
        } else getString(when(command) {
            NightActions.Command.BOOST -> R.string.feature_boost
            NightActions.Command.TURBO -> R.string.feature_turbo
            NightActions.Command.COOL -> R.string.feature_cool
            NightActions.Command.WARM -> R.string.feature_warm
            else -> R.string.feature_continue
        })
        val dialog=AlertDialog.Builder(this).setTitle(if (replacement) R.string.feature_replace else R.string.feature_preview)
            .setMessage(description).setNegativeButton(R.string.feature_cancel){_,_->finish()}
            .setNeutralButton(R.string.feature_open_tonight){_,_->startActivity(NightTileService.openIntent(this));finish()}
            .setOnCancelListener{finish()}
        if (command != NightActions.Command.START_NIGHT || preview?.plan != null) dialog.setPositiveButton(R.string.feature_continue) {_,_->
            runRequest(request, replacement)
        }
        dialog.show()
    }
    private fun runRequest(request: CommandRequest, replacement: Boolean) {
            val progress=AlertDialog.Builder(this).setMessage(R.string.feature_working)
                .setOnCancelListener{finish()}.show()
            lifecycleScope.launch {
                val message = try {
                    BedJetService.runOwned(this@PlatformActionActivity, notifyFailures=false) {
                        graph.actions.execute(request.copy(replacementConfirmed=replacement))
                    }.message
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { getString(R.string.feature_operation_timeout)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { e.message ?: getString(R.string.runtime_failed) }
                progress.dismiss()
                if (!isFinishing) AlertDialog.Builder(this@PlatformActionActivity)
                    .setTitle(R.string.feature_result).setMessage(message)
                    .setPositiveButton(android.R.string.ok){_,_->finish()}
                    .setOnCancelListener{finish()}.show()
            }
    }
    companion object {
        fun intent(context: Context, request: CommandRequest) = Intent(context,PlatformActionActivity::class.java)
            .putExtra("rendered",true).putExtra("command",request.command.name).putExtra("profile",request.profileId)
            .putExtra("device",request.deviceId).putExtra("night",request.expected?.nightId)
            .putExtra("revision",request.expected?.revision ?: -1).putExtra("strict",request.requireIdentity)
            // Glance/PendingIntent equality must not replace an older widget's identity with a newer one.
            .setData(android.net.Uri.parse("nocturne://widget/${request.command}/${request.profileId}/${request.deviceId}/${request.expected?.nightId}/${request.expected?.revision}"))
        fun readRequest(intent: Intent) = CommandRequest(
            NightActions.Command.valueOf(intent.getStringExtra("command")!!),intent.getStringExtra("profile"),intent.getStringExtra("device"),
            intent.getStringExtra("night")?.let { NightIdentity(it,intent.getIntExtra("revision",-1)) },intent.getBooleanExtra("strict",true))
    }
}
