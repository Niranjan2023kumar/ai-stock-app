package com.example.myapplication3

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.myapplication3.diag.CrashLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StockIntelligenceApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        // P0 #8 — install the local crash handler FIRST, before any Hilt work,
        // so an early-startup crash is still captured. It is just a handler set
        // (no I/O) and is fully runCatching-guarded internally.
        CrashLog.install(this)
        super.onCreate()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
