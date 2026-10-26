package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.BluetoothAvailability
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind

/** A visible, non-modal route through Android's permission and enable requests. */
@Composable
fun BluetoothBanner(model: AppViewModel) {
    val off by model.bluetoothOff.collectAsState()
    if (!off) return
    val context = LocalContext.current
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshBluetooth() }
    fun launchEnable() {
        try { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        catch (_: RuntimeException) {
            model.report(CommandOutcome(OutcomeKind.FAILED, context.getString(R.string.bluetooth_enable_unavailable), origin = CommandOrigin.APP))
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) launchEnable()
        model.refreshBluetooth()
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bluetooth_off), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.bluetooth_off_help), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    permissions.launch(BluetoothAvailability.permissions)
                else launchEnable()
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bluetooth_enable)) }
        }
    }
}
