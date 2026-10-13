/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import android.content.Context
import androidx.work.*
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class CloudSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val cloud = ServiceLocator.get(applicationContext).cloudSync
        if (!cloud.preferences.enabled.value) return Result.success()
        return try { cloud.sync(); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (e: CloudApiException) {
            cloud.preferences.status.value = e.message.orEmpty()
            if (e.status in listOf(400, 401, 403, 409)) Result.failure() else Result.retry()
        } catch (_: Exception) {
            cloud.preferences.status.value = "Could not sync. Check your connection and encryption key; your local data is safe."
            Result.retry()
        }
    }
    companion object {
        private const val NOW = "opentv-supabase-now"
        private const val PERIODIC = "opentv-supabase-periodic"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NOW); WorkManager.getInstance(context).cancelUniqueWork(PERIODIC) }
    }
}
