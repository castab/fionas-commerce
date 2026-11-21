package io.github.castab.fionas.commerce.notification

import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** A slot this late (the app was down or busy at the time) is still raised; an older one is skipped. */
val REMINDER_GRACE: Duration = Duration.ofMinutes(30)

/**
 * Raises the unanswered-inquiry reminder at each slot of its [schedule]. It owns only *when*:
 * one virtual thread sleeps until the next slot and calls [remind], which records the job;
 * delivery is the Web Push dispatcher's. Starting within [grace] after a slot (a restart during
 * a deploy) still raises that slot, and the slot's job identity keeps it from being raised twice.
 */
class UnansweredReminderScheduler(
    private val schedule: CronSchedule,
    private val remind: (Instant) -> ReminderOutcome,
    private val clock: Clock,
    private val grace: Duration = REMINDER_GRACE,
    /** Wake at least this often, so a wall-clock jump never oversleeps a slot by much. */
    private val maxSleep: Duration = Duration.ofMinutes(1),
) : ManagedBackgroundService {
    private val monitor = Object()
    private var cursor: Instant? = null
    private var worker: Thread? = null

    @Volatile private var stopping = false

    /**
     * Raises every slot in (cursor, [now]] that is no more than [grace] old, skips older ones, and
     * advances the cursor. Returns the next slot after [now].
     */
    internal fun runDue(now: Instant): Instant {
        var slot = schedule.next(checkNotNull(cursor) { "Reminder scheduler has not started" })
        while (!slot.isAfter(now)) {
            if (Duration.between(slot, now) <= grace) {
                raise(slot)
            } else {
                logger.warn { "event=unanswered_reminder_skipped slot=$slot reason=missed_by_more_than_grace" }
            }
            cursor = slot
            slot = schedule.next(slot)
        }
        return slot
    }

    private fun raise(slot: Instant) {
        try {
            when (val outcome = remind(slot)) {
                is ReminderOutcome.Queued ->
                    logger.info {
                        "event=unanswered_reminder_queued slot=$slot notification=${outcome.notification.value} count=${outcome.count}"
                    }
                is ReminderOutcome.AlreadyQueued ->
                    logger.info { "event=unanswered_reminder_already_queued slot=$slot notification=${outcome.notification.value}" }
                ReminderOutcome.NothingUnanswered -> logger.info { "event=unanswered_reminder_not_needed slot=$slot count=0" }
            }
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (failure: Exception) {
            // One failed slot (database unavailable) must not stop later slots.
            logger.error(failure) { "event=unanswered_reminder_failed slot=$slot" }
        }
    }

    internal fun startAt(now: Instant) =
        synchronized(monitor) {
            if (cursor == null) cursor = now - grace
        }

    override fun start() =
        synchronized(monitor) {
            check(!stopping) { "Reminder scheduler is closed" }
            if (worker != null) return@synchronized
            startAt(clock.instant())
            logger.info { "event=unanswered_reminder_scheduler_started schedule=\"${schedule.expression}\" zone=${schedule.zone}" }
            worker =
                Thread
                    .ofVirtual()
                    .name("fionas-unanswered-reminders")
                    .unstarted(::runLoop)
                    .also(Thread::start)
        }

    private fun runLoop() {
        try {
            while (!stopping) {
                val now = clock.instant()
                val next = runDue(now)
                val wait = minOf(Duration.between(clock.instant(), next), maxSleep)
                if (!wait.isNegative && !wait.isZero) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
            // close() interrupts the sleep.
        }
    }

    override fun close() {
        val thread =
            synchronized(monitor) {
                stopping = true
                worker
            }
        thread?.interrupt()
        thread?.join(Duration.ofSeconds(15))
    }
}
