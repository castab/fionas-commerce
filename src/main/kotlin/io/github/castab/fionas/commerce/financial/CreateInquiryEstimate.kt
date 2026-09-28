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

/**
 * Persists an estimate for an inquiry, priced by the server from commercial inputs alone.
 *
 * In one runtime transaction: the inquiry is loaded, the exact catalog revision the inputs
 * name is read and priced with Fiona's [pricing] (the same pricing as a preview), the
 * resulting lines become the first snapshot of a new commerce-runtime `Estimate` lineage
 * through the ledger's transaction-aware `create`, and Fiona records the inquiry's ownership
 * of that lineage and the inputs that priced its `v1`. If anything fails, nothing of it
 * remains: neither the commerce snapshot nor Fiona's association or pricing source.
 */
class CreateInquiryEstimate(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val pricingSources: FinancialDocumentPricingRepository,
    private val pricing: FionasPricing,
    private val clock: Clock,
    private val newDocumentId: () -> UUID = UUID::randomUUID,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    /** Fails with [CommerceFailure.NotFound] for an unknown inquiry or catalog revision, and as pricing rejects the inputs. */
    operator fun invoke(
        inquiryId: InquiryId,
        inputs: FionasPricingInputs,
    ): InquiryFinancialDocument {
        // PostgreSQL stores microseconds; truncating keeps what is returned equal to what is stored.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            inquiries.findById(transaction, inquiryId) ?: throw CommerceFailure.NotFound("Inquiry ${inquiryId.value} was not found")
            val lines = pricing.price(transaction, inputs).lineItems
            val estimate = ledger.create(transaction, FinancialDocument.Estimate.create(newDocumentId(), lines))
            associations.associate(transaction, InquiryDocumentAssociation(inquiryId, estimate.id, now))
            pricingSources.insert(transaction, estimate.reference, inputs)
            documents.describeLocked(transaction, inquiryId, estimate.id)
        }
    }
}
