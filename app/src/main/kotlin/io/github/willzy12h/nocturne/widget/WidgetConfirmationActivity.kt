package io.github.willzy12h.nocturne.widget

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.ui.captureQuickAction
import io.github.willzy12h.nocturne.ui.showTemporaryActionDialog
import io.github.willzy12h.nocturne.ui.nativeActionDialogContext
import kotlinx.coroutines.launch

/** A direct widget PendingIntent opens only this translucent, excluded confirmation overlay. */
class WidgetConfirmationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val supplied = intent.getBundleExtra(EXTRA_REQUEST) ?: run { finish(); return }
        val parameters = actionParametersOf(
            WidgetActionCallback.COMMAND to supplied.getString(WidgetActionCallback.COMMAND.name).orEmpty(),
            WidgetActionCallback.PROFILE to supplied.getString(WidgetActionCallback.PROFILE.name).orEmpty(),
            WidgetActionCallback.TARGET to supplied.getString(WidgetActionCallback.TARGET.name).orEmpty(),
            WidgetActionCallback.NIGHT to supplied.getString(WidgetActionCallback.NIGHT.name).orEmpty(),
            WidgetActionCallback.REVISION to supplied.getString(WidgetActionCallback.REVISION.name).orEmpty(),
            WidgetActionCallback.PRESET to supplied.getString(WidgetActionCallback.PRESET.name).orEmpty(),
        )
        val original = widgetRequest(parameters) ?: run { finish(); return }
        val kind = supplied.getString(WidgetActionCallback.KIND.name) ?: WidgetActionCallback.QUICK
        val id = runCatching { GlanceAppWidgetManager(this).getGlanceIdBy(intent.getIntExtra(EXTRA_WIDGET, -1)) }
            .getOrNull() ?: run { finish(); return }
        // Dynamic Start selects at opening; the same selected profile goes through the later approval.
        val request = if (original.command == Command.START_NIGHT && original.profileId == null)
            original.copy(profileId = graph.repository.tonightsProfile()?.id) else original
        val current = graph.repository.activeNight.value
        if (!widgetNeedsConfirmation(request, current)) { finish(); return }
        val dialogContext = nativeActionDialogContext(this, graph)
        val quick = captureQuickAction(graph, request)
        if (quick != null) {
            showTemporaryActionDialog(this, graph, quick, R.string.feature_cancel, dialogContext = dialogContext) { selected ->
                lifecycleScope.launch {
                    try { WidgetDelivery.submit(applicationContext, id, kind, selected) }
                    finally { finish() }
                }
            }
            return
        }
        val title = when (request.command) {
            Command.OFF, Command.END_NIGHT -> R.string.widget_confirm_off
            Command.STOP_TRACKING -> R.string.recovery_stop_tracking_title
            else -> R.string.feature_replace
        }
        val message = when (request.command) {
            Command.OFF, Command.END_NIGHT -> getString(R.string.widget_confirm_off_message)
            Command.STOP_TRACKING -> getString(R.string.stop_tracking_text)
            else -> getString(R.string.widget_confirm_replacement, widgetActionLabel(this, request))
        }
        val dialog = AlertDialog.Builder(dialogContext).setTitle(title).setMessage(message)
            .setNegativeButton(R.string.feature_cancel) { _, _ -> finish() }
            .setPositiveButton(widgetActionLabel(this, request)) { _, _ ->
                // submit returns after handoff; finishing the overlay never cancels the service-owned request.
                lifecycleScope.launch {
                    try { WidgetDelivery.submit(applicationContext, id, kind, request.copy(replacementConfirmed = true)) }
                    finally { finish() }
                }
            }.setOnCancelListener { finish() }.create()
        dialog.show()
        val height = (56 * resources.displayMetrics.density).toInt()
        for (button in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(button).apply { minimumHeight = height; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f) }
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "widgetRequest"
        private const val EXTRA_WIDGET = "widgetId"
        fun intent(context: Context, request: CommandRequest, id: GlanceId, kind: String): Intent {
            val widget = GlanceAppWidgetManager(context).getAppWidgetId(id)
            val parameters = widgetParameters(request, kind)
            val extras = Bundle().apply { parameters.asMap().forEach { (key, value) -> putString(key.name, value as String) } }
            return Intent(context, WidgetConfirmationActivity::class.java)
                .putExtra(EXTRA_REQUEST, extras).putExtra(EXTRA_WIDGET, widget)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .setData(Uri.Builder().scheme("nocturne").authority("widget-confirm")
                    .appendPath(widget.toString()).appendPath(request.command.name).appendPath(request.profileId.orEmpty())
                    .appendPath(request.deviceId.orEmpty()).appendPath(request.expected?.nightId.orEmpty())
                    .appendPath(request.expected?.revision?.toString().orEmpty())
                    .appendPath(parameters[WidgetActionCallback.PRESET].orEmpty()).build())
        }
    }
}
