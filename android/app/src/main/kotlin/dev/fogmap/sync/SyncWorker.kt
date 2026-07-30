package dev.fogmap.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.fogmap.FogmapApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FogmapApp
        // Не авторизован — это не ошибка, просто синхронизировать некуда.
        if (!app.syncRepository.isAuthenticated) return Result.success()

        return try {
            val synced = app.syncRepository.sync()
            // Маска живёт на main, туда и вливаем.
            withContext(Dispatchers.Main) { app.fogStore.applyServerTiles(synced.serverTiles) }
            Result.success()
        } catch (e: Exception) {
            // Сеть, сервер, токен — для планировщика разницы нет, все случаи лечатся повтором.
            Result.retry()
        }
    }
}

object Sync {

    /** Фоновая синхронизация: только по Wi-Fi, чтобы не тратить мобильный трафик на блобы. */
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
                )
                .build(),
        )
    }

    /** Синк по кнопке: раз попросили руками — идём в любую сеть, а не ждём Wi-Fi. */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }

    private const val PERIODIC_WORK = "fog-sync-periodic"
    private const val MANUAL_WORK = "fog-sync-manual"
}
