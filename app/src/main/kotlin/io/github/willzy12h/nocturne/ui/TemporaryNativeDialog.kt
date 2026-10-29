package io.github.willzy12h.nocturne.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandRequest

/** Native widget/shortcut equivalent of NightGuard, retaining each entry's existing cancellation/navigation. */
internal fun showTemporaryActionDialog(
    activity: Activity,
    graph: Graph,
    captured: CapturedQuickAction,
    cancelLabel: Int,
    openTonight: (() -> Unit)? = null,
    dialogContext: Context = nativeActionDialogContext(activity, graph),
    submit: (CommandRequest) -> Unit,
) {
    val choice = captured.temporary
    val fahrenheit = graph.repository.settings.value.fahrenheit
    var minutes = choice.preset.minutes
    var temporary = graph.actions.previewTemporary(choice.request).plan != null
    val spacing = (16 * activity.resources.displayMetrics.density).toInt()
    val minimumTarget = ((if (captured.end.origin == CommandOrigin.WIDGET) 56 else 48) *
        activity.resources.displayMetrics.density).toInt()
    fun textView() = TextView(dialogContext).apply {
        setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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
        // ProgressBar's own measurement does not honor View.minimumHeight for its touch surface.
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minimumTarget)
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
        val refusalText = if (temporary) preview.refusal else temporaryEndRefusal(activity, choice) ?: preview.refusal
        refusal.text = refusalText
        refusal.visibility = if (refusalText == null) View.GONE else View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setText(if (temporary) R.string.temporary_confirm else R.string.guard_confirm)
            isEnabled = if (temporary) preview.plan != null else choice.canEnd
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
    styleNativeDialog(dialog, nativeDialogPalette(activity, graph))
    listOf(running, details, duration, planned, explanation, refusal).forEach { it.setTextColor(nativeDialogPalette(activity, graph).tokens.ink.toArgb()) }
    val choices = dialog.listView
    val originalAdapter = choices.adapter
    choices.adapter = object : ListAdapter by originalAdapter {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            originalAdapter.getView(position, convertView, parent).apply { minimumHeight = minimumTarget }
    }
    choices.setItemChecked(if (temporary) 0 else 1, true)
    for (id in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
        dialog.getButton(id)?.apply {
            minimumHeight = minimumTarget
            minimumWidth = minimumTarget
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
    }
    // Material normally gives a custom panel zero weight when it also has a choice list. Let only
    // our scrolling body shrink so native choices and buttons retain their measured space.
    val choiceParents = generateSequence(choices.parent) { it.parent }.toSet()
    val customPanel = generateSequence<View>(scroll) { it.parent as? View }
        .firstOrNull { it.parent in choiceParents && it.layoutParams is LinearLayout.LayoutParams }
    customPanel?.let { panel ->
        panel.layoutParams = (panel.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f }
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) { minutes = progress; render() }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    })
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (!temporary && !choice.canEnd) return@setOnClickListener
        val request = if (temporary) choice.forMinutes(minutes) else captured.end
        dialog.dismiss()
        submit(request)
    }
    render()
}

/** Shortcut activities are translucent; their window theme is not a readable dialog palette. */
internal fun nativeActionDialogContext(activity: Activity, graph: Graph): Context {
    val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val dark = nativeThemeIsDark(activity, graph.repository.settings.value, graph.now(), systemDark)
    return ContextThemeWrapper(activity, if (dark) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert)
}

internal fun nativeDialogPalette(activity: Activity, graph: Graph): io.github.willzy12h.nocturne.core.store.Palette {
    val settings = graph.repository.settings.value
    val now = graph.now()
    val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val requested = settings.palette(now, systemDark)
    return if (!nativeThemeIsDark(activity, settings, now, systemDark) || requested.tokens.dark) requested
        else listOf(settings.nightPalette, settings.dayPalette).firstOrNull { it.tokens.dark }
            ?: io.github.willzy12h.nocturne.core.store.Palette.MIDNIGHT_INK
}

/** Apply the chosen surface to native alert panels without altering choice or disabled-state controls. */
internal fun styleNativeDialog(dialog: AlertDialog, palette: io.github.willzy12h.nocturne.core.store.Palette) {
    styleNativeDialog(dialog, palette.tokens)
}

internal fun styleNativeDialog(dialog: AlertDialog, tokens: PaletteTokens) {
    val window = dialog.window ?: return
    fun background() = GradientDrawable().apply { setColor(tokens.sheet.toArgb()); cornerRadius = 12 * dialog.context.resources.displayMetrics.density }
    val root = window.decorView
    fun tint(view: View) {
        val left = view.paddingLeft; val top = view.paddingTop
        val right = view.paddingRight; val bottom = view.paddingBottom
        val drawable = view.background?.mutate() ?: background()
        drawable.setTint(tokens.sheet.toArgb())
        view.background = drawable
        view.setPadding(left, top, right, bottom)
    }
    tint(root)
    val panelId = nativeDialogId(root, "parentPanel")
    if (panelId != 0) root.findViewById<View>(panelId)?.let(::tint)
    val titleId = nativeDialogId(root, "alertTitle")
    if (titleId != 0) root.findViewById<TextView>(titleId)?.setTextColor(tokens.ink.toArgb())
    root.findViewById<TextView>(android.R.id.message)?.setTextColor(tokens.ink.toArgb())
    val buttonColors = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(tokens.ink.copy(alpha = .38f).compositeOver(tokens.sheet).toArgb(), tokens.ink.toArgb()))
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { id ->
        dialog.getButton(id)?.setTextColor(buttonColors)
    }
}

/** Private framework IDs have no public android.R constant. This never reflects application resources.
 * OEM absence is handled by the caller; the root, public message and buttons are styled independently. */
@android.annotation.SuppressLint("DiscouragedApi")
private fun nativeDialogId(view: View, name: String): Int = view.resources.getIdentifier(name, "id", "android")
