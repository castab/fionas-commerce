package io.github.castab.fionas.commerce.notification

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.BitSet

/**
 * A standard five-field cron expression (`minute hour day-of-month month day-of-week`) evaluated
 * in an explicit [zone], so wall-clock times follow daylight saving. Fields accept `*`, single
 * values, `a-b` ranges, `/step`, comma lists and English month/day names (`JAN`, `MON-FRI`); day
 * of week 0 and 7 are both Sunday. As in cron, when both day fields are restricted a day matches
 * either one. A local time skipped by a daylight-saving gap runs at the shifted instant; a
 * repeated one runs once, at its first occurrence.
 */
class CronSchedule private constructor(
    val expression: String,
    val zone: ZoneId,
    private val minutes: BitSet,
    private val hours: BitSet,
    private val daysOfMonth: BitSet,
    private val months: BitSet,
    private val daysOfWeek: BitSet,
    private val dayOfMonthRestricted: Boolean,
    private val dayOfWeekRestricted: Boolean,
) {
    private val hourValues = hours.stream().toArray()
    private val minuteValues = minutes.stream().toArray()

    /** The first scheduled instant strictly after [after]. */
    fun next(after: Instant): Instant {
        val start = after.atZone(zone).toLocalDate().minusDays(1)
        for (offset in 0..SEARCH_DAYS) {
            val day = start.plusDays(offset.toLong())
            if (!matches(day)) continue
            for (hour in hourValues) {
                for (minute in minuteValues) {
                    val candidate = day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
                    if (candidate.isAfter(after)) return candidate
                }
            }
        }
        error("Cron schedule '$expression' has no time within $SEARCH_DAYS days")
    }

    private fun matches(day: LocalDate): Boolean {
        if (!months[day.monthValue]) return false
        val byMonthDay = daysOfMonth[day.dayOfMonth]
        val byWeekDay = daysOfWeek[day.dayOfWeek.value % 7]
        return when {
            dayOfMonthRestricted && dayOfWeekRestricted -> byMonthDay || byWeekDay
            dayOfMonthRestricted -> byMonthDay
            dayOfWeekRestricted -> byWeekDay
            else -> true
        }
    }

    override fun toString() = "CronSchedule($expression, $zone)"

    companion object {
        /** Long enough for any satisfiable expression, including 29 February. */
        private const val SEARCH_DAYS = 366 * 8

        private val MONTH_NAMES =
            listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
                .mapIndexed { index, name -> name to index + 1 }
                .toMap()
        private val DAY_NAMES =
            DayOfWeek.entries.associate { it.name.take(3) to it.value % 7 }

        fun parse(
            expression: String,
            zone: ZoneId,
        ): CronSchedule {
            val fields = expression.trim().split(Regex("\\s+"))
            require(fields.size == 5 && fields.none(String::isEmpty)) {
                "A cron schedule needs five fields: minute hour day-of-month month day-of-week"
            }
            val daysOfWeek = field(fields[4], 0, 7, DAY_NAMES, "day-of-week")
            if (daysOfWeek[7]) daysOfWeek.set(0)
            daysOfWeek.clear(7)
            val schedule =
                CronSchedule(
                    fields.joinToString(" "),
                    zone,
                    field(fields[0], 0, 59, emptyMap(), "minute"),
                    field(fields[1], 0, 23, emptyMap(), "hour"),
                    field(fields[2], 1, 31, emptyMap(), "day-of-month"),
                    field(fields[3], 1, 12, MONTH_NAMES, "month"),
                    daysOfWeek,
                    !fields[2].startsWith("*"),
                    !fields[4].startsWith("*"),
                )
            try {
                schedule.next(Instant.EPOCH)
            } catch (_: IllegalStateException) {
                throw IllegalArgumentException("Cron schedule '$expression' never runs")
            }
            return schedule
        }

        private fun field(
            text: String,
            min: Int,
            max: Int,
            names: Map<String, Int>,
            label: String,
        ): BitSet {
            val bits = BitSet(max + 1)
            text.split(',').forEach { part ->
                require(part.isNotEmpty()) { "Cron $label has an empty list entry" }
                val (range, stepText) = part.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
                val step =
                    stepText?.let {
                        val value = it.toIntOrNull()
                        require(value != null && value > 0) { "Cron $label step must be a positive number" }
                        value
                    } ?: 1
                val (from, to) =
                    when {
                        range == "*" -> min to max
                        '-' in range -> {
                            val (low, high) = range.split('-', limit = 2)
                            value(low, min, max, names, label) to value(high, min, max, names, label)
                        }
                        else -> value(range, min, max, names, label).let { it to if (stepText != null) max else it }
                    }
                require(from <= to) { "Cron $label range must not run backwards" }
                (from..to step step).forEach(bits::set)
            }
            return bits
        }

        private fun value(
            text: String,
            min: Int,
            max: Int,
            names: Map<String, Int>,
            label: String,
        ): Int {
            val number = text.toIntOrNull() ?: names[text.uppercase()]
            require(number != null && number in min..max) { "Cron $label value '$text' must be within $min-$max" }
            return number
        }
    }
}
