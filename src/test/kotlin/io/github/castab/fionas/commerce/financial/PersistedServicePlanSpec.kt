package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Fiona's persisted service-plan content: a strict, money-free historical representation. */
class PersistedServicePlanSpec :
    FunSpec({
        val document = UUID.fromString("5f0c6a7e-8c1d-4f63-9b2a-0d8e7f6a5b4c")
        val lineIds = (1L..4L).map { UUID(0, it) }
        val plan =
            InquiryServicePlan(
                inquiryId = InquiryId(UUID.randomUUID()),
                quote = FinancialDocumentReference(document, Version.of(3)),
                reviewedEstimate = FinancialDocumentReference(document, Version.INITIAL),
                basis = QuotePricingBasis.REPRICE_CONFIGURATION,
                catalogRevision = OfferingsRevision.of(7),
                context = FionasOfferingsContext(40, true, Duration.ofMinutes(150)),
                selections =
                    listOf(
                        ServicePlanCategory(
                            OfferingCategoryKey("soft-serve-flavor"),
                            "Soft Serve",
                            listOf(ServicePlanOffering(OfferingKey("vanilla"), "Vanilla", null)),
                        ),
                        ServicePlanCategory(
                            OfferingCategoryKey("cone-option"),
                            "Cones",
                            listOf(ServicePlanOffering(OfferingKey("waffle-cone"), "Waffle cones", "Fresh daily")),
                        ),
                    ),
                lines =
                    listOf(
                        ServicePlanLine(lineIds[0], ServicePlanLineOrigin.Generated(FionasChargeSource.BaseService), null),
                        ServicePlanLine(
                            lineIds[1],
                            ServicePlanLineOrigin.Generated(
                                FionasChargeSource.SelectedOffering(OfferingCategoryKey("cone-option"), OfferingKey("waffle-cone")),
                            ),
                            QuoteEditReason("Promotion"),
                        ),
                        ServicePlanLine(lineIds[2], ServicePlanLineOrigin.EstimateLine, QuoteEditReason("Negotiated")),
                        ServicePlanLine(
                            lineIds[3],
                            ServicePlanLineOrigin.Adjustment(QuoteAdjustmentKind.CREDIT, QuoteEditReason("Late arrival credit")),
                            null,
                        ),
                    ),
                approvedAt = Instant.parse("2026-10-05T17:00:00Z"),
                principalId = UserId(UUID.randomUUID()),
            )

        test("every content fact survives the persisted representation, which holds no money") {
            val json = encodeServicePlanContent(plan)
            val restored = restoreServicePlanContent("test", json)
            restored.context shouldBe plan.context
            restored.selections shouldBe plan.selections
            restored.lines shouldBe plan.lines
            Regex("(?i)amount|price|total|balance").containsMatchIn(json) shouldBe false
        }

        test("restoring is strict: unknown, missing or null-where-required properties, wrong types and invalid combinations fail") {
            val json = encodeServicePlanContent(plan)
            listOf(
                json.replaceFirst("{", """{"extra":1,"""),
                json.replace(""""guestCount":40,""", ""),
                json.replace(""""guestCount":40""", """"guestCount":"40""""),
                json.replace(""""guestCountIsMinimum":true""", """"guestCountIsMinimum":"true""""),
                json.replace(""","description":null""", ""),
                json.replace(""""displayName":"Vanilla"""", """"displayName":null"""),
                json.replace(""""displayName":"Vanilla"""", """"displayName":"  """"),
                json.replace(""""origin":"ESTIMATE_LINE"""", """"origin":"MANUAL""""),
                json.replace(""""origin":"ESTIMATE_LINE"""", """"origin":"GENERATED""""),
                json.replace(""""kind":"CREDIT"""", """"kind":"REFUND""""),
                json.replace(""""kind":"BASE_SERVICE","categoryKey":null""", """"kind":"BASE_SERVICE","categoryKey":"cone-option""""),
                json.replace(""""reason":"Late arrival credit"""", """"reason":"  """"),
                json.replace(lineIds[1].toString(), lineIds[0].toString()),
                json.replace(lineIds[1].toString(), "not-a-uuid"),
                json.replace(""""durationMinutes":150""", """"durationMinutes":0"""),
            ).forEach { corrupt ->
                (corrupt != json) shouldBe true
                shouldThrow<IllegalStateException> {
                    restoreServicePlanContent(
                        "the service plan of $document at v3",
                        corrupt,
                    )
                }.message!! shouldContain
                    "service plan"
            }
        }
    })
