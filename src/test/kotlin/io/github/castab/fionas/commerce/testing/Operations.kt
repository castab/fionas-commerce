package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.staff.ServiceId
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.FinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.EventType
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.InquirySubmissionKey
import io.github.castab.fionas.commerce.inquiry.InquirySubmissionRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquirySubmissionRepository
import io.github.castab.fionas.commerce.inquiry.RequestedService
import io.github.castab.fionas.commerce.inquiry.ZipCode
import java.time.Clock
import java.util.UUID

/** Fiona's materialization of authorized lines, as the composition root builds it. */
fun TestApplication.materialize(
    associations: InquiryFinancialDocumentRepository = JdbiInquiryFinancialDocumentRepository(),
    authorship: FinancialDocumentAuthorshipRepository = JdbiFinancialDocumentAuthorshipRepository(),
) = MaterializeInquiryFinancialDocument(context.financialLedger, associations, authorship)

/** [CreateInquiry] as the composition root builds it, with replaceable collaborators for failure specs. */
fun TestApplication.createInquiryOperation(
    customers: CustomerRepository = JdbiCustomerRepository(),
    inquiries: InquiryRepository = JdbiInquiryRepository(),
    submissions: InquirySubmissionRepository = JdbiInquirySubmissionRepository(),
    materialize: MaterializeInquiryFinancialDocument = materialize(),
    clock: Clock = testClock,
    newCustomerId: () -> CustomerId = { CustomerId(UUID.randomUUID()) },
    newInquiryId: () -> InquiryId = { InquiryId(UUID.randomUUID()) },
) = CreateInquiry(transactor, customers, inquiries, submissions, clock, materialize, newCustomerId, newInquiryId)

/** A public inquiry command submitted by the web server's SERVICE principal with [lines] it priced. */
fun TestApplication.inquiryCommand(
    email: String = "jane-${UUID.randomUUID()}@example.com",
    name: String = "Jane Doe",
    message: String? = "Ice cream for a birthday party",
    lines: List<TestLine> = acceptanceLines(),
    requested: RequestedService = requestedService(),
    key: String = UUID.randomUUID().toString(),
    submittedBy: ServiceId = web.id,
) = CreateInquiry.Command(
    CustomerName.of(name),
    Email.of(email),
    InquiryMessage.ofOptional(message),
    requested,
    lines.map { it.priced() },
    ZipCode("92626"),
    EventDate.of("2026-12-05"),
    EventType.BIRTHDAY,
    InquirySubmissionKey(key),
    submittedBy,
)
