package io.github.castab.fionas.commerce.notification

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")

private fun pacific(text: String): Instant = LocalDateTime.parse(text).atZone(PACIFIC).toInstant()

class CronScheduleSpec :
    FunSpec({
        val weekdays = CronSchedule.parse("0 10,14 * * MON-FRI", PACIFIC)

        test("10 AM and 2 PM Pacific on weekdays, skipping the weekend") {
            weekdays.next(pacific("2026-11-16T09:59")) shouldBe pacific("2026-11-16T10:00")
            weekdays.next(pacific("2026-11-16T10:00")) shouldBe pacific("2026-11-16T14:00")
            weekdays.next(pacific("2026-11-20T14:00")) shouldBe pacific("2026-11-23T10:00")
            weekdays.next(pacific("2026-11-21T12:00")) shouldBe pacific("2026-11-23T10:00")
        }

        test("wall-clock times follow daylight saving") {
            // 2027-03-14 springs forward and 2026-11-01 falls back; 10 AM stays 10 AM local.
            weekdays.next(pacific("2027-03-12T15:00")) shouldBe Instant.parse("2027-03-15T17:00:00Z")
            weekdays.next(pacific("2026-10-30T15:00")) shouldBe Instant.parse("2026-11-02T18:00:00Z")
            // A time skipped by the gap runs at the shifted instant, once.
            val gap = CronSchedule.parse("30 2 * * *", PACIFIC)
            gap.next(pacific("2027-03-13T12:00")) shouldBe Instant.parse("2027-03-14T10:30:00Z")
            // A repeated time runs once, at its first occurrence.
            val overlap = CronSchedule.parse("30 1 * * *", PACIFIC)
            val first = overlap.next(pacific("2026-10-31T12:00"))
            first shouldBe Instant.parse("2026-11-01T08:30:00Z")
            overlap.next(first) shouldBe Instant.parse("2026-11-02T09:30:00Z")
        }

        test("lists, ranges, steps, names and Sunday as 0 or 7; either day field matches when both are set") {
            val utc = ZoneId.of("UTC")
            val steps = CronSchedule.parse("*/20 9-10 * * *", utc)
            steps.next(Instant.parse("2026-11-16T09:20:00Z")) shouldBe Instant.parse("2026-11-16T09:40:00Z")
            steps.next(Instant.parse("2026-11-16T10:40:00Z")) shouldBe Instant.parse("2026-11-17T09:00:00Z")
            CronSchedule.parse("0 12 * * 7", utc).next(Instant.parse("2026-11-16T00:00:00Z")) shouldBe
                Instant.parse("2026-11-22T12:00:00Z")
            CronSchedule.parse("0 12 * * sun", utc).next(Instant.parse("2026-11-16T00:00:00Z")) shouldBe
                Instant.parse("2026-11-22T12:00:00Z")
            CronSchedule.parse("0 0 1 JAN *", utc).next(Instant.parse("2026-11-16T00:00:00Z")) shouldBe
                Instant.parse("2027-01-01T00:00:00Z")
            // The 1st of the month or any Friday.
            val either = CronSchedule.parse("0 8 1 * FRI", utc)
            either.next(Instant.parse("2026-11-16T00:00:00Z")) shouldBe Instant.parse("2026-11-20T08:00:00Z")
            either.next(Instant.parse("2026-11-28T00:00:00Z")) shouldBe Instant.parse("2026-12-01T08:00:00Z")
            CronSchedule.parse("0 0 29 2 *", utc).next(Instant.parse("2026-11-16T00:00:00Z")) shouldBe
                Instant.parse("2028-02-29T00:00:00Z")
        }

        test("malformed or impossible expressions are refused") {
            listOf(
                "",
                "0 10 * *",
                "0 10 * * * *",
                "60 10 * * *",
                "0 24 * * *",
                "0 10 0 * *",
                "0 10 * 13 *",
                "0 10 * * 8",
                "0 10 * * FUNDAY",
                "0 14-10 * * *",
                "*/0 10 * * *",
                "0 10,,14 * * *",
            ).forEach { expression ->
                shouldThrow<IllegalArgumentException> { CronSchedule.parse(expression, PACIFIC) }
            }
            shouldThrow<IllegalArgumentException> { CronSchedule.parse("0 10 31 2 *", PACIFIC) }.message shouldContain "never runs"
        }
    })

class UnansweredReminderSchedulerSpec :
    FunSpec({
        val schedule = CronSchedule.parse("0 10,14 * * MON-FRI", PACIFIC)

        fun scheduler(
            raised: MutableList<Instant>,
            failing: Set<Instant> = emptySet(),
        ) = UnansweredReminderScheduler(
            schedule,
            { slot ->
                if (slot in failing) error("database unavailable")
                raised += slot
                ReminderOutcome.Queued(StaffNotificationId(UUID.randomUUID()), 1)
            },
            Clock.systemUTC(),
        )

        test("a slot runs once when due; a start within the grace window still raises the slot just missed") {
            val raised = mutableListOf<Instant>()
            val subject = scheduler(raised)
            subject.startAt(pacific("2026-11-16T10:20"))
            subject.runDue(pacific("2026-11-16T10:20")) shouldBe pacific("2026-11-16T14:00")
            raised shouldBe listOf(pacific("2026-11-16T10:00"))
            subject.runDue(pacific("2026-11-16T13:59")) shouldBe pacific("2026-11-16T14:00")
            subject.runDue(pacific("2026-11-16T14:00")) shouldBe pacific("2026-11-17T10:00")
            raised shouldBe listOf(pacific("2026-11-16T10:00"), pacific("2026-11-16T14:00"))
        }

        test("a start after the grace window does not raise the earlier slot") {
            val raised = mutableListOf<Instant>()
            val subject = scheduler(raised)
            subject.startAt(pacific("2026-11-16T10:31"))
            subject.runDue(pacific("2026-11-16T10:31"))
            raised shouldBe emptyList()
        }

        test("slots missed by more than the grace window are skipped, not replayed in a burst") {
            val raised = mutableListOf<Instant>()
            val subject = scheduler(raised)
            subject.startAt(pacific("2026-11-16T09:00"))
            // Asleep (suspended VM) from Monday 9 AM until Tuesday 2:10 PM.
            subject.runDue(pacific("2026-11-17T14:10")) shouldBe pacific("2026-11-18T10:00")
            raised shouldBe listOf(pacific("2026-11-17T14:00"))
        }

        test("one failed slot does not stop later slots") {
            val raised = mutableListOf<Instant>()
            val subject = scheduler(raised, failing = setOf(pacific("2026-11-16T10:00")))
            subject.startAt(pacific("2026-11-16T09:00"))
            subject.runDue(pacific("2026-11-16T10:00"))
            subject.runDue(pacific("2026-11-16T14:00"))
            raised shouldBe listOf(pacific("2026-11-16T14:00"))
        }

        test("the managed service starts and stops promptly") {
            val subject = scheduler(mutableListOf())
            subject.start()
            subject.close()
        }
    })
