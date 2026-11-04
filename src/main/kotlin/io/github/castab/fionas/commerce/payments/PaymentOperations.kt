package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/** Plain operation functions for the executable HTTP contract and its rendering-only composition. */
class PaymentOperations(
    val access: (InquiryId) -> StaffPaymentAccess,
    val manage: (InquiryId, PaymentAccessAction, UserId) -> StaffPaymentAccess,
    val summary: (UUID, PaymentBearer) -> CustomerPaymentSummary,
    val checkout: (CheckoutCommand) -> CheckoutRedirect,
    val webhook: (String, String) -> Unit,
    val attempts: (InquiryId) -> List<StripeAttempt>,
    val recover: (InquiryId, UUID) -> CheckoutRedirect,
)
