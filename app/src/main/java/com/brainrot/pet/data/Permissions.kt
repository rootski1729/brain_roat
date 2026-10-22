package com.brainrot.pet.data

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.annotation.StringRes
import com.brainrot.pet.R
import com.brainrot.pet.service.ReelAccessibilityService

data class PermissionState(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val accessibility: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
) {
    /** The pet can live on screen and measure time. Reel counting additionally needs [accessibility]. */
    val coreReady: Boolean get() = usageAccess && overlay
}

object Permissions {

    fun state(context: Context) = PermissionState(
        usageAccess = hasUsageAccess(context),
        overlay = Settings.canDrawOverlays(context),
        accessibility = isAccessibilityEnabled(context),
        notifications = hasNotificationPermission(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, ReelAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /**
     * Opens Brain Rot's own switch where the system supports it (Android 11+), otherwise the
     * Accessibility list with our entry highlighted (supported by Pixel/AOSP and many OEM builds).
     * Callers fall back to [accessibilityListIntent] if this one cannot be started.
     */
    fun accessibilityIntent(context: Context): Intent {
        val component = ComponentName(context, ReelAccessibilityService::class.java).flattenToString()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        } else {
            accessibilityListIntent(context)
        }
    }

    fun accessibilityListIntent(context: Context): Intent {
        val component = ComponentName(context, ReelAccessibilityService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) })
    }

    /** Where this brand hides third-party accessibility services, as a string resource. */
    @StringRes
    fun accessibilitySteps(manufacturer: String = Build.MANUFACTURER): Int = when (manufacturer.lowercase()) {
        "samsung" -> R.string.a11y_steps_samsung
        "oppo", "realme", "oneplus" -> R.string.a11y_steps_coloros
        "xiaomi", "redmi", "poco" -> R.string.a11y_steps_xiaomi
        "vivo", "iqoo" -> R.string.a11y_steps_vivo
        else -> R.string.a11y_steps_generic
    }

    // Settings' own (unofficial but long-stable) extras for highlighting one preference.
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

    /** App info: where "Allow restricted settings" lives for sideloaded installs (Android 13+). */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** The settings list, not the direct-request dialog: that one needs a Play-restricted permission. */
    fun batteryIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
