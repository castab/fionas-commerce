package io.github.castab.fionas.commerce.inquiry

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The event's calendar date, without a time of day or time zone. */
@JvmInline
value class EventDate(
    val value: LocalDate,
) {
    init {
        require(value.year in 1..9999) { "Event date must have a year between 0001 and 9999" }
    }

    companion object {
        const val PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"

        fun of(submitted: String): EventDate {
            require(Regex(PATTERN).matches(submitted)) { "Event date must use YYYY-MM-DD" }
            val date =
                try {
                    LocalDate.parse(submitted)
                } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException("Event date must be a valid calendar date")
                }
            return EventDate(date)
        }
    }
}
