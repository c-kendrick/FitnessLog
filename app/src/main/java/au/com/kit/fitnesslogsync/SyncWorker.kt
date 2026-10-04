package au.com.kit.fitnesslogsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Store(applicationContext).use { it.get("enabled") == "true" }) return Result.success()
        // Seed future slots before I/O. A failed run cannot break the scheduling chain.
        Scheduling.ensureScheduled(applicationContext)
        val now = Instant.now()
        val alreadyFresh = Store(applicationContext).use {
            it.count() == 0L && it.get("last_error").isEmpty() &&
                SyncPolicy.recentlySuccessful(it.get("last_success"), now)
        }
        if (alreadyFresh) return Result.success()
        val outcome = SyncRepository(applicationContext).sync(foreground = false)
        // Bounded retries; future briefing-aligned slots keep their original times.
        return if (outcome.retry && runAttemptCount < 2) Result.retry() else Result.success()
    }
}

object Scheduling {
    private const val TAG = "fitness-briefing-sync-v1"

    @Synchronized fun ensureScheduled(context: Context) {
        Store(context).use { store ->
            if (store.get("enabled") != "true") return
            val manager = WorkManager.getInstance(context)
            if (store.get("schedule_version") != SyncPolicy.SCHEDULE_VERSION) {
                listOf("fitness-sync", "fitness-sync-now", "fitness-sync-retry").forEach { manager.cancelUniqueWork(it) }
                manager.cancelAllWorkByTag(TAG)
                store.put("schedule_version", SyncPolicy.SCHEDULE_VERSION)
            }
            val now = Instant.now()
            val slots = SyncPolicy.nextSlots(now)
            slots.forEach { slot ->
                val work = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(Duration.between(now, slot))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(10))
                    .addTag(TAG).build()
                manager.enqueueUniqueWork("fitness-slot-${slot.epochSecond}", ExistingWorkPolicy.KEEP, work)
            }
            store.put("next_sync_at", slots.first().toString())
        }
    }

    fun retrySoon(context: Context) {
        if (!Store(context).use { it.get("enabled") == "true" }) return
        val retry = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(10, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(10)).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("fitness-sync-retry-v12", ExistingWorkPolicy.KEEP, retry)
    }

    fun enable(context: Context) {
        Store(context).use { it.put("enabled", "true") }
        ensureScheduled(context)
    }

    fun disable(context: Context) {
        Store(context).use { it.put("enabled", "false"); it.put("next_sync_at", "") }
        val manager = WorkManager.getInstance(context)
        listOf("fitness-sync", "fitness-sync-now", "fitness-sync-retry", "fitness-sync-retry-v12")
            .forEach { manager.cancelUniqueWork(it) }
        manager.cancelAllWorkByTag(TAG)
    }

    fun rebuild(context: Context) {
        Store(context).use { it.put("schedule_version", "") }
        ensureScheduled(context)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) Scheduling.rebuild(context)
    }
}
