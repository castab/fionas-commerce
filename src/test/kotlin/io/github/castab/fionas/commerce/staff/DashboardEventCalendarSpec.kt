package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.fionas.commerce.fionaApplication
import io.github.castab.fionas.commerce.fionaEventCalendarZone
import io.github.castab.fionas.commerce.http.StaffAttentionReasonResponse
import io.github.castab.fionas.commerce.http.StaffDashboardResponse
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.invoiceLatest
import io.github.castab.fionas.commerce.testing.quoteLatest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class DashboardEventCalendarSpec :
    FunSpec({
        test("event calendar defaults to Los Angeles; configured IANA zones are strict and fail during composition") {
            fionaEventCalendarZone(emptyMap()).id shouldBe "America/Los_Angeles"
            listOf("America/Los_Angeles", "America/New_York", "UTC").forEach { name ->
                fionaEventCalendarZone(mapOf("FIONAS_EVENT_TIME_ZONE" to name)).id shouldBe name
            }
            listOf("", "Unknown/Zone", " America/Los_Angeles ").forEach { value ->
                shouldThrow<DateTimeException> {
                    fionaApplication(bootstrap = null, eventCalendarZone = fionaEventCalendarZone(mapOf("FIONAS_EVENT_TIME_ZONE" to value)))
                }
            }
        }

        test("explicit UTC configuration does not change the harness default unless supplied to it") {
            val configured = fionaEventCalendarZone(mapOf("FIONAS_EVENT_TIME_ZONE" to "UTC"))
            configured.id shouldBe "UTC"
            val midnightUtc = Instant.parse("2026-07-26T00:00:00Z")
            val clock = Clock.fixed(midnightUtc, ZoneOffset.UTC)
            listOf(null, configured).forEach { calendarZone ->
                val application =
                    if (calendarZone == null) {
                        TestApplication.create(clock = clock)
                    } else {
                        TestApplication.create(clock = clock, eventCalendarZone = calendarZone)
                    }
                application.use { app ->
                    val id = app.createInquiry()
                    app.database.execute("UPDATE fionas.inquiries SET event_date = DATE '2026-07-25' WHERE id = '$id'")
                    val document = UUID.fromString(app.initialEstimateOf(id))
                    app.transactor.inTransaction {
                        app.context.financialLedger.quoteLatest(it, document)
                        app.context.financialLedger.invoiceLatest(it, document)
                    }
                    val dashboard = CommerceJson.asA(app.adminGet("/staff/dashboard").bodyString(), StaffDashboardResponse.serializer())
                    if (calendarZone == null) {
                        dashboard.workQueue.needsResolution.items shouldBe emptyList()
                    } else {
                        dashboard.workQueue.needsResolution.items
                            .single()
                            .attentionSince shouldBe midnightUtc.toString()
                    }
                }
            }
        }

        val boundaries =
            listOf(
                Triple("2026-07-25", "2026-07-26T07:00:00Z", null),
                Triple("2026-03-08", "2026-03-09T07:00:00Z", null),
                Triple("2026-11-01", "2026-11-02T08:00:00Z", null),
                Triple("2026-07-25", "2026-07-26T04:00:00Z", "America/New_York"),
                Triple("2026-07-25", "2026-07-26T00:00:00Z", "UTC"),
            )
        boundaries.forEach { (date, boundary, configuredZone) ->
            test("application wires ${configuredZone ?: "default Los Angeles"} with UTC Clock: $date ends at $boundary") {
                val midnight = Instant.parse(boundary)
                var now = midnight.minusSeconds(1)
                val utcClock =
                    object : Clock() {
                        override fun getZone(): ZoneId = ZoneOffset.UTC

                        override fun withZone(zone: ZoneId): Clock = error("Event zone must not come from Clock")

                        override fun instant(): Instant = now
                    }
                val alternate = configuredZone?.let { fionaEventCalendarZone(mapOf("FIONAS_EVENT_TIME_ZONE" to it)) }
                val application =
                    if (alternate == null) {
                        TestApplication.create(clock = utcClock)
                    } else {
                        TestApplication.create(clock = utcClock, eventCalendarZone = alternate)
                    }
                application.use { app ->
                    val id = app.createInquiry()
                    app.database.execute("UPDATE fionas.inquiries SET event_date = DATE '$date' WHERE id = '$id'")
                    val document = UUID.fromString(app.initialEstimateOf(id))
                    app.transactor.inTransaction {
                        app.context.financialLedger.quoteLatest(it, document)
                        app.context.financialLedger.invoiceLatest(it, document)
                    }

                    fun read() = CommerceJson.asA(app.adminGet("/staff/dashboard").bodyString(), StaffDashboardResponse.serializer())
                    // UTC is already on the next day while California/New York's event date is still in progress.
                    read().workQueue.needsResolution.items shouldBe emptyList()
                    now = midnight
                    val result = read()
                    result.asOf shouldBe boundary
                    result.workQueue.needsResolution.items
                        .single()
                        .attentionSince shouldBe boundary
                    result.workQueue.needsResolution.items
                        .single()
                        .reasons shouldBe
                        listOf(StaffAttentionReasonResponse.EVENT_DATE_PASSED_UNSERVED)
                }
            }
        }
    })
