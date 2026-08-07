package com.example.myapplication3.notifications.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myapplication3.core.common.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sysManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            sysManager.createNotificationChannel(
                NotificationChannel(Constants.CHANNEL_ID_OPPORTUNITIES, "Trading Opportunities", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New intraday trade opportunities"
                }
            )
            sysManager.createNotificationChannel(
                NotificationChannel(Constants.CHANNEL_ID_ALERTS, "Trade Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Stop loss and target alerts"
                }
            )
            // Plain words on a user-visible surface (I2): the channel list in
            // Android Settings must never say "Regime". Same channel id — the
            // rename updates in place on existing installs.
            sysManager.createNotificationChannel(
                NotificationChannel(Constants.CHANNEL_ID_REGIME, "Market alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "General market condition alerts"
                }
            )
        }
    }

    fun notifyDailyLimitBreached(loss: Double, limit: Double) {
        sendNotification(
            "daily-limit-breached".hashCode(),
            "Daily Loss Limit Reached",
            "You have lost ₹${String.format("%.0f", loss)} today (limit ₹${String.format("%.0f", limit)}). No new trades recommended.",
            Constants.CHANNEL_ID_ALERTS
        )
    }

    fun notifyDailyLimitWarning(loss: Double, limit: Double) {
        val remaining = limit - loss
        sendNotification(
            "daily-limit-warning".hashCode(),
            "Daily Loss Warning",
            "₹${String.format("%.0f", loss)} lost today. Only ₹${String.format("%.0f", remaining)} remaining before limit.",
            Constants.CHANNEL_ID_ALERTS
        )
    }

    /**
     * Live stop-loss alert the user cannot miss (C15a): a strong buzz + a clear
     * "sell now" notification. Same id so repeats update the one notification while
     * the buzz keeps signalling until the user acts.
     */
    fun alertStopLossLive(symbol: String, price: Double, note: String = "") {
        vibrateStrong()
        sendNotification(
            "$symbol-sl".hashCode(),
            "🔴 SELL $symbol NOW",
            "Stop-loss hit at ₹${String.format("%.2f", price)}. Open Groww and sell. $note".trim(),
            Constants.CHANNEL_ID_ALERTS
        )
    }

    /** Live target-hit alert with a buzz — time to book profit. */
    fun alertTargetLive(symbol: String, price: Double) {
        vibrateStrong()
        sendNotification(
            "$symbol-target".hashCode(),
            "🎯 $symbol hit your target",
            "Price reached ₹${String.format("%.2f", price)}. Open Groww and sell to book profit.",
            Constants.CHANNEL_ID_ALERTS
        )
    }

    /**
     * 3:15 PM warning for any open intraday position (C15a). Plain words only
     * (I2): "square-off" is broker jargon a zero-knowledge user cannot act on.
     */
    fun alertSquareOff(symbol: String) {
        vibrateStrong()
        sendNotification(
            "$symbol-squareoff".hashCode(),
            "⏰ Sell $symbol now",
            "It's 3:15 PM. Open Groww and sell now — at 3:20 the broker will sell it himself.",
            Constants.CHANNEL_ID_ALERTS
        )
    }

    private fun vibrateStrong() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 400, 200, 400, 200, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) { /* vibration is best-effort */ }
    }

    /**
     * Tapping ANY of these alerts must open the app (U2.1/U4.3) — a "sell now"
     * buzz that leads nowhere strands a zero-knowledge user. This module cannot
     * see MainActivity (separate Gradle module), so the launcher intent is used.
     */
    private fun openAppIntent(): PendingIntent? = try {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    } catch (e: Exception) {
        null   // fail-safe: a notification without a tap action still alerts
    }

    private fun sendNotification(id: Int, title: String, text: String, channelId: String) {
        try {
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            openAppIntent()?.let { builder.setContentIntent(it) }
            notificationManager.notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }
}
