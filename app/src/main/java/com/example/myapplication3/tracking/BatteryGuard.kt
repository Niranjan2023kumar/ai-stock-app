package com.example.myapplication3.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-kill protection check (B0.2d completion / U4.5).
 *
 * OEM battery "optimization" (Realme/ColorOS, Xiaomi, Vivo...) silently kills the
 * background stop-loss watcher — the app promises "I will alert you" and then the
 * phone breaks that promise without a word. This guard makes the risk VISIBLE:
 *
 *  - [isProtectionAtRisk] — true while the app is NOT on the battery-optimization
 *    allowlist, i.e. Android may freeze/kill [TradeWatchService] and its alarms.
 *    Re-check it on every resume (like the notifications-off banner) so the
 *    warning disappears the moment the user grants the exemption and returns.
 *  - [requestIntent] — the intent that fixes it: the direct system "Allow this
 *    app to always run in background?" dialog when available, else the system
 *    battery-optimization list screen as a fallback.
 *
 * B8 fail-safe: every call is wrapped — a broken OEM Settings app or missing
 * PowerManager must never crash the screen; on failure we err on the QUIET side
 * (no false alarm on a phone we cannot read).
 */
object BatteryGuard {

    /**
     * True when Android's battery optimization may stop background alerts —
     * i.e. the user has NOT yet granted the "ignore battery optimizations"
     * exemption for this app.
     */
    fun isProtectionAtRisk(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@runCatching false
        !pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * The intent that lets the user fix it. Prefers the one-tap system dialog
     * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with this package's uri) —
     * but ONLY when this app actually HOLDS the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * permission (a normal permission: granted at install iff the manifest
     * declares it). Without it, AOSP's dialog activity silently finishes —
     * the tap would LOOK dead (U2.1) with no exception to catch. In that case,
     * or when no activity resolves at all (OEM removed it), fall back to the
     * system battery-optimization LIST screen where the user picks this app.
     */
    fun requestIntent(context: Context): Intent {
        val holdsPermission = runCatching {
            context.packageManager.checkPermission(
                android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                context.packageName
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (holdsPermission) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            val canDirect = runCatching {
                direct.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
            if (canDirect) return direct
        }
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
