package io.github.willzy12h.nocturne.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.platform.PhoneMaker
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton

/**
 * Android's own battery prompt gives no reason, so Nocturne explains first: why overnight changes need it, what it
 * costs, and what Android will ask next. Nothing changes until Continue opens Android's prompt.
 */
@Composable
fun BatteryExplainDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("battery-explain"),
        title = { Text(stringResource(R.string.battery_explain_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.battery_explain_why), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.battery_explain_cost), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.battery_explain_next), style = MaterialTheme.typography.bodyMedium)
                // Makers with their own controls need those too; Android's setting alone isn't enough there.
                PhoneMaker.of()?.let { maker ->
                    Text(stringResource(R.string.battery_explain_maker), style = MaterialTheme.typography.bodySmall)
                    MakerTips(maker)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onContinue() }, modifier = Modifier.testTag("battery-explain-continue")) {
                Text(stringResource(R.string.battery_explain_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("battery-explain-later")) {
                Text(stringResource(R.string.battery_explain_later))
            }
        })
}

/** The maker's battery tips, folded away until asked for, with a button to its own settings screen. */
@Composable
fun MakerTips(maker: PhoneMaker) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag("setup-maker-tips")) {
        Text(stringResource(maker.title))
    }
    if (open) {
        Text(stringResource(maker.help), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { PhoneMaker.open(context, maker) }, modifier = Modifier.testTag("setup-maker-open")) {
            Text(stringResource(R.string.setup_maker_open))
        }
        Text(stringResource(R.string.setup_maker_open_help), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Where the battery fix goes. A person who restricted Nocturne's background use must lift that on its app page; the
 * battery-optimisation prompt alone wouldn't.
 */
@Suppress("BatteryLife")
fun batteryIntent(context: Context): Intent = Intent(
    if (context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true)
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    "package:${context.packageName}".toUri())
