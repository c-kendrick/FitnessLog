package au.com.kit.fitnesslogsync

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Reads each date independently so a failed date cannot erase a good one or hide today's result. */
object StepSnapshot {
    suspend fun read(from: LocalDate, to: LocalDate, read: suspend (LocalDate) -> Long?,
                     clock: () -> Instant = { Instant.now() }): JSONObject {
        require(to >= from && to <= from.plusDays(6))
        val rows = JSONArray()
        var date = from
        while (date <= to) {
            var issue = ""
            var state = "value"
            val value = try {
                read(date).also { if (it == null) state = "no_data" else require(it >= 0) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                state = "error"
                issue = (e.message ?: "Samsung step read failed. Check Connect Samsung steps.").take(500)
                null
            }
            rows.put(JSONObject().put("date", date.toString()).put("value", value ?: JSONObject.NULL)
                .put("data_status", state).put("issue", issue).put("checked_at", clock().toString()))
            date = date.plusDays(1)
        }
        return JSONObject().put("action", "steps").put("source_mode", "samsung_sdk_total")
            .put("start_date", from.toString()).put("end_date", to.toString()).put("rows", rows)
    }
}
