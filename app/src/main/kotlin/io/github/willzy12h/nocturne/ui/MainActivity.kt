package io.github.willzy12h.nocturne.ui

import android.os.Bundle
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class MainActivity : ComponentActivity() {
    // Built from this activity's own Application rather than the process-wide default factory, which keeps the first one it saw.
    private val model: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(application) } } }
    private var tonightRequest by mutableIntStateOf(0)

    private fun consumeTonightRequest(intent: Intent?) {
        if (intent?.getBooleanExtra("openTonight", false) == true) {
            intent.removeExtra("openTonight")
            tonightRequest++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumeTonightRequest(intent)
        setContent {
            val settings by model.settings.collectAsState()
            var themeNow by remember { mutableStateOf(model.now()) }
            LaunchedEffect(Unit) { while (true) { delay(1_000); themeNow = model.now() } }
            val dark = settings.isDarkTheme(themeNow, isSystemInDarkTheme())
            // The bars are drawn over the app, so their icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            NocturneTheme(settings, themeNow) { NocturneApp(model, tonightRequest) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeTonightRequest(intent)
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

private enum class Tab(@androidx.annotation.StringRes val label: Int, val icon: ImageVector) {
    TONIGHT(io.github.willzy12h.nocturne.R.string.tab_tonight, Icons.Filled.Bedtime),
    PROFILES(io.github.willzy12h.nocturne.R.string.tab_profiles, Icons.Filled.ViewList),
    REMOTE(io.github.willzy12h.nocturne.R.string.tab_remote, Icons.Filled.SettingsRemote),
    HISTORY(io.github.willzy12h.nocturne.R.string.tab_history, Icons.Filled.History),
    SETTINGS(io.github.willzy12h.nocturne.R.string.tab_settings, Icons.Filled.Settings),
}

@androidx.compose.runtime.Composable
private fun NocturneApp(model: AppViewModel, tonightRequest: Int) {
    val configuration = LocalConfiguration.current
    val compactNavigation = configuration.fontScale >= 1.5f && configuration.screenWidthDp < 400
    val view = LocalView.current
    LaunchedEffect(model, view) {
        model.haptics.collect { kind ->
            when (kind) {
                OutcomeKind.ACCEPTED -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                OutcomeKind.CONFIRMED -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                else -> Unit
            }
        }
    }
    var tab by rememberSaveable { mutableStateOf(Tab.TONIGHT) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var parkedDraft by rememberSaveable { mutableStateOf(false) }
    // A new profile isn't in the repository until Save, so the editor keeps its starting point here (it survives recreation).
    var newProfileJson by rememberSaveable { mutableStateOf<String?>(null) }
    // Tonight's "Restore" opens Settings at the snapshots.
    var backupsRequest by remember { mutableIntStateOf(0) }
    val profiles by model.profiles.collectAsState()
    val edit: (Profile) -> Unit = { profile ->
        newProfileJson = if (profiles.none { it.id == profile.id }) Json.encodeToString(profile) else null
        editingId = profile.id
        draftJson = null
    }
    val snackbar = remember { SnackbarHostState() }
    // The snackbar floats over the content, so while one shows the content ends above it and nothing (Off) sits underneath.
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(Unit) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(tonightRequest) {
        if (tonightRequest > 0) {
            parkedDraft = editingId != null && draftJson != null
            editingId = null
            tab = Tab.TONIGHT
        }
    }

    editingId?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
            ?: newProfileJson?.let { runCatching { Json.decodeFromString<Profile>(it) }.getOrNull() }?.takeIf { it.id == id }
        if (profile != null) {
            val draft = draftJson?.let { runCatching { Json.decodeFromString<Profile>(it) }.getOrNull() }
            ProfileEditor(model, profile, onDone = { editingId = null; draftJson = null; parkedDraft = false; newProfileJson = null },
                initialDraft = draft, onDraftChange = { draftJson = Json.encodeToString(it) })
        } else editingId = null
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.onSizeChanged { snackbarHeight = it.height }) },
        bottomBar = {
            Column {
                if (parkedDraft && draftJson != null) {
                    androidx.compose.material3.TextButton(onClick = {
                        val draft = runCatching { Json.decodeFromString<Profile>(draftJson!!) }.getOrNull()
                        editingId = draft?.id
                        parkedDraft = false
                    }) { Text(androidx.compose.ui.res.stringResource(io.github.willzy12h.nocturne.R.string.main_resume_draft)) }
                }
                NavigationBar(modifier = Modifier.testTag("main-navigation")) {
                    Tab.entries.forEach {
                        val label = androidx.compose.ui.res.stringResource(it.label)
                        NavigationBarItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            modifier = if (compactNavigation) Modifier.semantics { contentDescription = label } else Modifier,
                            icon = { Icon(it.icon, null) },
                            label = if (compactNavigation) null else { { Text(label) } },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val snackbarInset = with(density) { snackbarHeight.toDp() }
        // Chips draw smaller than their 48 dp touch targets. Protect both expanded edges when scrolling brings
        // a visible chip to the viewport boundary; a snackbar already reserves more space at the bottom.
        val modifier = Modifier.padding(padding).padding(top = 8.dp, bottom = maxOf(snackbarInset, 8.dp))
        when (tab) {
            Tab.TONIGHT -> TonightScreen(model, modifier, onEdit = edit, onOpenBackups = { tab = Tab.SETTINGS; backupsRequest++ })
            Tab.PROFILES -> ProfilesScreen(model, modifier, onEdit = edit)
            Tab.REMOTE -> RemoteScreen(model, modifier)
            Tab.HISTORY -> HistoryScreen(model, modifier)
            Tab.SETTINGS -> SettingsScreen(model, modifier, backupsRequest)
        }
    }
}
