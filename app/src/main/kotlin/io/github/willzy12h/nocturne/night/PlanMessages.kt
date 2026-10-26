package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Preparation
import io.github.willzy12h.nocturne.core.plan.NightDurationException
import io.github.willzy12h.nocturne.core.plan.NightDurationProblem
import io.github.willzy12h.nocturne.core.plan.Preview

private fun NightDurationProblem.text(context: Context): String = context.getString(when (this) {
    NightDurationProblem.OUTSIDE_WINDOW -> R.string.night_duration_outside_window
    NightDurationProblem.TOO_LONG -> R.string.night_duration_too_long
})

/** Only these duration reasons are translated here; unrelated compiler behaviour is unchanged. */
internal fun Preview.refusalText(context: Context): String? = durationProblem?.text(context) ?: refusalReason
internal fun Preparation.Refused.refusalText(context: Context): String = durationProblem?.text(context) ?: reason
internal fun Throwable.planMessage(context: Context): String? = (this as? NightDurationException)?.problem?.text(context) ?: message
