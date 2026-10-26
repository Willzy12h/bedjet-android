package io.github.willzy12h.nocturne.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.CommandRequest

/** Native widget/shortcut equivalent of NightGuard, retaining each entry's existing cancellation/navigation. */
internal fun showTemporaryActionDialog(
    activity: Activity,
    graph: Graph,
    captured: CapturedQuickAction,
    cancelLabel: Int,
    openTonight: (() -> Unit)? = null,
    dialogContext: Context = activity,
    submit: (CommandRequest) -> Unit,
) {
    val choice = captured.temporary
    val fahrenheit = graph.repository.settings.value.fahrenheit
    var minutes = choice.preset.minutes
    var temporary = graph.actions.previewTemporary(choice.request).plan != null
    val spacing = (16 * activity.resources.displayMetrics.density).toInt()
    fun textView() = TextView(dialogContext).apply {
        setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        setPadding(0, spacing / 2, 0, spacing / 2)
    }
    val body = LinearLayout(dialogContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(spacing, 0, spacing, spacing)
    }
    val running = textView().apply {
        val night = graph.repository.activeNight.value
        text = night?.let { activity.getString(R.string.temporary_running,
            it.profile?.name ?: activity.getString(R.string.tonights_night)) }
    }
    val details = textView()
    val duration = textView()
    val seek = SeekBar(dialogContext).apply {
        tag = "temporary-duration"
        contentDescription = activity.getString(R.string.slider_minutes)
        min = 1; max = choice.maximumMinutes
        progress = minutes.coerceIn(1, max)
        minimumHeight = (48 * activity.resources.displayMetrics.density).toInt()
    }
    val planned = textView()
    val explanation = textView()
    val refusal = textView()
    listOf(running, details, duration, seek, planned, explanation, refusal).forEach(body::addView)
    val scroll = ScrollView(dialogContext).apply { addView(body) }
    lateinit var dialog: AlertDialog
    fun render() {
        val request = choice.forMinutes(minutes)
        val preview = graph.actions.previewTemporary(request)
        details.text = if (temporary) temporaryDetails(activity, request, preview, fahrenheit).joinToString("\n")
            else requestedEndDetail(activity, choice, fahrenheit)
        duration.text = activity.getString(R.string.temporary_duration,
            Format.duration(minutes, Format.Words { id, args -> activity.getString(id, *args) }))
        duration.visibility = if (temporary) View.VISIBLE else View.GONE
        seek.visibility = duration.visibility
        val returnText = temporaryReturn(activity, preview)
        planned.text = returnText
        planned.visibility = if (temporary && returnText != null) View.VISIBLE else View.GONE
        explanation.setText(if (temporary) R.string.temporary_schedule else R.string.temporary_native_end)
        refusal.text = preview.refusal
        refusal.visibility = if (preview.refusal == null) View.GONE else View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setText(if (temporary) R.string.temporary_confirm else R.string.guard_confirm)
            isEnabled = !temporary || preview.plan != null
        }
    }
    val builder = AlertDialog.Builder(dialogContext).setTitle(R.string.temporary_title)
        .setSingleChoiceItems(arrayOf(activity.getString(R.string.temporary_choice), activity.getString(R.string.temporary_end_choice)),
            if (temporary) 0 else 1) { _, which -> temporary = which == 0; render() }
        .setView(scroll)
        .setPositiveButton(R.string.temporary_confirm, null)
        .setNegativeButton(cancelLabel) { _, _ -> activity.finish() }
        .setOnCancelListener { activity.finish() }
    if (openTonight != null) builder.setNeutralButton(R.string.feature_open_tonight) { _, _ -> openTonight() }
    dialog = builder.create()
    dialog.show()
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) { minutes = progress; render() }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    })
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val request = if (temporary) choice.forMinutes(minutes) else captured.end
        dialog.dismiss()
        submit(request)
    }
    render()
}
