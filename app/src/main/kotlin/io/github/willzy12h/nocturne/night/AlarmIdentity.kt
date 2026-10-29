package io.github.willzy12h.nocturne.night

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Display only. This never changes the raw creator passed to AlarmPolicy. */
internal fun identifiedAlarmCreator(raw: String?, uid: Int?, packages: List<String>, sharedUid: Boolean): String? {
    if (raw != null) return raw
    if (uid == null || uid < 10_000 || sharedUid) return null
    return packages.distinct().singleOrNull()?.takeIf { it.isNotBlank() && it != "android" }
}

internal fun identifyAlarmCreator(context: Context, raw: String?, uid: Int?): String? {
    if (raw != null || uid == null || uid < 10_000) return raw
    // Package visibility may hide results. Absence, errors and shared UIDs remain unidentified.
    return runCatching {
        val packages = context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        val candidate = packages.distinct().singleOrNull() ?: return@runCatching null
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(candidate, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") // The flags-object overload starts at API 33.
            context.packageManager.getPackageInfo(candidate, 0)
        }
        // Android retains legacy shared UIDs; rejecting them still requires this field (no replacement).
        @Suppress("DEPRECATION")
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
