// Local HTTP smoke test: Invoice, two partial payments, partial refund, final settlement.
// Requires a running Fiona application and an already seeded Offerings catalog.

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

  async function payAndAllocate(amount) {
    const payment = (await request("POST", "/payments", {
      authenticated: true,
      body: { amount, currency: documentCurrency, method: "OTHER" },
      expectedStatus: 201,
    })).data;
    checkId(payment?.paymentId, "payment id");
    checkAmount(payment.amount, amount, "payment amount");
    check(payment.currency === documentCurrency && payment.method === "OTHER", "payment currency and method");
    check(typeof payment.receivedAt === "string" && payment.receivedAt.length > 0, "payment receivedAt");
    const allocation = (await request("POST", `/payments/${payment.paymentId}/allocations`, {
      authenticated: true,
      body: { documentId, documentVersion, amount },
      expectedStatus: 201,
    })).data;
    checkId(allocation?.allocationId, "allocation id");
    check(allocation.paymentId === payment.paymentId && allocation.documentId === documentId &&
      allocation.documentVersion === documentVersion, "allocation must identify the exact Invoice and payment");
    checkAmount(allocation.amount, amount, "allocation amount");
    check(allocation.currency === documentCurrency, "allocation currency");
    check(typeof allocation.allocatedAt === "string" && allocation.allocatedAt.length > 0, "allocation time");
    console.log(`Received ${money(amount, documentCurrency)} as payment ${payment.paymentId}; allocated ${allocation.allocationId}`);
    return { payment, allocation };
  }

  console.log("\nPayment 1");
  await payAndAllocate("200.00");
  const afterFirst = await currentInvoice(20000n, 20000n);
  console.log(`Invoice balance: ${money(afterFirst.reconciliation.balance, documentCurrency)}`);
  console.log("\nPayment 2");
  const second = await payAndAllocate("150.00");
  const afterSecond = await currentInvoice(35000n, 35000n);
  console.log(`Invoice balance: ${money(afterSecond.reconciliation.balance, documentCurrency)}`);

  const refund = (await request("POST", `/payments/${second.payment.paymentId}/refunds`, {
    authenticated: true,
    body: {
      amount: "50.00", currency: documentCurrency, method: "OTHER",
      allocations: [{ paymentAllocationId: second.allocation.allocationId, amount: "50.00" }],
    },
    expectedStatus: 201,
  })).data;
  checkId(refund?.refundId, "refund id");
  check(refund.paymentId === second.payment.paymentId && refund.method === "OTHER" &&
    refund.currency === documentCurrency, "refund payment, method, and currency");
  checkAmount(refund.amount, "50.00", "refund amount");
  check(typeof refund.refundedAt === "string" && refund.refundedAt.length > 0, "refund time");
  check(refund.allocations?.length === 1, "refund must unwind exactly one allocation");
  checkId(refund.allocations[0].refundAllocationId, "refund allocation id");
  check(refund.allocations[0].paymentAllocationId === second.allocation.allocationId,
    "refund must unwind the second payment's allocation");
  checkAmount(refund.allocations[0].amount, "50.00", "refund allocation amount");
  const expectedPayment = {
    paymentAmount: "150.00", totalRefunded: "50.00", netReceived: "100.00",
    grossAllocated: "150.00", refundAllocations: "50.00", netAllocated: "100.00", unallocated: "0.00",
  };
  check(refund.reconciliation?.currency === documentCurrency, "refund reconciliation currency");
  for (const [field, amount] of Object.entries(expectedPayment)) {
    checkAmount(refund.reconciliation[field], amount, `refund reconciliation ${field}`);
  }
  console.log("\nRefund");
  console.log(`Refunded ${money("50.00", documentCurrency)} from payment ${second.payment.paymentId}`);
  console.log(`Unwound allocation ${second.allocation.allocationId}`);

  const reopened = await currentInvoice(35000n, 30000n);
  console.log(`Net applied: ${money(reopened.reconciliation.netApplied, documentCurrency)}`);
  console.log(`Invoice balance: ${money(reopened.reconciliation.balance, documentCurrency)}`);
  const finalAmount = reopened.reconciliation.balance;
  check(cents(finalAmount, "reopened balance") > 0n, "reopened balance must be positive");
  console.log("\nFinal payment");
  await payAndAllocate(finalAmount);
  const final = await currentInvoice(totalCents + 5000n, totalCents);
  checkAmount(final.reconciliation.balance, "0.00", "final balance");

  console.log("\nFinal reconciliation");
  console.log("--------------------------------");
  console.log(`Invoice total       ${money(documentTotal, documentCurrency)}`);
  console.log(`Gross allocated     ${money(final.reconciliation.grossAllocated, documentCurrency)}`);
  console.log(`Refunded/unwound    ${money("50.00", documentCurrency)}`);
  console.log(`Net applied         ${money(final.reconciliation.netApplied, documentCurrency)}`);
  console.log(`Balance             ${money(final.reconciliation.balance, documentCurrency)}`);
  console.log("--------------------------------");
  console.log("Result: PAID IN FULL");
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
