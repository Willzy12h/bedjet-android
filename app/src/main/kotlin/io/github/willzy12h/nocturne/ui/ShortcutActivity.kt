package io.github.willzy12h.nocturne.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.service.BedJetService

/** Launcher shortcuts, Samsung Routines and Bixby open this; it passes the command to the service and closes. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent.getStringExtra("command")?.let { runCatching { NightActions.Command.valueOf(it) }.getOrNull() }
        if (command != null) {
            BedJetService.start(this, command, intent.getStringExtra("profile"))
            Toast.makeText(this, "Sending to your BedJet…", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
