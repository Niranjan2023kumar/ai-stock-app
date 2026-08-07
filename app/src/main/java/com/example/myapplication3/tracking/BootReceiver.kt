package com.example.myapplication3.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores protection after a phone restart (E1: scanning resumes after restart).
 * A reboot wipes the one-shot 3:15 square-off alarm and kills TradeWatchService,
 * so without this a user who does not reopen the app holds an unwatched position
 * with no square-off warning — the exact disaster C15a exists to prevent.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var tracker: TradeTrackerRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT) return

        // Always re-arm the 3:15 square-off alarm — cheap, safe, needs no DB read.
        runCatching { SquareOffScheduler.scheduleNext(context) }
            .onFailure { Log.w(TAG, "could not reschedule square-off alarm: ${it.message}") }

        // Restart the stop-loss watcher only when open REAL trades exist (practice
        // trades are fake money — never worth a foreground service).
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hasOpenReal = runCatching { tracker.getOpenOnce(practice = false) }
                    .getOrElse { emptyList() }
                    .isNotEmpty()
                if (hasOpenReal) {
                    // Android 15 restricts dataSync FGS starts from BOOT_COMPLETED —
                    // runCatching keeps a denial from crashing the boot broadcast.
                    runCatching {
                        ContextCompat.startForegroundService(
                            context, Intent(context, TradeWatchService::class.java)
                        )
                    }.onFailure { Log.w(TAG, "could not restart trade watch after boot: ${it.message}") }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        // Some OEMs (HTC, older Xiaomi) fire this instead of BOOT_COMPLETED.
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
