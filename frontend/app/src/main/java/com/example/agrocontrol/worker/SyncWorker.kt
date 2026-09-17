package com.example.agrocontrol.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.agrocontrol.data.local.AppDatabase
import com.example.agrocontrol.data.repository.SyncRepository
import com.example.agrocontrol.network.RetrofitClient

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val apiService = RetrofitClient.api(applicationContext)

            val repository = SyncRepository(
                apiService,
                database.commandQueueDao()
            )

            if (repository.syncAll()) Result.success() else Result.retry()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
