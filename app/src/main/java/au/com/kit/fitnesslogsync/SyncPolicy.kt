package au.com.kit.fitnesslogsync

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val APP_VERSION = "1.3.1"

/** Matches the task's DTSTART;TZID=Australia/Sydney:20261003T200000 and HOURLY;INTERVAL=5. */
object SyncPolicy {
    val zone: ZoneId = ZoneId.of("Australia/Sydney")
    val wellnessAnchor: LocalDateTime = LocalDateTime.of(2026, 10, 3, 20, 0)
    const val SCHEDULE_VERSION = "sydney-20261003-2000-every5h-v1"
    val lead: Duration = Duration.ofHours(1)

    // Calendar boundaries, not a fixed 24-hour duration: Sydney has 23/25-hour DST days.
    fun stepWindow(date: LocalDate): Pair<Instant, Instant> =
        date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

    fun nextSlots(now: Instant, count: Int = 3): List<Instant> {
        val localNow = now.atZone(zone).toLocalDateTime()
        val firstIndex = maxOf(0L, ChronoUnit.HOURS.between(wellnessAnchor, localNow) / 5 - 2)
        val slots = sortedSetOf<Instant>()
        // RFC 5545 hourly recurrence follows local wall time; omit nonexistent DST hours.
        for (index in firstIndex..firstIndex + count * 6L + 12) {
            val local = wellnessAnchor.plusHours(index * 5)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.isNotEmpty()) slots += local.toInstant(offsets.first()).minus(lead)
        }
        for (day in 0..count + 3) {
            slots += localNow.toLocalDate().plusDays(day.toLong()).atTime(LocalTime.of(6, 0))
                .atZone(zone).toInstant().minus(lead)
        }
        // Exact coincidences (e.g. tomorrow's 06:00 check-in and daily briefing) share one job.
        return slots.filter { it > now }.take(count)
    }

    fun recentlySuccessful(lastSuccess: String, now: Instant): Boolean = runCatching {
        val previous = Instant.parse(lastSuccess)
        val elapsed = Duration.between(previous, now)
        !elapsed.isNegative && elapsed < Duration.ofMinutes(15) &&
            previous.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    }.getOrDefault(false)
}
