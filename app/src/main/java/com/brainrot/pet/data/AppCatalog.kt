package com.brainrot.pet.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.telecom.TelecomManager
import com.brainrot.pet.BrainLog
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(val packageName: String, val label: String)

/** App labels, icons and the system packages that must never count as screen time or be blocked. */
class AppCatalog(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val labels = ConcurrentHashMap<String, String>()

    /** Launchers and System UI: being on the home screen is not "using the phone" for our numbers. */
    val notScreenTime: Set<String> by lazy {
        homePackages() + setOf(context.packageName, "com.android.systemui")
    }

    /**
     * Never blocked: home screen, calls, settings (so permissions can be fixed) and this app (so a
     * limit can always be changed). Blocking these would trap the user or break emergencies.
     */
    val neverBlock: Set<String> by lazy {
        notScreenTime + setOfNotNull(
            defaultDialer(),
            "com.android.settings",
            "com.android.emergency",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )
    }

    fun label(packageName: String): String = labels.getOrPut(packageName) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun icon(packageName: String): Drawable? = try {
        pm.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Apps with a launcher icon, sorted by label. Call off the main thread. */
    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryActivities(intent)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { InstalledApp(it, label(it)) }
            .sortedBy { it.label.lowercase() }
    }

    private fun homePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return queryActivities(intent).map { it.activityInfo.packageName }.toSet()
    }

    private fun defaultDialer(): String? = try {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    } catch (e: SecurityException) {
        null
    }

    private fun queryActivities(intent: Intent) = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    } catch (e: RuntimeException) {
        BrainLog.w("package_query_failed", e)
        emptyList()
    }
}
