package de.pokemoncarddetector.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.pokemoncarddetector.PokeApp
import java.util.concurrent.TimeUnit

/** Aktualisiert einmal am Tag die Cardmarket-Preise der Sammlung + Wunschliste und speichert den Tageswert. */
class PriceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PokeApp
        val result = runCatching { app.container.priceRepository.refreshTracked() }
        return if (result.isSuccess && result.getOrNull()?.failed == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "preise-taeglich"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<PriceWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
