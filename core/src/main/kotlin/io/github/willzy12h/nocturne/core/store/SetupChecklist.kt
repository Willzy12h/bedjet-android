package io.github.willzy12h.nocturne.core.store

/** System state is sampled on resume; saved flags can never hide a missing required item. */
enum class SetupItem(val key: String, val required: Boolean = true) {
    PAIR("pair"), BLUETOOTH("bluetooth"), NOTIFICATIONS("notifications"), EXACT_ALARMS("exact"),
    BATTERY("battery"), HEALTH("health", false), WIDGET("widget", false),
}

data class SetupSnapshot(val complete: Set<SetupItem>) {
    val requiredComplete: Boolean get() = SetupItem.entries.filter { it.required }.all { it in complete }

    fun visible(settings: Settings, reopened: Boolean = false): List<SetupItem> {
        if (!reopened && (settings.demoMode || requiredComplete)) return emptyList()
        return SetupItem.entries.filter { item -> item !in complete &&
            (item.required || ((reopened || !settings.setupChecklistCompleted) &&
                (reopened || item.key !in settings.setupDismissedOptional))) }
    }
}
