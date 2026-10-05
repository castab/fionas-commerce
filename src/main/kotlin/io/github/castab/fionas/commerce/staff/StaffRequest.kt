package io.github.castab.fionas.commerce.staff

import io.github.castab.fionas.commerce.financial.InquiryFinancialDocument
import io.github.castab.fionas.commerce.inquiry.InquiryDetails

/** A derived inquiry detail and its current canonical INITIAL_ESTIMATE lineage, never persisted. */
data class StaffRequest(
    val inquiry: InquiryDetails,
    val financial: InquiryFinancialDocument,
) {
    init {
        check(financial.inquiryId == inquiry.inquiry.id) { "Staff request canonical financial ownership disagrees with inquiry" }
        check(financial.latest.document.id == inquiry.lifecycle.documentId) {
            "Staff request canonical financial identity disagrees with lifecycle"
        }
    }
}
