package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.time.Clock
import java.util.UUID

/** The existing estimate entry point, backed by first-snapshot document creation. */
class CreateInquiryEstimate(
    transactor: Transactor,
    inquiries: InquiryRepository,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    pricingSources: FinancialDocumentPricingRepository,
    pricing: FionasPricing,
    clock: Clock,
    newDocumentId: () -> UUID = UUID::randomUUID,
) {
    private val create =
        CreateInquiryFinancialDocument(
            transactor,
            inquiries,
            ledger,
            associations,
            pricingSources,
            pricing,
            clock,
            newDocumentId,
        )

    operator fun invoke(
        inquiryId: InquiryId,
        inputs: FionasPricingInputs,
    ): InquiryFinancialDocument =
        create(CreateInquiryFinancialDocument.Command(inquiryId, CreateInquiryFinancialDocument.Stage.ESTIMATE, inputs))
}
