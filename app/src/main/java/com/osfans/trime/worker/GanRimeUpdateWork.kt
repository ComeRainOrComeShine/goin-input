/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.update.GanRimeUpdateManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

class GanRimeUpdateWork(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val manifestUrl = prefs.ganRimeUpdateManifestUrl.getValue()
        if (!prefs.ganRimeAutoUpdate.getValue() || manifestUrl.isBlank()) {
            return Result.success()
        }
        return try {
            val result = GanRimeUpdateManager.updateFromManifest(manifestUrl)
            if (!result.skipped) {
                val rime = RimeDaemon.createSession(javaClass.name)
                try {
                    rime.runOnReady { deploy() }
                } finally {
                    RimeDaemon.destroySession(javaClass.name)
                }
            }
            prefs.ganRimeLastUpdateStatus.setValue(true)
            prefs.ganRimeLastUpdateTime.setValue(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Gan Rime online update failed.")
            prefs.ganRimeLastUpdateStatus.setValue(false)
            prefs.ganRimeLastUpdateTime.setValue(System.currentTimeMillis())
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_GAN_RIME_UPDATE_KEY = "periodic_gan_rime_update"

        private val prefs = AppPrefs.defaultInstance().profile

        fun start(context: Context) {
            start(
                context,
                prefs.ganRimeAutoUpdate.getValue(),
                prefs.ganRimeUpdateManifestUrl.getValue(),
            )
        }

        fun start(
            context: Context,
            enabled: Boolean,
            manifestUrl: String,
        ) {
            val instance = WorkManager.getInstance(context.applicationContext)
            if (!enabled || manifestUrl.isBlank()) {
                instance.cancelUniqueWork(PERIODIC_GAN_RIME_UPDATE_KEY)
                Timber.i("GanRimeUpdateWork canceled!")
                return
            }
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<GanRimeUpdateWork>(
                    1,
                    TimeUnit.DAYS,
                    1,
                    TimeUnit.HOURS,
                ).setConstraints(constraints)
                    .build()
            instance.enqueueUniquePeriodicWork(
                PERIODIC_GAN_RIME_UPDATE_KEY,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest,
            )
        }
    }
}
