package io.github.willzy12h.nocturne.ui

import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightAlarms
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.boostDescription
import io.github.willzy12h.nocturne.service.BedJetService

/** Exported for launcher shortcuts and Samsung Routines/Bixby: every command needs a visible confirmation. */
class ShortcutActivity : Activity() {
    companion object {
        private const val EXTRA_CAPTURED = "capturedConfirmation"
        private const val EXTRA_TARGET = "confirmationTarget"
        fun confirmationIntent(context: Context, request: CommandRequest): Intent =
            Intent(context, ShortcutActivity::class.java).putExtra("command", request.command.name)
                .putExtra("profile", request.profileId).putExtra(EXTRA_CAPTURED, true).putExtra(EXTRA_TARGET, request.deviceId)
                .putExtra(NightAlarms.EXTRA_NIGHT, request.expected?.nightId)
                .putExtra(NightAlarms.EXTRA_REVISION, request.expected?.revision ?: -1)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent.getStringExtra("command")?.let { runCatching { NightActions.Command.valueOf(it) }.getOrNull() }
        if (command == null) { finish(); return }
        val current = graph.actions.capture(command, intent.getStringExtra("profile"), requireIdentity = true)
        // Notification requests retain render-time identity. External extras never waive confirmation.
        val captured = if (intent.getBooleanExtra(EXTRA_CAPTURED, false)) current.copy(
            deviceId = intent.getStringExtra(EXTRA_TARGET), expected = NightAlarms.identity(intent)) else current
        if (graph.repository.activeNight.value != null) captureQuickAction(graph,
            captured.copy(origin = if (intent.getBooleanExtra(EXTRA_CAPTURED, false)) CommandOrigin.NOTIFICATION else CommandOrigin.SHORTCUT))?.let { quick ->
            showTemporaryActionDialog(this, graph, quick, android.R.string.cancel) { selected ->
                BedJetService.start(this, selected); finish()
            }
            return
        }
        val recovery = command == NightActions.Command.END_NIGHT || command == NightActions.Command.RESUME
        AlertDialog.Builder(this)
            .setTitle(when (command) {
                NightActions.Command.END_NIGHT -> R.string.guard_title
                NightActions.Command.RESUME -> R.string.guard_resume_title
                else -> R.string.runtime_confirm_title
            })
            .setMessage(when (command) {
                NightActions.Command.END_NIGHT -> getString(R.string.runtime_confirm_stop)
                NightActions.Command.RESUME -> getString(R.string.guard_resume_body)
                NightActions.Command.BOOST -> getString(R.string.runtime_confirm_command,
                    boostDescription(this, captured.actionPreset?.minutes ?: graph.repository.settings.value.boostMinutes))
                else -> getString(R.string.runtime_confirm_command, NightActions.commandLabel(this, command))
            })
            .setPositiveButton(if (recovery) NightActions.commandLabel(this, command) else getString(R.string.runtime_confirm)) { _, _ ->
                BedJetService.start(this, captured.copy(replacementConfirmed = true)); finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
