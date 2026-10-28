package io.github.willzy12h.nocturne.ui

import android.os.Bundle
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.serialization.Serializable
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
        if (savedInstanceState == null) consumeTonightRequest(intent)
        setContent {
            val settings by model.settings.collectAsState()
            val theme = rememberThemePresentation(settings, model::now)
            val dark = theme.palette.tokens.dark
            // The bars are drawn over the app, so their icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            NocturneTheme(settings, theme.at, paletteOverride = theme.palette) {
                NocturneApp(model, tonightRequest)
                ThemeDuePrompt(theme)
            }
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

@Serializable
private data class ProfileDraft(val original: Profile, val edited: Profile, val selection: EditorPhaseSelection? = null)

@androidx.compose.runtime.Composable
private fun NocturneApp(model: AppViewModel, tonightRequest: Int) {
    val settings by model.settings.collectAsState()
    // Editor drafts and captured dialogues belong to their real or disposable demo session.
    key(settings.demoMode) { SessionApp(model, tonightRequest) }
}

@androidx.compose.runtime.Composable
private fun SessionApp(model: AppViewModel, tonightRequest: Int) {
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
    val setupRequest by model.setupChecklistRequest.collectAsState()
    LaunchedEffect(setupRequest) { if (setupRequest > 0) tab = Tab.TONIGHT }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    // Every interrupted editor keeps its own baseline and edits, including profiles not yet saved to the repository.
    var parkedDrafts by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Tonight's "Restore" opens Settings at the snapshots.
    var backupsRequest by remember { mutableIntStateOf(0) }
    var alarmsRequest by remember { mutableIntStateOf(0) }
    val edit: (Profile) -> Unit = { profile ->
        val parked = parkedDrafts.firstOrNull { Json.decodeFromString<ProfileDraft>(it).original.id == profile.id }
        draftJson = parked ?: Json.encodeToString(ProfileDraft(profile, profile))
        if (parked != null) parkedDrafts = parkedDrafts - parked
    }
    val editPhase: (Profile, EditorPhaseSelection) -> Unit = { profile, selection ->
        edit(profile)
        draftJson = draftJson?.let { encoded ->
            val saved = Json.decodeFromString<ProfileDraft>(encoded)
            // A parked draft can have different phase indices from the curve the person just selected.
            Json.encodeToString(saved.copy(selection = selection.takeIf { saved.edited == profile }))
        }
    }
    val snackbar = remember { SnackbarHostState() }
    // The snackbar floats over the content, so while one shows the content ends above it and nothing (Off) sits underneath.
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(Unit) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(tonightRequest) {
        if (tonightRequest > 0) {
            draftJson?.let {
                parkedDrafts = parkedDrafts + it
                draftJson = null
            }
            tab = Tab.TONIGHT
        }
    }

    draftJson?.let { json ->
        val draft = Json.decodeFromString<ProfileDraft>(json)
        ProfileEditor(model, draft.original, onDone = { draftJson = null },
            initialDraft = draft.edited, initialPhase = draft.selection, onDraftChange = { edited ->
                val updated = Json.encodeToString(draft.copy(edited = edited))
                if (draftJson?.let { Json.decodeFromString<ProfileDraft>(it).original.id } == draft.original.id) {
                    draftJson = updated
                } else {
                    // A final edit callback can arrive after Tonight has parked this editor in the same frame.
                    parkedDrafts = parkedDrafts.map { parked ->
                        if (Json.decodeFromString<ProfileDraft>(parked).original.id == draft.original.id) updated else parked
                    }
                }
            })
        return
    }
    Scaffold(
        topBar = { Column(Modifier.statusBarsPadding()) {
            StatusHeader(model)
            BluetoothBanner(model)
            DemoModePanel(model, showTryDemo = false)
        } },
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.onSizeChanged { snackbarHeight = it.height }) { data ->
                BoxWithConstraints {
                    val style = MaterialTheme.typography.bodyMedium
                    val messageLayout = rememberTextMeasurer().measure(data.visuals.message, style = style,
                        constraints = Constraints(maxWidth = with(density) { (maxWidth - 56.dp).roundToPx().coerceAtLeast(1) }))
                    // Preserve Material's compact layout; only long explanations need their own scrolling area.
                    if (messageLayout.lineCount <= 2) Snackbar(data)
                    else {
                        Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = MaterialTheme.shapes.small, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).heightIn(max = (LocalConfiguration.current.screenHeightDp * .25f).dp)
                                    .testTag("snackbar-message-scroll").verticalScroll(rememberScrollState())) {
                                    Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(48.dp).testTag("snackbar-dismiss")) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(io.github.willzy12h.nocturne.R.string.snackbar_dismiss))
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
                if (parkedDrafts.isNotEmpty()) {
                    NocturneTextButton(onClick = {
                        // Resume the oldest parked editor first; later drafts remain available after Save or Discard.
                        draftJson = parkedDrafts.first()
                        parkedDrafts = parkedDrafts.drop(1)
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
        val modifier = Modifier.padding(padding).padding(bottom = snackbarInset)
        when (tab) {
            Tab.TONIGHT -> TonightScreen(model, modifier, onEdit = edit, onEditPhase = editPhase,
                onOpenBackups = { tab = Tab.SETTINGS; alarmsRequest = 0; backupsRequest++ },
                onOpenAlarmSettings = { tab = Tab.SETTINGS; backupsRequest = 0; alarmsRequest++ })
            Tab.PROFILES -> ProfilesScreen(model, modifier, onEdit = edit)
            Tab.REMOTE -> RemoteScreen(model, modifier)
            Tab.HISTORY -> HistoryScreen(model, modifier)
            Tab.SETTINGS -> SettingsScreen(model, modifier, backupsRequest, alarmsRequest,
                onBackupsRequestHandled = { if (backupsRequest == it) backupsRequest = 0 },
                onAlarmsRequestHandled = { if (alarmsRequest == it) alarmsRequest = 0 })
        }
    }
}
