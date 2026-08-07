package com.example.myapplication3.tracking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.notifications.manager.StockNotificationManager
import com.example.myapplication3.tts.HindiTtsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/**
 * Fires at 3:15 PM IST (C15a): for every OPEN intraday trade, a strong buzz + a
 * "sell now" alert, because at 3:20 the broker auto-squares-off intraday positions.
 * Reschedules itself for the next day after each fire.
 */
@AndroidEntryPoint
class SquareOffAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var tracker: TradeTrackerRepository
    @Inject lateinit var notifier: StockNotificationManager
    @Inject lateinit var tts: HindiTtsManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val open = runCatching { tracker.getAllOpenOnce() }.getOrElse { emptyList() }
                    // Real intraday only — practice trades are fake money, never alert on them.
                    .filter { it.orderType == OrderType.INTRADAY && !it.isPractice }
                open.forEach { notifier.alertSquareOff(it.symbol) }
                if (open.isNotEmpty()) {
                    // Plain words only (I2) — "square off" is broker jargon. Match the
                    // notification's wording: sell now, or at 3:20 the broker sells it.
                    tts.speakText("It is 3:15. Open Groww and sell your intraday trades now. At 3:20 the broker will sell them himself.")
                }
            } finally {
                SquareOffScheduler.scheduleNext(context)   // set tomorrow's alarm
                pending.finish()
            }
        }
    }
}

/** Schedules the daily 3:15 PM IST square-off alarm. */
object SquareOffScheduler {

    private const val REQUEST_CODE = 3151
    private const val WINDOW_MS = 10 * 60_000L   // inexact fallback: fire inside 3:05–3:15

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(Calendar.HOUR_OF_DAY, 15); set(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)

        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, SquareOffAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Android 12+: the user can revoke "Alarms & reminders". Check up front so the
        // degraded path still lands BEFORE the broker's 3:20 auto-square-off.
        val canExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } catch (e: SecurityException) {
                // Permission revoked between check and set — use the safe window.
                scheduleWindow(am, cal.timeInMillis, pi)
            }
        } else {
            scheduleWindow(am, cal.timeInMillis, pi)
        }
    }

    /**
     * Inexact fallback: a 3:05–3:15 delivery window. Even a late fire inside the
     * window still warns the user before the 3:20 broker square-off; a plain
     * inexact set() could be deferred past it by Doze/batching.
     */
    private fun scheduleWindow(am: AlarmManager, triggerAtMs: Long, pi: PendingIntent) {
        am.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMs - WINDOW_MS, WINDOW_MS, pi)
    }
}
