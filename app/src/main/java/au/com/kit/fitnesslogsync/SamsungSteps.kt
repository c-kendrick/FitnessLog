package au.com.kit.fitnesslogsync

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Direct Samsung aggregate; never sum phone/watch records or substitute Google Fit. */
class SamsungSteps(context: Context) {
    private val store = HealthDataService.getStore(context.applicationContext)
    private val permissions = setOf(Permission.of(DataTypes.STEPS, AccessType.READ))
    suspend fun hasPermission(): Boolean = store.getGrantedPermissions(permissions).containsAll(permissions)
    suspend fun requestPermission(activity: Activity): Boolean = store.requestPermissions(permissions, activity).containsAll(permissions)
    suspend fun read(date: LocalDate, now: Instant = Instant.now()): Long? {
        check(hasPermission()) { "Direct Samsung step access is missing. Tap Connect Samsung steps." }
        // SDK local-time filters follow recorded local days. Do not silently mix a travelling phone's day with Sydney briefings.
        check(ZoneId.systemDefault().rules.getOffset(now) == SYDNEY.rules.getOffset(now)) {
            "Phone time zone differs from Sydney. Step syncing needs a time-zone review."
        }
        val start = date.atStartOfDay()
        val end = minOf(date.plusDays(1).atStartOfDay(), now.atZone(SYDNEY).toLocalDateTime())
        if (end <= start) return null
        val request = DataType.StepsType.TOTAL.requestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(start, end)).build()
        val values = store.aggregateData(request).dataList.map { it.value }
        // An ungrouped aggregate is a single total. Unexpected multiple results must not double-count.
        check(values.size <= 1) { "Samsung returned multiple totals for one day; steps were not combined." }
        return values.singleOrNull()?.also { check(it >= 0) { "Samsung returned an invalid step total." } }
    }
    suspend fun diagnose(date: LocalDate): String = buildString {
        appendLine("Source: Samsung Health direct (TOTAL)")
        appendLine("Checked: ${Instant.now().atZone(SYDNEY)}")
        appendLine("$date: ${read(date) ?: "no data"}")
        appendLine("${date.minusDays(1)}: ${read(date.minusDays(1)) ?: "no data"}")
        append("Compare with Samsung Health's All steps view. These are daily totals, not values to add to Health Connect records.")
    }
}
