package io.github.willzy12h.nocturne.platform

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import io.github.willzy12h.nocturne.R

/** LIB-01 containment: only the Android 14+ platform module is used by this build. */
internal object HealthConnectAccess {
    val platformSupported: Boolean get() = Build.VERSION.SDK_INT >= 34

    fun available(context: Context): Boolean = platformSupported &&
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun client(context: Context): HealthConnectClient {
        if (!platformSupported) throw UnsupportedOperationException(context.getString(R.string.health_platform_required))
        return HealthConnectClient.getOrCreate(context)
    }

    fun unavailableMessage(context: Context): String = context.getString(
        if (platformSupported) R.string.feature_health_absent else R.string.health_platform_required)
}
