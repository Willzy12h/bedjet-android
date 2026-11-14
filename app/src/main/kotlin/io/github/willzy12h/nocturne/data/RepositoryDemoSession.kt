package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.R
import kotlinx.serialization.json.*
import java.io.IOException
import io.github.willzy12h.nocturne.data.Repository.SessionState

// Part of [Repository], split out of Repository.kt: the disposable demo session. The same flows serve it, and the
// real session's values are captured on entry and published again, verbatim, on exit.

internal fun Repository.captureSession() = SessionState(_settings.value, _profiles.value, _history.value, _slotBackups.value,
    _activeNight.value, _runtime.value, _automation.value, _notices.value, _snapshotList.value, _unreadable.value,
    originalsIntegrityBlocked, originalsLossAcceptedAt, _restoreUnfinished.value)

internal fun Repository.publishSession(state: SessionState) {
    _profiles.value = state.profiles
    _history.value = state.history
    _slotBackups.value = state.originals
    _activeNight.value = state.activeNight
    _runtime.value = state.runtime
    _automation.value = state.automation
    _notices.value = state.notices
    _snapshotList.value = state.snapshots
    _unreadable.value = state.unreadable
    originalsIntegrityBlocked = state.originalsBlocked
    originalsLossAcceptedAt = state.originalsLossAcceptedAt
    _restoreUnfinished.value = state.restoreUnfinished
    // Switch the transport only after every flow belongs to the incoming session.
    _settings.value = state.settings.withoutUnidentifiedAlarms()
    _sessionRevision.value += 1
    _settingsPublication.value += 1
}

internal fun Repository.requireRealSession() {
    if (isDemoSession) throw IOException(context.getString(R.string.gpt12_demo_backup_unavailable))
}
