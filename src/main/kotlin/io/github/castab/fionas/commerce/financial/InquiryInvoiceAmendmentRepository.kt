package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId

/** Immutable Invoice business history, ordered by per-inquiry business revision. */
interface InquiryInvoiceAmendmentRepository {
    fun append(
        transaction: Transaction,
        amendment: InquiryInvoiceAmendment,
    )

    fun latest(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryInvoiceAmendment?

    fun history(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): List<InquiryInvoiceAmendment>
}
