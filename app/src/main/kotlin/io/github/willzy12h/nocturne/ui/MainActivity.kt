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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.R
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

private enum class Tab(@androidx.annotation.StringRes val label: Int, @androidx.annotation.DrawableRes val icon: Int) {
    TONIGHT(R.string.tab_tonight, R.drawable.ic_nc_moon),
    PROFILES(R.string.tab_profiles, R.drawable.ic_nc_list),
    HISTORY(R.string.tab_history, R.drawable.ic_nc_hist),
    SETTINGS(R.string.tab_settings, R.drawable.ic_nc_gear),
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
    // Remote is a Tonight sub-page: no tab bar, a "‹ Tonight" link and Back return to Tonight.
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = remoteOpen) { remoteOpen = false }
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
    val density = LocalDensity.current
    LaunchedEffect(Unit) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(tonightRequest) {
        if (tonightRequest > 0) {
            draftJson?.let {
                parkedDrafts = parkedDrafts + it
                draftJson = null
            }
            tab = Tab.TONIGHT
            remoteOpen = false
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
        topBar = { Spacer(Modifier.statusBarsPadding()) },
        snackbarHost = {
            // Feedback floats over the screen as a small bubble; the content underneath never moves.
            SnackbarHost(snackbar) { data ->
                val tokens = paletteTokens
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                    val messageLayout = rememberTextMeasurer().measure(data.visuals.message, style = style,
                        constraints = Constraints(maxWidth = with(density) { (maxWidth - 40.dp).roundToPx().coerceAtLeast(1) }))
                    Surface(color = tokens.menu, contentColor = tokens.ink, shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, tokens.outline), shadowElevation = 8.dp,
                        modifier = Modifier.widthIn(max = 360.dp).testTag("feedback-bubble")) {
                        // Short notices dismiss on a tap; only long explanations need their own scrolling area.
                        if (messageLayout.lineCount <= 2) Text(data.visuals.message, style = style, textAlign = TextAlign.Center,
                            modifier = Modifier.clickable { data.dismiss() }.padding(horizontal = 16.dp, vertical = 10.dp))
                        else Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f, fill = false).heightIn(max = (LocalConfiguration.current.screenHeightDp * .25f).dp)
                                .testTag("snackbar-message-scroll").verticalScroll(rememberScrollState())) {
                                Text(data.visuals.message, style = style)
                            }
                            IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(48.dp).testTag("snackbar-dismiss")) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(io.github.willzy12h.nocturne.R.string.snackbar_dismiss))
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
                val tokens = paletteTokens
                if (!remoteOpen) NavigationBar(containerColor = tokens.ground, tonalElevation = 0.dp,
                    modifier = Modifier.drawBehind {
                        drawLine(tokens.hairline, androidx.compose.ui.geometry.Offset(0f, 0f),
                            androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx())
                    }.testTag("main-navigation")) {
                    Tab.entries.forEach {
                        val label = androidx.compose.ui.res.stringResource(it.label)
                        NavigationBarItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            modifier = if (compactNavigation) Modifier.semantics { contentDescription = label } else Modifier,
                            icon = { Icon(androidx.compose.ui.res.painterResource(it.icon), null, Modifier.size(22.dp)) },
                            label = if (compactNavigation) null else { { Text(label, fontSize = 10.5.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) } },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = tokens.ink, selectedTextColor = tokens.ink,
                                unselectedIconColor = tokens.ink3, unselectedTextColor = tokens.ink3,
                                indicatorColor = Color.Transparent),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        if (remoteOpen) Column(modifier) {
            NocturneTextButton(onClick = { remoteOpen = false }, modifier = Modifier.testTag("remote-back")) {
                Text(androidx.compose.ui.res.stringResource(R.string.parity_back,
                    androidx.compose.ui.res.stringResource(R.string.tab_tonight)), color = paletteTokens.accent)
            }
            RemoteScreen(model, Modifier.weight(1f))
        } else when (tab) {
            Tab.TONIGHT -> TonightScreen(model, modifier, onEdit = edit, onEditPhase = editPhase,
                onOpenRemote = { remoteOpen = true },
                onOpenBackups = { tab = Tab.SETTINGS; alarmsRequest = 0; backupsRequest++ },
                onOpenAlarmSettings = { tab = Tab.SETTINGS; backupsRequest = 0; alarmsRequest++ })
            Tab.PROFILES -> ProfilesScreen(model, modifier, onEdit = edit)
            Tab.HISTORY -> HistoryScreen(model, modifier)
            Tab.SETTINGS -> SettingsScreen(model, modifier, backupsRequest, alarmsRequest,
                onBackupsRequestHandled = { if (backupsRequest == it) backupsRequest = 0 },
                onAlarmsRequestHandled = { if (alarmsRequest == it) alarmsRequest = 0 })
        }
    }
}
