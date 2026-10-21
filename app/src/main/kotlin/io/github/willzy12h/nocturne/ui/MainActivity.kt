package io.github.willzy12h.nocturne.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.widget.WidgetUpdater

class MainActivity : ComponentActivity() {
    // Built from this activity's own Application rather than the process-wide default factory, which keeps the first one it saw.
    private val model: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(application) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WidgetUpdater.start(this)
        setContent {
            val settings by model.settings.collectAsState()
            val dark = settings.themeMode.isDark(isSystemInDarkTheme())
            // The bars are drawn over the app, so their icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            NocturneTheme(settings.themeMode) { NocturneApp(model) }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onScreenVisible()
    }

    override fun onStop() {
        model.onScreenHidden()
        super.onStop()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TONIGHT("Tonight", Icons.Filled.Bedtime),
    PROFILES("Profiles", Icons.Filled.ViewList),
    REMOTE("Remote", Icons.Filled.SettingsRemote),
    HISTORY("History", Icons.Filled.History),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@androidx.compose.runtime.Composable
private fun NocturneApp(model: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.TONIGHT) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { model.messages.collect { snackbar.showSnackbar(it) } }

    editing?.let { profile ->
        ProfileEditor(model, profile, onDone = { editing = null })
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(selected = tab == it, onClick = { tab = it }, icon = { Icon(it.icon, null) }, label = { Text(it.label) })
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.TONIGHT -> TonightScreen(model, modifier, onEdit = { editing = it })
            Tab.PROFILES -> ProfilesScreen(model, modifier, onEdit = { editing = it })
            Tab.REMOTE -> RemoteScreen(model, modifier)
            Tab.HISTORY -> HistoryScreen(model, modifier)
            Tab.SETTINGS -> SettingsScreen(model, modifier)
        }
    }
}
