package io.github.willzy12h.nocturne.platform

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * For the diagnostics export: what the phone allows the app to do in the background, and why Android last closed it.
 * Phone makers such as Honor close apps and remove the battery exemption on their own; this shows whether that
 * happened. Read-only: nothing here changes a setting.
 */
internal object PhoneDiagnostics {
    private const val EXITS = 5

    fun snapshot(context: Context): JsonObject = buildJsonObject {
        put("manufacturer", JsonPrimitive(Build.MANUFACTURER))
        put("brand", JsonPrimitive(Build.BRAND))
        put("makerGuide", JsonPrimitive(PhoneMaker.of()?.name))
        put("model", JsonPrimitive(Build.MODEL))
        put("androidRelease", JsonPrimitive(Build.VERSION.RELEASE))
        put("sdk", JsonPrimitive(Build.VERSION.SDK_INT))
        // The maker's build name, which is where MagicOS, HyperOS, One UI and others give their version.
        put("build", JsonPrimitive(Build.DISPLAY))
        val power = context.getSystemService(PowerManager::class.java)
        put("ignoringBatteryOptimizations", power?.isIgnoringBatteryOptimizations(context.packageName).json())
        put("powerSaveMode", power?.isPowerSaveMode.json())
        put("deviceIdle", power?.isDeviceIdleMode.json())
        val activity = context.getSystemService(ActivityManager::class.java)
        put("backgroundRestricted", activity?.isBackgroundRestricted.json())
        put("canScheduleExactAlarms", context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms().json())
        put("standbyBucket", context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            ?.let { JsonPrimitive(bucketName(it)) } ?: JsonNull)
        put("recentExits", JsonArray(recentExits(activity, context.packageName)))
    }

    private fun recentExits(activity: ActivityManager?, packageName: String): List<JsonObject> =
        runCatching { activity?.getHistoricalProcessExitReasons(packageName, 0, EXITS).orEmpty() }.getOrDefault(emptyList())
            .map { exit ->
                buildJsonObject {
                    put("at", JsonPrimitive(Instant.ofEpochMilli(exit.timestamp).toString()))
                    put("reason", JsonPrimitive(reasonName(exit.reason)))
                    put("importance", JsonPrimitive(exit.importance))
                    put("description", exit.description?.let(::JsonPrimitive) ?: JsonNull)
                }
            }

    private fun Boolean?.json() = this?.let(::JsonPrimitive) ?: JsonNull

    internal fun bucketName(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        else -> "OTHER_$bucket"
    }

    /** USER_REQUESTED is a force-stop, whether from App info or from a phone maker's battery manager. */
    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN_$reason"
    }
}
