package io.github.willzy12h.nocturne.widget

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal fun widgetDescendants(view: View): List<View> = listOf(view) +
    if (view is ViewGroup) (0 until view.childCount).flatMap { widgetDescendants(view.getChildAt(it)) } else emptyList()

/** The tappable pill or tile that shows [label] (a wide pill adds its temperature): its nearest clickable ancestor, as a launcher would hit it. */
internal fun widgetControl(root: View, label: String): View? {
    val text = widgetDescendants(root).filterIsInstance<TextView>().firstOrNull {
        Regex("${Regex.escape(label)}( [^ ]+°[CF]?)?").matches(it.text) } ?: return null
    return generateSequence<View>(text) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() }
}

/** Glance puts the click on a wrapper and the spoken description on the view inside it. */
internal fun widgetDescription(view: View): String =
    widgetDescendants(view).firstNotNullOfOrNull { it.contentDescription?.toString() }.orEmpty()

/** Every tappable control in the widget, outermost first. */
internal fun widgetControls(root: View): List<View> = widgetDescendants(root).filter { it.hasOnClickListeners() }

/** The command-to-Controls preset mapping the tests use: Warm is the Heat preset. */
internal fun quickPresetKey(command: io.github.willzy12h.nocturne.night.NightActions.Command): String? = when (command) {
    io.github.willzy12h.nocturne.night.NightActions.Command.WARM -> "HEAT"
    io.github.willzy12h.nocturne.night.NightActions.Command.COOL -> "COOL"
    io.github.willzy12h.nocturne.night.NightActions.Command.TURBO -> "TURBO"
    io.github.willzy12h.nocturne.night.NightActions.Command.BOOST -> "BOOST"
    io.github.willzy12h.nocturne.night.NightActions.Command.START_NIGHT -> PRESET_START
    io.github.willzy12h.nocturne.night.NightActions.Command.OFF -> PRESET_OFF
    else -> null
}
