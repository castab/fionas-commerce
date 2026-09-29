// Local HTTP smoke test: direct Invoice v1, standalone payment, full allocation, zero balance.
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
      message: "Local developer smoke test: direct invoice paid in full.",
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

  const payment = (await request("POST", "/payments", {
    authenticated: true,
    body: { amount: documentTotal, currency: documentCurrency, method: "OTHER" },
    expectedStatus: 201,
  })).data;
  checkId(payment?.paymentId, "payment id");
  checkAmount(payment.amount, documentTotal, "payment amount");
  check(payment.currency === documentCurrency, "payment currency must equal document currency");
  check(payment.method === "OTHER", "payment method must be OTHER");
  check(typeof payment.receivedAt === "string" && payment.receivedAt.length > 0, "payment receivedAt must be present");
  const paymentId = payment.paymentId;
  console.log(`Recorded payment ${paymentId}: ${money(payment.amount, payment.currency)}`);

  const allocation = (await request("POST", `/payments/${paymentId}/allocations`, {
    authenticated: true,
    body: { documentId, documentVersion, amount: documentTotal },
    expectedStatus: 201,
  })).data;
  checkId(allocation?.allocationId, "allocation id");
  check(allocation.paymentId === paymentId, "allocation must reference the recorded payment");
  check(allocation.documentId === documentId, "allocation must reference the created Invoice");
  check(allocation.documentVersion === documentVersion, "allocation must reference the exact Invoice version");
  checkAmount(allocation.amount, documentTotal, "allocation amount");
  check(allocation.currency === documentCurrency, "allocation currency must equal document currency");
  check(typeof allocation.allocatedAt === "string" && allocation.allocatedAt.length > 0, "allocation allocatedAt must be present");
  checkReconciliation(allocation.reconciliation, documentTotal, documentCurrency, true);
  console.log(`Allocated payment ${allocation.allocationId} to Invoice v${documentVersion}`);

  const current = (await request("GET", `/financial-documents/${documentId}`, {
    authenticated: true, expectedStatus: 200,
  })).data;
  check(current?.id === documentId && current.inquiryId === inquiryId, "current Invoice identity must be unchanged");
  check(current.stage === "INVOICE" && current.version === documentVersion && current.previousVersion == null,
    "current Invoice must still be the original v1 snapshot");
  checkAmount(current.total, documentTotal, "current Invoice total");
  check(current.currency === documentCurrency, "current Invoice currency must be unchanged");
  check(JSON.stringify(current.pricing) === JSON.stringify(document.pricing), "Invoice pricing inputs must be unchanged");
  check(JSON.stringify(current.lines) === JSON.stringify(document.lines), "Invoice lines must be unchanged");
  check(current.subtotal === document.subtotal && current.taxAmount === document.taxAmount,
    "Invoice subtotal and tax must be unchanged");
  checkReconciliation(current.reconciliation, documentTotal, documentCurrency, true);

  console.log("\nReconciliation");
  console.log("--------------------------------");
  console.log(`Gross allocated  ${money(current.reconciliation.grossAllocated, documentCurrency)}`);
  console.log(`Net applied      ${money(current.reconciliation.netApplied, documentCurrency)}`);
  console.log(`Balance          ${money(current.reconciliation.balance, documentCurrency)}`);
  console.log("--------------------------------");
  console.log("Result: PAID IN FULL");
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
