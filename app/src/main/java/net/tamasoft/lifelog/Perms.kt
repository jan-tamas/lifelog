package net.tamasoft.lifelog

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** What each module needs from Android, and whether it has it. */
object Perms {
    fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun usageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessibility(c: Context): Boolean {
        val mine = ComponentName(c, WebWatcher::class.java).flattenToString()
        val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return on.split(':').any { it.equals(mine, ignoreCase = true) }
    }

    fun notificationAccess(c: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun notifications(c: Context) =
        Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)

    fun batteryUnrestricted(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** Android pauses an app that is rarely opened and takes its permissions; this one is rarely opened. */
    fun neverHibernated(c: Context) = Build.VERSION.SDK_INT < 30 || c.packageManager.isAutoRevokeWhitelisted

    fun fineLocation(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun backgroundLocation(c: Context) = granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    val photoRead: String get() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun photos(c: Context) = granted(c, photoRead) && granted(c, Manifest.permission.ACCESS_MEDIA_LOCATION)
}
