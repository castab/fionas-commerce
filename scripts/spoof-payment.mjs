// Local HTTP smoke test: Invoice, two partial payments, partial refund, final settlement.
// Requires a running Fiona application and an already seeded Offerings catalog.
//
// It also proves that payment facts survive a reload: every mutation response below is
// validated and then dropped. The refund is prepared only from
// GET /financial-documents/{documentId}/payments, and its own facts are verified by reading
// that again, knowing nothing but the document id the operator is looking at.
// Standalone receipts are also rediscovered through /payments/unapplied, including after partial allocation.

const baseUrl = process.env.FIONAS_BASE_URL ?? "http://localhost:8080";
const origin = process.env.FIONAS_ORIGIN ?? "http://localhost:8080";
const username = process.env.FIONAS_ADMIN_USERNAME ?? "admin";
const password = process.env.FIONAS_ADMIN_PASSWORD;
let sessionCookie;

class HttpFailure extends Error {
  constructor(method, path, status, body) {
    super(`${method} ${path} returned ${status}${body ? `: ${body}` : ""}`);
    this.status = status;
  }
}

function urlFor(path) {
  return new URL(path.replace(/^\//, ""), `${baseUrl.replace(/\/+$/, "")}/`);
}

async function request(method, path, { body, authenticated = false, expectedStatus } = {}) {
  const headers = new Headers();
  if (body !== undefined) headers.set("Content-Type", "application/json");
  if (authenticated || path === "/auth/login") headers.set("Origin", origin);
  if (authenticated) headers.set("Cookie", sessionCookie);

  let response;
  try {
    response = await fetch(urlFor(path), {
      method,
      headers,
      signal: AbortSignal.timeout(10_000),
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  } catch (error) {
    throw new Error(`${method} ${path} could not reach ${baseUrl}: ${error.cause?.message ?? error.message}`);
  }

  const text = await response.text();
  let data;
  if (text && response.headers.get("Content-Type")?.includes("json")) {
    try {
      data = JSON.parse(text);
    } catch {
      throw new Error(`${method} ${path} returned invalid JSON (${response.status}): ${text}`);
    }
  }
  if (!response.ok || (expectedStatus !== undefined && response.status !== expectedStatus)) {
    throw new HttpFailure(method, path, response.status, text);
  }
  return { response, data };
}

function check(condition, description) {
  if (!condition) throw new Error(`Unexpected Fiona response: ${description}`);
}

function checkId(value, description) {
  check(typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value),
    `${description} must be a UUID`);
}

function decimal(value, description) {
  const match = typeof value === "string" && /^(\d+)(?:\.(\d+))?$/.exec(value);
  check(match, `${description} must be an exact decimal string`);
  const whole = BigInt(match[1]).toString();
  const fraction = (match[2] ?? "").replace(/0+$/, "");
  return fraction ? `${whole}.${fraction}` : whole;
}

function checkAmount(actual, expected, description) {
  check(decimal(actual, description) === decimal(expected, "expected amount"),
    `${description} must equal ${expected}; received ${actual}`);
}

function cents(value, description) {
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value);
  check(match, `${description} must be a nonnegative exact amount with at most two decimal places`);
  return BigInt(match[1]) * 100n + BigInt((match[2] ?? "").padEnd(2, "0"));
}

function fromCents(value) {
  check(value >= 0n, "computed amount must not be negative");
  return `${value / 100n}.${(value % 100n).toString().padStart(2, "0")}`;
}

function checkReconciliation(value, total, currency, paid) {
  check(value && typeof value === "object", "reconciliation must be present");
  check(value.currency === currency, `reconciliation currency must be ${currency}`);
  checkAmount(value.grossAllocated, paid ? total : "0", "grossAllocated");
  checkAmount(value.netApplied, paid ? total : "0", "netApplied");
  checkAmount(value.balance, paid ? "0" : total, "balance");
}

function money(amount, currency) {
  return `${currency === "USD" ? "$" : ""}${amount} ${currency}`;
}

async function main() {
  if (!password?.trim()) {
    throw new Error("Set FIONAS_ADMIN_PASSWORD to your local bootstrap admin password, then run: node scripts/spoof-payment.mjs");
  }
  if (Number(process.versions.node.split(".")[0]) < 20 || typeof fetch !== "function") {
    throw new Error("This script needs Node.js 20 or newer with built-in fetch");
  }

  const login = await request("POST", "/auth/login", {
    body: { username, password }, expectedStatus: 204,
  });
  const setCookies = login.response.headers.getSetCookie?.() ?? [login.response.headers.get("Set-Cookie")].filter(Boolean);
  sessionCookie = setCookies
    .map((header) => header.match(/(?:^|,\s*)(__Host-fionas_session=[^;,\s]+)/)?.[1])
    .find(Boolean);
  if (!sessionCookie) throw new Error("POST /auth/login succeeded but returned no __Host-fionas_session cookie");
  console.log("Logged in as local administrator");

  let catalog;
  try {
    catalog = (await request("GET", "/offering-catalog", { expectedStatus: 200 })).data;
  } catch (error) {
    if (error instanceof HttpFailure && error.status === 404) {
      throw new Error("No Fiona Offerings catalog exists. Run node scripts/setup-local-commerce.mjs first.");
    }
    throw error;
  }
  check(Number.isInteger(catalog?.revision) && catalog.revision >= 1, "catalog revision must be a positive integer");
  const catalogRevision = catalog.revision;
  console.log(`Using catalog revision ${catalogRevision}`);

  const inquiry = (await request("POST", "/inquiries", {
    body: {
      name: "Local Payment Smoke Test",
      email: "payment-smoke@example.com",
      message: "Local developer smoke test: partial payments, refund, and final settlement.",
    },
    expectedStatus: 201,
  })).data;
  checkId(inquiry?.id, "inquiry id");
  const inquiryId = inquiry.id;
  console.log(`Created inquiry: ${inquiryId}`);

  const document = (await request("POST", `/inquiries/${inquiryId}/financial-documents`, {
    authenticated: true,
    body: {
      stage: "INVOICE",
      catalogRevision,
      guestCount: 75,
      guestCountIsMinimum: false,
      durationMinutes: 120,
      selections: [
        { category: "soft-serve-flavor", offerings: ["vanilla", "horchata"] },
        { category: "topping", offerings: ["sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough"] },
        { category: "cone-option", offerings: ["waffle-cone"] },
      ],
    },
    expectedStatus: 201,
  })).data;
  checkId(document?.id, "document id");
  check(document.inquiryId === inquiryId, "document must belong to the new inquiry");
  check(document.stage === "INVOICE", "document stage must be INVOICE");
  check(document.version === 1 && document.previousVersion == null, "Invoice must begin at v1 with no predecessor");
  check(document.pricing?.catalogRevision === catalogRevision, "Invoice must use the selected catalog revision");
  check(typeof document.currency === "string" && document.currency.length > 0, "document currency must be present");
  check(decimal(document.total, "document total") !== "0", "document total must be positive");
  check(Array.isArray(document.lines) && document.lines.length > 0, "document lines must be server-derived");
  checkReconciliation(document.reconciliation, document.total, document.currency, false);
  const { id: documentId, version: documentVersion, total: documentTotal, currency: documentCurrency } = document;
  console.log(`Created Invoice ${documentId} v${documentVersion}: ${money(documentTotal, documentCurrency)}`);

  const totalCents = cents(documentTotal, "Invoice total");
  check(totalCents > 35000n, "Invoice total must exceed 350.00 for this partial-payment walkthrough");

  async function currentInvoice(grossCents, netCents) {
    const current = (await request("GET", `/financial-documents/${documentId}`, {
      authenticated: true, expectedStatus: 200,
    })).data;
    check(current?.id === documentId && current.inquiryId === inquiryId, "Invoice identity must be unchanged");
    check(current.stage === "INVOICE" && current.version === documentVersion && current.previousVersion == null,
      "Invoice must remain the original v1 snapshot");
    checkAmount(current.total, documentTotal, "Invoice total");
    check(current.currency === documentCurrency, "Invoice currency must be unchanged");
    check(JSON.stringify(current.pricing) === JSON.stringify(document.pricing), "pricing inputs must be unchanged");
    check(JSON.stringify(current.lines) === JSON.stringify(document.lines), "Invoice lines must be unchanged");
    check(current.subtotal === document.subtotal && current.taxAmount === document.taxAmount,
      "Invoice subtotal and tax must be unchanged");
    check(current.reconciliation?.currency === documentCurrency, "reconciliation currency must match");
    checkAmount(current.reconciliation.grossAllocated, fromCents(grossCents), "grossAllocated");
    checkAmount(current.reconciliation.netApplied, fromCents(netCents), "netApplied");
    checkAmount(current.reconciliation.balance, fromCents(totalCents - netCents), "balance");
    return current;
  }

  async function unappliedPayment(paymentId) {
    const queue = (await request("GET", "/payments/unapplied", {
      authenticated: true, expectedStatus: 200,
    })).data;
    check(Array.isArray(queue?.payments), "unapplied queue must contain complete payment histories");
    return queue.payments.find((history) => history.payment.paymentId === paymentId);
  }

  async function payAndAllocate(amount, demonstratePartial = false) {
    const payment = (await request("POST", "/payments", {
      authenticated: true,
      body: { amount, currency: documentCurrency, method: "OTHER" },
      expectedStatus: 201,
    })).data;
    checkId(payment?.paymentId, "payment id");
    checkAmount(payment.amount, amount, "payment amount");
    check(payment.currency === documentCurrency && payment.method === "OTHER", "payment currency and method");
    check(typeof payment.receivedAt === "string" && payment.receivedAt.length > 0, "payment receivedAt");
    const rediscovered = await unappliedPayment(payment.paymentId);
    check(rediscovered, "a standalone payment must be rediscoverable before allocation");
    checkAmount(rediscovered.reconciliation.unallocated, amount, "standalone available amount");
    const allocatedAmount = demonstratePartial ? fromCents(cents(amount, "payment amount") / 2n) : amount;
    const allocation = (await request("POST", `/payments/${payment.paymentId}/allocations`, {
      authenticated: true,
      body: { documentId, documentVersion, amount: allocatedAmount },
      expectedStatus: 201,
    })).data;
    checkId(allocation?.allocationId, "allocation id");
    check(allocation.paymentId === payment.paymentId && allocation.documentId === documentId &&
      allocation.documentVersion === documentVersion, "allocation must identify the exact Invoice and payment");
    checkAmount(allocation.amount, allocatedAmount, "allocation amount");
    check(allocation.currency === documentCurrency, "allocation currency");
    check(typeof allocation.allocatedAt === "string" && allocation.allocatedAt.length > 0, "allocation time");
    if (demonstratePartial) {
      const remaining = fromCents(cents(amount, "payment amount") - cents(allocatedAmount, "first allocation"));
      const partial = await unappliedPayment(payment.paymentId);
      check(partial, "partial allocation must leave the payment discoverable");
      checkAmount(partial.reconciliation.unallocated, remaining, "partly allocated available amount");
      console.log(`Rediscovered partial payment ${payment.paymentId}: ${money(remaining, documentCurrency)} available`);
      await request("POST", `/payments/${payment.paymentId}/allocations`, {
        authenticated: true, body: { documentId, documentVersion, amount: remaining }, expectedStatus: 201,
      });
    }
    check(!(await unappliedPayment(payment.paymentId)), "a fully allocated payment must leave the unapplied queue");
    console.log(`Received ${money(amount, documentCurrency)} as payment ${payment.paymentId}; allocated ${allocation.allocationId}`);
    // The responses are deliberately not returned: later steps rediscover these facts.
  }

  // The staff payment panel's read. Checks every history is complete and self-consistent:
  // each refund allocation names a refund and an allocation the same history carries.
  async function documentPayments() {
    const read = (await request("GET", `/financial-documents/${documentId}/payments`, {
      authenticated: true, expectedStatus: 200,
    })).data;
    check(read?.documentId === documentId && Array.isArray(read.payments), "payment histories must name the document");
    for (const history of read.payments) {
      const { payment, allocations, refunds, refundAllocations, reconciliation } = history;
      checkId(payment?.paymentId, "rediscovered payment id");
      check(payment.currency === documentCurrency, "rediscovered payment currency");
      check([allocations, refunds, refundAllocations].every(Array.isArray), "history lists must be present");
      check(allocations.some((it) => it.documentId === documentId), "a listed payment must have been allocated here");
      for (const it of allocations) {
        checkId(it.allocationId, "rediscovered allocation id");
        check(it.paymentId === payment.paymentId, "allocation must belong to its payment");
      }
      for (const it of refunds) {
        checkId(it.refundId, "rediscovered refund id");
        check(it.paymentId === payment.paymentId, "refund must belong to its payment");
      }
      for (const it of refundAllocations) {
        checkId(it.refundAllocationId, "rediscovered refund allocation id");
        check(refunds.some((refund) => refund.refundId === it.refundId), "refund allocation must name a listed refund");
        check(allocations.some((allocation) => allocation.allocationId === it.paymentAllocationId),
          "refund allocation must name a listed allocation");
      }
      check(reconciliation?.currency === documentCurrency, "payment reconciliation currency");
    }
    return read.payments;
  }

  console.log("\nPayment 1");
  await payAndAllocate("200.00", true);
  const afterFirst = await currentInvoice(20000n, 20000n);
  console.log(`Invoice balance: ${money(afterFirst.reconciliation.balance, documentCurrency)}`);
  console.log("\nPayment 2");
  await payAndAllocate("150.00");
  const afterSecond = await currentInvoice(35000n, 35000n);
  console.log(`Invoice balance: ${money(afterSecond.reconciliation.balance, documentCurrency)}`);

  // Reload: the payment responses are gone. Rediscover what a refund needs from the document alone.
  console.log("\nReload: GET /financial-documents/{documentId}/payments");
  const beforeRefund = await documentPayments();
  check(beforeRefund.length === 2, `the Invoice must list its 2 payments; listed ${beforeRefund.length}`);
  for (const history of beforeRefund) {
    check(history.refunds.length === 0 && history.refundAllocations.length === 0, "no refund exists yet");
    checkAmount(history.reconciliation.unallocated, "0", "a fully allocated payment's unallocated amount");
  }
  const refundable = beforeRefund.find((history) => decimal(history.payment.amount, "payment amount") === "150");
  check(refundable, "the 150.00 payment must be rediscovered");
  const paymentId = refundable.payment.paymentId;
  const allocationToRefund = refundable.allocations.find((it) => it.documentId === documentId);
  check(allocationToRefund?.documentVersion === documentVersion, "the allocation must name the exact Invoice version");
  checkAmount(allocationToRefund.amount, "150.00", "rediscovered allocation amount");
  const paymentAllocationId = allocationToRefund.allocationId;
  console.log(`Rediscovered payment ${paymentId} and its allocation ${paymentAllocationId}`);

  const appliedRefundAmount = "50.00";
  const appliedRefundCents = cents(appliedRefundAmount, "applied refund amount");
  const refund = (await request("POST", `/payments/${paymentId}/refunds`, {
    authenticated: true,
    body: {
      amount: appliedRefundAmount, currency: documentCurrency, method: "OTHER",
      allocations: [{ paymentAllocationId, amount: appliedRefundAmount }],
    },
    expectedStatus: 201,
  })).data;
  checkId(refund?.refundId, "refund id");
  check(refund.paymentId === paymentId && refund.method === "OTHER" &&
    refund.currency === documentCurrency, "refund payment, method, and currency");
  checkAmount(refund.amount, appliedRefundAmount, "refund amount");
  check(typeof refund.refundedAt === "string" && refund.refundedAt.length > 0, "refund time");
  check(refund.allocations?.length === 1, "refund must unwind exactly one allocation");
  checkId(refund.allocations[0].refundAllocationId, "refund allocation id");
  check(refund.allocations[0].paymentAllocationId === paymentAllocationId,
    "refund must unwind the rediscovered allocation");
  checkAmount(refund.allocations[0].amount, appliedRefundAmount, "refund allocation amount");
  check(refund.allocations[0].currency === documentCurrency, "refund allocation currency");
  check(typeof refund.allocations[0].allocatedAt === "string" && refund.allocations[0].allocatedAt.length > 0,
    "refund allocation time");
  const expectedPayment = {
    paymentAmount: "150.00", totalRefunded: appliedRefundAmount, netReceived: "100.00",
    grossAllocated: "150.00", allocationReversals: "0.00", refundAllocations: appliedRefundAmount,
    netAllocated: "100.00", unallocated: "0.00",
  };
  check(refund.reconciliation?.currency === documentCurrency, "refund reconciliation currency");
  for (const [field, amount] of Object.entries(expectedPayment)) {
    checkAmount(refund.reconciliation[field], amount, `refund reconciliation ${field}`);
  }
  console.log("\nRefund");
  console.log(`Refunded ${money(appliedRefundAmount, documentCurrency)} from payment ${paymentId}`);
  console.log(`Unwound allocation ${paymentAllocationId}`);

  // Reload again: the refund response is gone too. Its facts come back from the document's payments.
  console.log("\nReload: GET /financial-documents/{documentId}/payments");
  const afterRefund = await documentPayments();
  check(afterRefund.length === 2, "a refund must not add or remove the Invoice's payments");
  const refunded = afterRefund.find((history) => history.payment.paymentId === paymentId);
  check(refunded, "the refunded payment must still be listed");
  check(refunded.refunds.length === 1, "the refunded payment must list its one refund");
  const [rediscoveredRefund] = refunded.refunds;
  checkAmount(rediscoveredRefund.amount, appliedRefundAmount, "rediscovered refund amount");
  check(rediscoveredRefund.method === "OTHER" && rediscoveredRefund.currency === documentCurrency,
    "rediscovered refund method and currency");
  check(refunded.refundAllocations.length === 1, "the refund must list its one unwind");
  const [rediscoveredUnwind] = refunded.refundAllocations;
  check(rediscoveredUnwind.refundId === rediscoveredRefund.refundId, "the unwind must name the rediscovered refund");
  check(rediscoveredUnwind.paymentAllocationId === paymentAllocationId, "the unwind must name the rediscovered allocation");
  checkAmount(rediscoveredUnwind.amount, appliedRefundAmount, "rediscovered unwind amount");
  for (const [field, amount] of Object.entries(expectedPayment)) {
    checkAmount(refunded.reconciliation[field], amount, `rediscovered reconciliation ${field}`);
  }
  const untouched = afterRefund.find((history) => history.payment.paymentId !== paymentId);
  check(untouched.refunds.length === 0, "the other payment must have no refund");
  console.log(`Rediscovered refund ${rediscoveredRefund.refundId} and its unwind ${rediscoveredUnwind.refundAllocationId}`);
  console.log(`Payment ${paymentId} net received: ${money(refunded.reconciliation.netReceived, documentCurrency)}`);

  const reopened = await currentInvoice(35000n, 30000n);
  console.log(`Net applied: ${money(reopened.reconciliation.netApplied, documentCurrency)}`);
  console.log(`Invoice balance: ${money(reopened.reconciliation.balance, documentCurrency)}`);
  const finalAmount = reopened.reconciliation.balance;
  check(cents(finalAmount, "reopened balance") > 0n, "reopened balance must be positive");
  console.log("\nFinal payment");
  await payAndAllocate(finalAmount);
  const final = await currentInvoice(totalCents + appliedRefundCents, totalCents);
  checkAmount(final.reconciliation.balance, "0.00", "final balance");
  check((await documentPayments()).length === 3, "the Invoice must list all 3 payments");

  console.log("\nFinal reconciliation");
  console.log("--------------------------------");
  console.log(`Invoice total       ${money(documentTotal, documentCurrency)}`);
  console.log(`Gross allocated     ${money(final.reconciliation.grossAllocated, documentCurrency)}`);
  console.log(`Refunded/unwound    ${money(appliedRefundAmount, documentCurrency)}`);
  console.log(`Net applied         ${money(final.reconciliation.netApplied, documentCurrency)}`);
  console.log(`Balance             ${money(final.reconciliation.balance, documentCurrency)}`);
  console.log("--------------------------------");
  console.log("Result: PAID IN FULL");
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
