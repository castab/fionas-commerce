package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.time.Clock
import java.util.UUID

/** Creates the first snapshot of an inquiry-owned lineage at the chosen stage. */
class CreateInquiryFinancialDocument(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    private val pricingSources: FinancialDocumentPricingRepository,
    private val pricing: FionasPricing,
    clock: Clock,
    newDocumentId: () -> UUID = UUID::randomUUID,
    private val materialize: MaterializeInquiryFinancialDocument =
        MaterializeInquiryFinancialDocument(ledger, associations, clock, newDocumentId),
) {
    enum class Stage { ESTIMATE, QUOTE, INVOICE }

    data class Command(
        val inquiryId: InquiryId,
        val stage: Stage,
        val inputs: FionasPricingInputs,
    )

    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    /** The ledger snapshot, inquiry association, and pricing source commit or roll back together. */
    operator fun invoke(command: Command): InquiryFinancialDocument =
        transactor.inTransaction { transaction ->
            inquiries.findById(transaction, command.inquiryId)
                ?: throw CommerceFailure.NotFound("Inquiry ${command.inquiryId.value} was not found")
            val lines = pricing.price(transaction, command.inputs).lineItems
            val created = materialize.create(transaction, command.inquiryId, command.stage, lines)
            pricingSources.insert(transaction, created.reference, command.inputs)
            documents.describeLocked(transaction, command.inquiryId, created.id)
        }
}
