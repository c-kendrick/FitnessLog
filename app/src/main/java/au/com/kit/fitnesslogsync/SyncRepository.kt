package au.com.kit.fitnesslogsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.request.ChangesTokenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SyncOutcome(val retry: Boolean, val message: String)

class SyncRepository(private val context: Context) {
    companion object { private val mutex = Mutex() }
    suspend fun sync(foreground: Boolean, backfill: LocalDate? = null): SyncOutcome {
        if (!mutex.tryLock()) return SyncOutcome(false, "A sync is already running.")
        val store = Store(context)
        val alerts = Alerts(context, store)
        val webhook = Webhook(Configuration(context))
        val problems = mutableListOf<Pair<String, String>>()
        var transient = false
        store.put("last_attempt", Instant.now().toString())
        try {
            store.migrateDirectSteps()
            flush(store, webhook)
            val today = LocalDate.now(SYDNEY)
            syncSteps(today, backfill, store, webhook)
            val stepIssues = JSONArray(store.get("step_issues", "[]"))
            if (stepIssues.length() > 0) problems += "steps_attention" to
                (0 until stepIssues.length()).joinToString(" | ") { stepIssues.getString(it) }.take(1200)
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE)
                throw SyncProblem("health_unavailable", "Health Connect is unavailable. Tap to check the phone's Health Connect installation.")
            val reader = HealthReader(HealthConnectClient.getOrCreate(context))
            val granted = reader.client.permissionController.getGrantedPermissions()
            val hasDataAccess = (DAILY + MEASUREMENTS).any { it.permission in granted }
            if (!hasDataAccess) throw SyncProblem("permissions", "Health access is missing. Open Fitness Log Sync and tap Grant health access.")
            if (!foreground && BACKGROUND !in granted) throw SyncProblem("background", "Background health access is missing. Open the app and restore it.")
            val history = HISTORY in granted
            val upgradedHistory = history && store.get("history_access_seen") != "true"
            // Without history permission, stay safely inside the default permission window.
            val earliest = if (history) LocalDate.parse("2026-07-09") else today.minusDays(29)
            if (upgradedHistory) MEASUREMENTS.forEach { store.put("token_${it.key}", "") }
            if (upgradedHistory || backfill != null) DAILY.filter { it.key != "steps" }.forEach { category ->
                val requested = backfill ?: earliest
                val previous = store.get("backfill_${category.key}").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                store.put("backfill_${category.key}", minOf(previous ?: requested, requested).toString())
            }
            store.put("history_access_seen", history.toString())
            val status = JSONObject()
            status.put("app_version", APP_VERSION).put("steps_query", "samsung_sdk_total").put("steps", store.get("status_steps"))
                .put("schedule_version", SyncPolicy.SCHEDULE_VERSION)
                .put("next_sync_at", if (store.get("enabled") == "true") SyncPolicy.nextSlots(Instant.now()).first().toString() else "")
            val gaps = DAILY.filter { it.key != "steps" }.mapNotNull { category ->
                store.get("cursor_${category.key}").takeIf { it.isNotEmpty() }?.let { previous ->
                    if (LocalDate.parse(previous).plusDays(1) < earliest) "${category.label}: ${LocalDate.parse(previous).plusDays(1)} to ${earliest.minusDays(1)} needs history access" else null
                }
            }
            if (gaps.isNotEmpty()) {
                store.put("history_gap", gaps.joinToString("; "))
                problems += "history_gap" to "Some missed dates are outside the readable history. Grant history access and import earlier dates."
            }
            status.put("history_gap", store.get("history_gap"))
            status.put("history_access", history).put("background_access", BACKGROUND in granted)
            status.put("notifications_enabled", alerts.available()).put("automatic_enabled", store.get("enabled") == "true")
            status.put("history_start", earliest.toString())
            for (category in DAILY.filter { it.key != "steps" } + MEASUREMENTS) {
                if (category.permission !in granted) {
                    val message = "${category.label} permission is missing. Other categories can still sync."
                    problems += "permission_${category.key}" to message
                    status.put(category.key, "permission_required")
                    store.put("status_${category.key}", "permission_required")
                    continue
                }
                try {
                    if (category in DAILY) {
                        val pendingBackfill = store.get("backfill_${category.key}").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                        syncDaily(reader, category, today, earliest, pendingBackfill, store, webhook)
                    }
                    else syncMeasurements(reader, category, earliest, store, webhook)
                    status.put(category.key, "ok")
                    store.put("status_${category.key}", "ok")
                    val checked = Instant.now().toString()
                    status.put("checked_at_${category.key}", checked)
                    store.put("checked_at_${category.key}", checked)

                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    val code = (error as? SyncProblem)?.code ?: "read_${category.key}"
                    problems += code to (error.message ?: "${category.label} read failed.")
                    status.put(category.key, "error"); store.put("status_${category.key}", "error")
                    // Network/server errors keep the prepared payload and its fixed dates for the next run.
                    if (error is IOException) { transient = error !is SyncProblem || error.retryable; break }
                }
            }
            if (history && DAILY.all { status.optString(it.key) == "ok" && store.get("backfill_${it.key}").isEmpty() }) {
                store.put("history_gap", ""); status.put("history_gap", "")
                problems.removeAll { it.first == "history_gap" }
            }
            if (BACKGROUND !in granted) problems += "background" to "Background health access is missing. Open the app and grant it for automatic syncing."
            if (!alerts.available()) problems += "notifications" to "Issue notifications are disabled. Tap Allow issue notifications in the app."
            if (backfill != null && backfill < earliest) {
                problems += "history" to "Older history needs Health Connect history access. Imported the available dates from $earliest."
            }
            val report = JSONObject().put("action", "status").put("request_id", UUID.randomUUID().toString())
                .put("status", status).put("pending_uploads", store.count())
                .put("last_attempt_at", store.get("last_attempt"))
                .put("last_error", problems.joinToString(" | ") { it.second }.take(1200))
            // Status is telemetry, not an acknowledged data checkpoint. A failed report never loses an outbox item.
            try { webhook.send(report) } catch (e: CancellationException) { throw e
            } catch (e: IOException) {
                transient = transient || e !is SyncProblem || e.retryable
                problems += ((e as? SyncProblem)?.code ?: "network") to (e.message ?: "Google could not confirm sync status.")
            }
            val message = if (problems.isEmpty() && !transient) {
                store.put("last_success", Instant.now().toString()); store.put("last_error", "")
                store.put("successful_app_version", APP_VERSION)
                alerts.recovered()
                "Sync complete. Google confirmed all available categories."
            } else {
                val message = problems.firstOrNull()?.second ?: "Google could not confirm sync status. It will be retried."
                store.put("last_error", problems.joinToString("\n") { it.second }.ifEmpty { message })
                alerts.issue(problems.firstOrNull()?.first ?: "network", message, !transient)
                "Some data still needs attention. $message"
            }
            store.log(message)
            alerts.optionalCheckIn()
            if (foreground && transient && store.get("enabled") == "true") Scheduling.retrySoon(context)
            return SyncOutcome(transient, message)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            val retry = if (error is SyncProblem) error.retryable else error is IOException
            val message = error.message ?: "Sync failed. Open the app for details."
            store.put("last_error", message); store.log(message)
            alerts.issue((error as? SyncProblem)?.code ?: "sync", message, !retry)
            if (foreground && retry && store.get("enabled") == "true") Scheduling.retrySoon(context)
            return SyncOutcome(retry, message)
        } finally { store.close(); mutex.unlock() }
    }

    private suspend fun flush(store: Store, webhook: Webhook) {
        while (true) {
            val item = store.next() ?: break
            val response = webhook.send(item.payload)
            if (item.payload.optString("action") == "steps" &&
                (response.optJSONObject("step_check") == null || response.optJSONArray("step_issues") == null))
                throw SyncProblem("server_version", "Google did not confirm the step snapshot. Update Code.gs to v1.3.1.")
            store.acknowledge(item, response)
        }
    }

    private suspend fun syncSteps(today: LocalDate, backfill: LocalDate?, store: Store, webhook: Webhook) {
        val previous = store.get("cursor_direct_steps").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        val pending = store.get("backfill_steps").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        var from = maxOf(LocalDate.parse("2026-07-09"), backfill ?: pending ?:
            minOf(previous?.plusDays(1) ?: today.minusDays(3), today.minusDays(3)))
        // Read the current day first when importing history, so briefings do not wait behind backfill.
        if (from < today.minusDays(3)) {
            val current = StepSnapshot.read(today.minusDays(3), today, { SamsungSteps(context).read(it) })
            store.enqueue(current)
            flush(store, webhook)
        }
        while (from <= today) {
            val to = minOf(from.plusDays(6), today)
            val payload = StepSnapshot.read(from, to, { SamsungSteps(context).read(it) })
                .put("category", "steps")
                .put("backfill_next", if (to == today) "" else to.plusDays(1).toString())
            store.enqueue(payload, "cursor_direct_steps", maxOf(previous ?: to, to).toString())
            flush(store, webhook)
            from = to.plusDays(1)
        }
    }

    private suspend fun syncDaily(reader: HealthReader, category: Category, today: LocalDate, earliest: LocalDate,
        backfill: LocalDate?, store: Store, webhook: Webhook) {
        val key = "cursor_${category.key}"
        val cursor = store.get(key).takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
        var from = maxOf(earliest, backfill ?: if (cursor == null) earliest else minOf(cursor.plusDays(1), today.minusDays(3)))
        while (from <= today) {
            val to = minOf(from.plusDays(6), today)
            val payload = reader.daily(category, from, to)
            val covered = minOf(to, today.minusDays(1))
            val checkpoint = maxOf(cursor ?: covered, covered)
            payload.put("complete_through", covered.toString())
            payload.put("coverage_start", earliest.toString())
            if (backfill != null) payload.put("backfill_next", if (to == today) "" else to.plusDays(1).toString())
            val digestKey = "digest_v12_${category.key}_${from}_${to}"
            val digest = SnapshotDigest.of(payload)
            if (backfill != null || store.get(digestKey) != digest) {
                payload.put("_digest_key", digestKey).put("_digest", digest)
                store.enqueue(payload, key, checkpoint.toString())
                flush(store, webhook)
            }
            from = to.plusDays(1)
        }
    }

    private suspend fun syncMeasurements(reader: HealthReader, category: Category, earliest: LocalDate,
        store: Store, webhook: Webhook) {
        val key = "token_${category.key}"
        var token = store.get(key)
        if (token.isEmpty()) token = snapshotMeasurements(reader, category, earliest, store, webhook)
        var pages = 0
        do {
            val changes = reader.client.getChanges(token)
            if (changes.changesTokenExpired) {
                store.log("${category.label} change token expired; reconciling the readable history.")
                snapshotMeasurements(reader, category, earliest, store, webhook)
                return
            }
            val upserts = linkedMapOf<String, JSONObject>()
            val deletions = linkedSetOf<String>()
            changes.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> reader.measurement(category, change.record)?.let {
                        val id = it.getString("record_id"); upserts[id] = it; deletions.remove(id)
                    }
                    is DeletionChange -> {
                        val id = "${category.key}:${change.recordId}"; upserts.remove(id); deletions.add(id)
                    }
                }
            }
            val rows = JSONArray().apply { upserts.values.forEach { put(it) } }
            val deleted = JSONArray().apply { deletions.forEach { put(it) } }
            if (rows.length() == 0 && deleted.length() == 0) {
                // No remote data to acknowledge. Advancing an empty page cannot lose an update.
                store.put(key, changes.nextChangesToken)
            } else {
                store.enqueue(JSONObject().put("action", "measurements").put("category", category.key)
                    .put("mode", "changes").put("rows", rows).put("deleted_ids", deleted), key, changes.nextChangesToken)
                flush(store, webhook)
            }
            token = changes.nextChangesToken
            check(++pages <= 1000) { "Health Connect returned too many change pages. Progress is saved." }
        } while (changes.hasMore)
    }

    private suspend fun snapshotMeasurements(reader: HealthReader, category: Category, earliest: LocalDate,
        store: Store, webhook: Webhook): String {
        // Reserve the token BEFORE reading so updates arriving during the snapshot are not lost.
        val token = reader.client.getChangesToken(ChangesTokenRequest(recordTypes = setOf(category.type)))
        val from = earliest.atStartOfDay(SYDNEY).toInstant()
        val to = Instant.now()
        val records = reader.readAll(category.type, from, to)
        val rows = JSONArray()
        records.forEach { reader.measurement(category, it)?.let { row -> rows.put(row) } }
        store.enqueue(JSONObject().put("action", "measurements").put("category", category.key)
            .put("mode", "snapshot").put("start_at", from.toString()).put("end_at", to.toString())
            .put("rows", rows).put("deleted_ids", JSONArray()), "token_${category.key}", token)
        flush(store, webhook)
        return token
    }
}
