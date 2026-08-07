package com.example.myapplication3.mutualfunds

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.notifications.manager.StockNotificationManager
import com.example.myapplication3.ui.component.formatIndianRupees
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Monthly SIP reminder (rule C20a) — the tab promises "the app reminds you every
 * month", and this worker is what keeps that promise. Runs once a day, entirely
 * offline: it reads the ONE tracked SIP from [SipStore] and, if the SIP day has
 * arrived (or passed) this month with no payment confirmed yet, posts a single
 * notification asking the user to open the app and confirm.
 *
 * One fixed notification id — an unconfirmed SIP quietly refreshes the same
 * notification each day rather than piling up a new one per day.
 */
@HiltWorker
class SipReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sipStore: SipStore,
    // Injected purely so its init{} runs: the notifications module owns channel
    // creation, and constructing the singleton guarantees CHANNEL_ID_ALERTS
    // exists even when WorkManager wakes the app process with no UI on screen.
    @Suppress("unused") private val channelOwner: StockNotificationManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val record = sipStore.load() ?: return Result.success()
        val today = Calendar.getInstance()
        if (record.isDueOn(today)) postReminder(record, today)
        return Result.success()
    }

    private fun postReminder(record: SipRecord, today: Calendar) {
        val onSipDay = today.get(Calendar.DAY_OF_MONTH) == record.sipDay
        val amount = formatIndianRupees(record.monthlyAmount.toLong())
        val title = if (onSipDay) "Today is your monthly investment day" else "Did your monthly investment go?"
        val text = if (onSipDay) {
            "Did your ₹$amount get taken this month? Open the app to confirm."
        } else {
            "Your monthly investment day (day ${record.sipDay}) has passed. " +
                    "Did your ₹$amount get taken? Open the app to confirm."
        }

        // Tapping the reminder opens the app so "confirm" is one tap away.
        val contentIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.let {
                PendingIntent.getActivity(
                    applicationContext, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

        try {
            val notification = NotificationCompat.Builder(applicationContext, Constants.CHANNEL_ID_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .apply { contentIntent?.let { pi -> setContentIntent(pi) } }
                .build()
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — same silent handling as
            // StockNotificationManager; the in-app "SIP due" banner still shows.
        }
    }

    companion object {
        const val WORK_NAME = "sip_reminder"
        private val NOTIFICATION_ID = "sip-reminder".hashCode()

        /** Daily local check at ~9 AM — no network needed, so no constraints. */
        fun buildPeriodicRequest(): PeriodicWorkRequest {
            val now = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 9)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_MONTH, 1)
            val initialDelayMs = target.timeInMillis - now.timeInMillis
            return PeriodicWorkRequestBuilder<SipReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
