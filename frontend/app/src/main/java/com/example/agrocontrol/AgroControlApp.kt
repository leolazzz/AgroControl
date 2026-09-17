package com.example.agrocontrol

import android.app.Application

class AgroControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        setupSyncWorker()
    }

    private fun setupSyncWorker() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val syncRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.agrocontrol.worker.SyncWorker>(
            15,
            java.util.concurrent.TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "SyncWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
        AppPrefs.applyTheme(this)
    }
}
