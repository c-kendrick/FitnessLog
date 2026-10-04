package au.com.kit.fitnesslogsync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")
const val SAMSUNG = "com.sec.android.app.shealth"
const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

data class Category(val key: String, val label: String, val type: KClass<out Record>, val unit: String) {
    val permission: String get() = HealthPermission.getReadPermission(type)
}
val DAILY = listOf(
    Category("steps", "Steps", StepsRecord::class, "steps"),
    Category("exercise", "Workouts", ExerciseSessionRecord::class, "minutes"),
    Category("calories", "Calories reported by Health Connect", TotalCaloriesBurnedRecord::class, "kcal")
)
val MEASUREMENTS = listOf(
    Category("weight", "Weight", WeightRecord::class, "kg"),
    Category("body_fat", "Body fat", BodyFatRecord::class, "%"),
    Category("height", "Height", HeightRecord::class, "cm"),
    Category("basal_metabolic_rate", "Basal metabolic rate", BasalMetabolicRateRecord::class, "kcal/day")
)

class HealthReader(val client: HealthConnectClient) {
    fun supports(feature: Int): Boolean = client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    fun requestPermissions(): Set<String> = (DAILY + MEASUREMENTS).map { it.permission }.toSet() +
        (if (supports(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) setOf(BACKGROUND) else emptySet()) +
        (if (supports(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) setOf(HISTORY) else emptySet())

    suspend fun readAll(type: KClass<out Record>, start: Instant, end: Instant): List<Record> {
        val records = mutableListOf<Record>()
        var token: String? = null
        var pages = 0
        do {
            val result = client.readRecords(ReadRecordsRequest(
                recordType = type, timeRangeFilter = TimeRangeFilter.between(start, end),
                dataOriginFilter = setOf(DataOrigin(SAMSUNG)), pageToken = token
            ))
            records += result.records
            token = result.pageToken
            check(++pages <= 1000) { "Health Connect returned too many pages; no partial snapshot was uploaded." }
        } while (!token.isNullOrEmpty())
        return records
    }

    suspend fun daily(category: Category, start: LocalDate, end: LocalDate): JSONObject {
        val rows = JSONArray()
        val workouts = JSONArray()
        var date = start
        while (!date.isAfter(end)) {
            val (from, dayEnd) = SyncPolicy.stepWindow(date)
            val to = minOf(dayEnd, Instant.now())
            if (to.isAfter(from)) {
                when (category.key) {
                    "steps" -> {
                        // Samsung can publish the current total in an interval spanning the full day.
                        // Cutting that interval at now makes Health Connect apportion the count.
                        // Query the whole calendar day, even today. This reads existing records only.
                        val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, dayEnd), setOf(DataOrigin(SAMSUNG))))
                        rows.put(dailyRow(date, "steps", result[StepsRecord.COUNT_TOTAL], "steps"))
                    }
                    "calories" -> {
                        val result = client.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), TimeRangeFilter.between(from, to), setOf(DataOrigin(SAMSUNG))))
                        rows.put(dailyRow(date, "calories", result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories, "kcal"))
                    }
                    "exercise" -> {
                        // Include sessions starting the previous day for ordinary overnight workouts.
                        val records = readAll(ExerciseSessionRecord::class, from.minus(Duration.ofDays(1)), to).filterIsInstance<ExerciseSessionRecord>()
                        val elapsed = records.sumOf {
                            Duration.between(maxOf(from, it.startTime), minOf(to, it.endTime)).seconds.coerceAtLeast(0) / 60.0
                        }
                        // Elapsed session time is labelled explicitly; it may include pauses.
                        rows.put(dailyRow(date, "exercise_elapsed_minutes", elapsed, "minutes"))
                        rows.put(dailyRow(date, "exercise_sessions", records.count { it.startTime >= from && it.startTime < to }, "sessions"))
                        records.filter { it.startTime >= from && it.startTime < to }.forEach { r ->
                            workouts.put(JSONObject().put("record_id", r.metadata.id)
                                .put("start_at", r.startTime.toString()).put("end_at", r.endTime.toString())
                                .put("start_date", r.startTime.atZone(SYDNEY).toLocalDate().toString())
                                .put("exercise_type", r.exerciseType).put("title", r.title ?: "")
                                .put("elapsed_minutes", Duration.between(r.startTime, r.endTime).seconds / 60.0)
                                .put("source", r.metadata.dataOrigin.packageName).put("modified_at", r.metadata.lastModifiedTime.toString()))
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return JSONObject().put("action", "daily").put("category", category.key)
            .put("start_date", start.toString()).put("end_date", end.toString())
            .put("rows", rows).put("workouts", workouts)
    }

    private fun dailyRow(date: LocalDate, metric: String, value: Number?, unit: String) = JSONObject()
        .put("date", date.toString()).put("metric", metric).put("value", value ?: JSONObject.NULL)
        .put("unit", unit).put("data_status", if (value == null) "no_data" else "value")
        .put("source", SAMSUNG).put("observed_at", Instant.now().toString())

    suspend fun diagnoseSteps(date: LocalDate): String {
        val (from, end) = SyncPolicy.stepWindow(date)
        val now = Instant.now()
        suspend fun total(until: Instant): Long? = client.aggregate(AggregateRequest(
            setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, until), setOf(DataOrigin(SAMSUNG))
        ))[StepsRecord.COUNT_TOTAL]
        val full = total(end)
        val partial = if (now > from && now < end) total(now) else full
        val records = readAll(StepsRecord::class, from, end).filterIsInstance<StepsRecord>()
        return buildString {
            appendLine("Checked: ${now.atZone(SYDNEY)}")
            appendLine("Date: $date; source: Samsung Health via Health Connect")
            appendLine("Full calendar-day query (exported): ${full ?: "no data"}")
            appendLine("Old midnight-to-now query: ${partial ?: "no data"}")
            appendLine("Samsung step records: ${records.size}")
            records.take(12).forEach { r ->
                appendLine("${r.startTime.atZone(SYDNEY)} to ${r.endTime.atZone(SYDNEY)}: ${r.count} steps; modified ${r.metadata.lastModifiedTime}")
            }
            if (records.size > 12) appendLine("Showing first 12 records; aggregate includes all records.")
            append("Compare the full-day result with Samsung Health. These are source records, not additional steps to add together.")
        }
    }

    fun measurement(category: Category, record: Record): JSONObject? {
        if (record.metadata.dataOrigin.packageName != SAMSUNG) return null
        val (time, value) = when (record) {
            is WeightRecord -> record.time to record.weight.inKilograms
            is BodyFatRecord -> record.time to record.percentage.value
            is HeightRecord -> record.time to record.height.inMeters * 100.0
            is BasalMetabolicRateRecord -> record.time to record.basalMetabolicRate.inKilocaloriesPerDay
            else -> return null
        }
        return JSONObject().put("record_id", "${category.key}:${record.metadata.id}")
            .put("measured_at", time.toString()).put("metric", category.key).put("value", value)
            .put("unit", category.unit).put("source", SAMSUNG).put("modified_at", record.metadata.lastModifiedTime.toString())
    }
}
