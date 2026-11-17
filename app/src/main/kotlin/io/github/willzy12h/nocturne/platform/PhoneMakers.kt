package io.github.willzy12h.nocturne.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import io.github.willzy12h.nocturne.R

/**
 * Phone makers whose own battery or launch controls can stop Nocturne overnight, on top of Android's battery setting.
 * Each has written steps and the maker's settings screens to try, most specific first. Those screens are not a public
 * API and get moved between versions, so [open] falls back to Android's page for Nocturne when none of them opens.
 * Nocturne can't read these settings, so the guide never says whether they are already right.
 */
enum class PhoneMaker(@StringRes val title: Int, @StringRes val help: Int, val screens: List<ComponentName>) {
    SAMSUNG(R.string.setup_samsung_title, R.string.setup_samsung_help, listOf(
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )),
    HONOR(R.string.setup_maker_honor_title, R.string.setup_maker_honor_help, listOf(
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    )),
    HUAWEI(R.string.setup_maker_huawei_title, R.string.setup_maker_huawei_help, listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    )),
    XIAOMI(R.string.setup_maker_xiaomi_title, R.string.setup_maker_xiaomi_help, listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )),
    OPPO(R.string.setup_maker_oppo_title, R.string.setup_maker_oppo_help, listOf(
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    )),
    VIVO(R.string.setup_maker_vivo_title, R.string.setup_maker_vivo_help, listOf(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
    ));

    companion object {
        /** The maker for this phone, or null for phones (such as Pixels) that need only Android's own settings. */
        fun of(manufacturer: String? = Build.MANUFACTURER, brand: String? = Build.BRAND): PhoneMaker? {
            val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
            fun any(vararg candidates: String) = names.any { it in candidates }
            return when {
                any("samsung") -> SAMSUNG
                // Honor phones made before the 2020 split still report Huawei as the manufacturer.
                any("honor", "hihonor") -> HONOR
                any("huawei") -> HUAWEI
                any("xiaomi", "redmi", "poco") -> XIAOMI
                any("oppo", "realme", "oneplus") -> OPPO
                any("vivo", "iqoo") -> VIVO
                else -> null
            }
        }

        /** Android's own page for Nocturne, which every phone has. */
        fun appDetails(context: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Opens the first of [maker]'s screens that this phone has, else Nocturne's app page. Returns the screen opened,
         * or null for the app page. A screen that isn't there, or that the maker keeps private, is skipped.
         */
        fun open(context: Context, maker: PhoneMaker): ComponentName? {
            for (screen in maker.screens) {
                val intent = Intent().setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("package_name", context.packageName).putExtra("packageName", context.packageName)
                try {
                    context.startActivity(intent)
                    return screen
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
            // Every phone should have the app page; a phone missing even that gets Android's settings home.
            try {
                context.startActivity(appDetails(context))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return null
        }
    }
}
