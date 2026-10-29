package io.github.willzy12h.nocturne.ui.features

import io.github.willzy12h.nocturne.ui.nocturneCheckboxColors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable fun HistoryExportButton(graph:Graph) {
    val settings by graph.repository.settings.collectAsState()
    val scope=rememberCoroutineScope()
    var dialog by remember {mutableStateOf(false)}
    var identifiers by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {uri->
        if(uri!=null && !graph.repository.settings.value.demoMode) scope.launch {
            try {
                if (graph.repository.settings.value.demoMode) return@launch
                val csv=HistoryCsv.encode(graph.repository.history.value,settings.fahrenheit,identifiers)
                withContext(Dispatchers.IO) {
                    if (graph.repository.settings.value.demoMode) throw kotlinx.coroutines.CancellationException("Demo session")
                    graph.context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(csv.toByteArray(Charsets.UTF_8))}
                    ?: error("No output stream")}
                status=graph.context.getString(R.string.feature_export_done)
            } catch(e:kotlinx.coroutines.CancellationException){throw e
            } catch(_:Exception){status=graph.context.getString(R.string.feature_export_failed)}
        }
    }
    Column {
        TextButton({identifiers=false;dialog=true}, enabled = !settings.demoMode) {Text(stringResource(R.string.feature_csv))}
        status?.let {Text(it)}
    }
    if(dialog && !settings.demoMode) AlertDialog(onDismissRequest={dialog=false},title={Text(stringResource(R.string.feature_csv))},text={
        Column {
            Text(stringResource(R.string.feature_csv_help))
            Row(Modifier.toggleable(identifiers,role=Role.Checkbox,onValueChange={identifiers=it})) {Checkbox(identifiers,null, colors = nocturneCheckboxColors());Text(stringResource(R.string.feature_identifiers))}
        }
    },confirmButton={TextButton({dialog=false;picker.launch("nocturne-history-${graph.now().toLocalDate()}.csv")}) {Text(stringResource(R.string.feature_save))}},
        dismissButton={TextButton({dialog=false}){Text(stringResource(R.string.feature_cancel))}})
}

@Composable fun HealthSleepOverlay(graph:Graph,record:NightRecord) {
    if (record.isTestRun || io.github.willzy12h.nocturne.core.link.DeviceId.isDemo(record.deviceId)) return
    val settings by graph.repository.settings.collectAsState()
    if(settings.demoMode || !settings.platform.healthSleepOverlay)return
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resume by remember {mutableIntStateOf(0)}
    var result by remember(record.id) {mutableStateOf<SleepOverlay?>(null)}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME)resume++}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(record,settings.platform.healthSleepOverlay,resume) {
        result=null
        result=AndroidSleepSource.reader(graph.context).read(record)
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.feature_health_source),style=MaterialTheme.typography.labelLarge)
        val value=result
        val message=when(value?.availability) {
            null->R.string.feature_health_loading
            SleepAvailability.ABSENT->R.string.feature_health_absent
            SleepAvailability.PERMISSION->R.string.feature_health_permission
            SleepAvailability.EMPTY->R.string.feature_health_empty
            SleepAvailability.ERROR->R.string.feature_health_error
            SleepAvailability.AVAILABLE->null
        }
        if(message!=null)Text(stringResource(message),style=MaterialTheme.typography.bodySmall)
        else if(value!=null) {
            val start=ZonedDateTime.parse(record.startedAt)
            val end=ZonedDateTime.parse(record.wakeAt)
            val total=Duration.between(start,end).toMillis().coerceAtLeast(1).toFloat()
            val colour=MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                value.stages.forEach { stage->
                    val x=Duration.between(start.toInstant(),stage.start).toMillis()/total*size.width
                    val width=Duration.between(stage.start,stage.end).toMillis()/total*size.width
                    drawRect(colour.copy(alpha=0.35f+(stage.stage.coerceIn(0,6)/6f)*0.65f),Offset(x,0f),Size(width, size.height))
                }
            }
            value.stages.forEach {stage->
                val label=stringResource(when(stage.stage) {1->R.string.feature_stage_awake;2->R.string.feature_stage_sleeping;3->R.string.feature_stage_out;4->R.string.feature_stage_light;5->R.string.feature_stage_deep;6->R.string.feature_stage_rem;else->R.string.feature_stage_unknown})
                val format=DateTimeFormatter.ofPattern("HH:mm XXX")
                Text(stringResource(R.string.feature_stage_line,stage.start.atZone(start.zone).format(format),stage.end.atZone(start.zone).format(format),label),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
