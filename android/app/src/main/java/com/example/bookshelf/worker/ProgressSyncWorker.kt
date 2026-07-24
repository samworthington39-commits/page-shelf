package com.example.bookshelf.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.bookshelf.PageShelfApplication

class ProgressSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PageShelfApplication).container
        if (container.credentials.bearerToken() == null) {
            runCatching { container.auth.autoLogin() }.getOrElse { return Result.retry() }
        }
        return runCatching { container.progress.syncPending() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
