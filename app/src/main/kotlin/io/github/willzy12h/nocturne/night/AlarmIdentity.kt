package io.github.willzy12h.nocturne.night

import android.content.Context

/** Display only. This never changes the raw creator passed to AlarmPolicy. */
internal fun identifiedAlarmCreator(raw: String?, uid: Int?, packages: List<String>, sharedUid: Boolean): String? {
    if (raw != null) return raw
    if (uid == null || uid < 10_000 || sharedUid) return null
    return packages.distinct().singleOrNull()?.takeIf { it.isNotBlank() && it != "android" }
}

@Suppress("DEPRECATION")
internal fun identifyAlarmCreator(context: Context, raw: String?, uid: Int?): String? {
    if (raw != null || uid == null || uid < 10_000) return raw
    // Package visibility may hide results. Absence, errors and shared UIDs remain unidentified.
    return runCatching {
        val packages = context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        val candidate = packages.distinct().singleOrNull() ?: return@runCatching null
        val info = context.packageManager.getPackageInfo(candidate, 0)
        val shared = info.sharedUserId != null || info.applicationInfo?.uid != uid
        identifiedAlarmCreator(raw, uid, packages, shared)
    }.getOrNull()
}

internal val samsungClockPackages = setOf(
    "com.sec.android.app.clockpackage",
    // Explicit compatibility aliases, not manufacturer-based inference or an acceptance allowlist.
    "com.samsung.android.app.clockpackage",
    "com.samsung.android.clockpackage",
)
