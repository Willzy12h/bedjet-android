package io.github.willzy12h.nocturne.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.service.BedJetService

/** Exported for launcher shortcuts and Samsung Routines/Bixby: every command needs a visible confirmation. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent.getStringExtra("command")?.let { runCatching { NightActions.Command.valueOf(it) }.getOrNull() }
        if (command == null) { finish(); return }
        val captured = graph.actions.capture(command, intent.getStringExtra("profile"), requireIdentity = true)
        AlertDialog.Builder(this)
            .setTitle(R.string.runtime_confirm_title)
            .setMessage(getString(R.string.runtime_confirm_command, NightActions.commandLabel(this, command)))
            .setPositiveButton(R.string.runtime_confirm) { _, _ ->
                BedJetService.start(this, captured.copy(replacementConfirmed = true)); finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
