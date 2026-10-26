package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Preparation
import io.github.willzy12h.nocturne.core.night.TemporaryRefusal
import io.github.willzy12h.nocturne.core.plan.NightDurationException
import io.github.willzy12h.nocturne.core.plan.NightDurationProblem
import io.github.willzy12h.nocturne.core.plan.Preview

private fun NightDurationProblem.text(context: Context): String = context.getString(when (this) {
    NightDurationProblem.OUTSIDE_WINDOW -> R.string.night_duration_outside_window
    NightDurationProblem.TOO_LONG -> R.string.night_duration_too_long
})

private fun TemporaryRefusal.text(context: Context): String = context.getString(when (this) {
    TemporaryRefusal.NOT_CONFIRMED -> R.string.runtime_temporary_not_confirmed
    TemporaryRefusal.MANUAL -> R.string.runtime_temporary_manual
    TemporaryRefusal.ENDED -> R.string.runtime_temporary_ended
    TemporaryRefusal.MISSING_SNAPSHOT -> R.string.runtime_temporary_snapshot_missing
    TemporaryRefusal.INVALID_PRESET -> R.string.runtime_temporary_invalid
    TemporaryRefusal.PROFILE_LIMITS -> R.string.runtime_temporary_profile_limits
    TemporaryRefusal.TOO_CLOSE_TO_WAKE -> R.string.runtime_temporary_too_close
    TemporaryRefusal.TOO_MANY_STEPS -> R.string.runtime_temporary_too_many_steps
})

/** Typed core refusals are presented through Android resources at every entry point. */
internal fun Preview.refusalText(context: Context): String? = durationProblem?.text(context) ?: refusalReason
internal fun Preparation.Refused.refusalText(context: Context): String =
    temporaryRefusal?.text(context) ?: durationProblem?.text(context) ?: reason
internal fun Throwable.planMessage(context: Context): String? = (this as? NightDurationException)?.problem?.text(context) ?: message
