package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Creates the first snapshot of an inquiry-owned lineage at the chosen stage. */
class CreateInquiryFinancialDocument(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val pricingSources: FinancialDocumentPricingRepository,
    private val pricing: FionasPricing,
    private val clock: Clock,
    private val newDocumentId: () -> UUID = UUID::randomUUID,
) {
    enum class Stage { ESTIMATE, QUOTE, INVOICE }

    data class Command(
        val inquiryId: InquiryId,
        val stage: Stage,
        val inputs: FionasPricingInputs,
    )

    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    /** The ledger snapshot, inquiry association, and pricing source commit or roll back together. */
    operator fun invoke(command: Command): InquiryFinancialDocument {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            inquiries.findById(transaction, command.inquiryId)
                ?: throw CommerceFailure.NotFound("Inquiry ${command.inquiryId.value} was not found")
            val lines = pricing.price(transaction, command.inputs).lineItems
            val id = newDocumentId()
            val first =
                when (command.stage) {
                    Stage.ESTIMATE -> FinancialDocument.Estimate.create(id, lines)
                    Stage.QUOTE -> FinancialDocument.Quote.create(id, lines)
                    Stage.INVOICE -> FinancialDocument.Invoice.create(id, lines)
                }
            val created = ledger.create(transaction, first)
            associations.associate(transaction, InquiryDocumentAssociation(command.inquiryId, created.id, now))
            pricingSources.insert(transaction, created.reference, command.inputs)
            documents.describeLocked(transaction, command.inquiryId, created.id)
        }
    }
}
