/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.diagnostics.OptimiseProgress
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Carries an optimise run ([app.opentv.diagnostics.StreamOptimizer]) so that it outlives the
 * screen that started it.
 *
 * Two flavours share this class. "Run now" is a one-off started from the settings screen, in the
 * foreground, and runs to the end. The nightly one is periodic work due at about three in the
 * morning; the system may start it late or cut it short, which is fine — each run begins with
 * whatever has waited longest, so the work gets done across runs rather than in one.
 */
class OptimiseWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val graph = ServiceLocator.get(applicationContext)
        if (!manual && !graph.settings.optimiseNightly.value) return Result.success()
        // Refused when started from the background on newer Android; the run then carries on as
        // ordinary background work for as long as the system allows.
        runCatching { setForeground(foregroundInfo(OptimiseProgress())) }
        val deadline = if (manual) Long.MAX_VALUE else System.currentTimeMillis() + NIGHTLY_BUDGET_MILLIS
        coroutineScope {
            val notifier = launch {
                var shown = -1
                graph.optimizer.progress.collect { p ->
                    val step = if (p.total > 0) p.done * 100 / p.total else 0
                    if (step != shown) {
                        shown = step
                        runCatching { setForeground(foregroundInfo(p)) }
                    }
                }
            }
            try {
                graph.optimizer.run(manual, deadline)
            } finally {
                notifier.cancel()
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(OptimiseProgress())

    private fun foregroundInfo(progress: OptimiseProgress): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.optimise_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = ctx.getString(R.string.optimise_channel_desc) },
                )
            }
        }
        val text = when {
            progress.phase == OptimiseProgress.Phase.PAUSED -> ctx.getString(R.string.optimise_paused_short)
            progress.total > 0 -> ctx.getString(R.string.optimise_progress_short, progress.done, progress.total)
            else -> ctx.getString(R.string.optimise_preparing)
        }
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.optimise_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_opentv_logo)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(progress.total.coerceAtLeast(1), progress.done, progress.total <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val KEY_MANUAL = "manual"
        private const val CHANNEL_ID = "optimise"
        private const val NOTIF_ID = 4311
        private const val WORK_NOW = "opentv-optimise-now"
        private const val WORK_NIGHTLY = "opentv-optimise-nightly"
        private const val NIGHTLY_HOUR = 3
        /** Three in the morning to seven: out of the way before anybody is up. */
        private const val NIGHTLY_BUDGET_MILLIS = 4 * 3_600_000L

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<OptimiseWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setConstraints(connected)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** Stops whichever run is going. The nightly schedule itself is left in place. */
        fun stop(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(WORK_NOW)
            val graph = ServiceLocator.get(context)
            if (graph.optimizer.progress.value.active && graph.settings.optimiseNightly.value) {
                // Cancelling periodic work removes the schedule with it, so put it straight back.
                manager.cancelUniqueWork(WORK_NIGHTLY)
                scheduleNightly(context, replace = true)
            }
        }

        /**
         * Brings the nightly schedule in line with the setting. Called on every launch as well as
         * when the setting changes: a force-stop or an app update can drop scheduled work.
         */
        fun syncNightly(context: Context, replace: Boolean = false) {
            if (ServiceLocator.get(context).settings.optimiseNightly.value) {
                scheduleNightly(context, replace)
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NIGHTLY)
            }
        }

        private fun scheduleNightly(context: Context, replace: Boolean) {
            val request = PeriodicWorkRequestBuilder<OptimiseWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(millisUntilNight(System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                .setConstraints(connected)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NIGHTLY,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Milliseconds from [now] to the next three o'clock in the morning, local time. */
        fun millisUntilNight(now: Long): Long {
            val next = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, NIGHTLY_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }
            return next.timeInMillis - now
        }
    }
}
