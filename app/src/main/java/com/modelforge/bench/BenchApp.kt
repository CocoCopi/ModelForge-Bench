package com.modelforge.bench

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BenchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val running = NotificationChannel(
            CHANNEL_RUNNING,
            "Evaluation progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the stage and progress of a running benchmark."
            setShowBadge(false)
        }
        val results = NotificationChannel(
            CHANNEL_RESULTS,
            "Evaluation results",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Tells you when a benchmark has finished."
        }
        manager.createNotificationChannel(running)
        manager.createNotificationChannel(results)
    }

    companion object {
        const val CHANNEL_RUNNING = "bench_running"
        const val CHANNEL_RESULTS = "bench_results"
    }
}
