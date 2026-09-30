// Fresh local DB → bootstrap Fiona's acceptance catalog → preview the canonical $681.25 estimate.
// Run with FIONAS_ADMIN_PASSWORD and FIONAS_UI_API_KEY set; this script does not load .env files or seed on application startup.
// --capitalize-toppings updates labels on an existing local catalog, preserving keys and other properties.

const EXPECTED_TOTAL = "681.25";
const TOPPING_OPTIONS = [
  { key: "sprinkles", displayName: "Sprinkles" },
  { key: "oreos", displayName: "Oreos" },
  { key: "strawberries", displayName: "Strawberries" },
  { key: "brownies", displayName: "Brownies" },
  { key: "gummy-bears", displayName: "Gummy Bears" },
  { key: "cookie-dough", displayName: "Cookie Dough" },
];

const baseUrl = process.env.FIONAS_BASE_URL ?? "http://localhost:8080";
const origin = process.env.FIONAS_ORIGIN ?? "http://localhost:8080";
const username = process.env.FIONAS_ADMIN_USERNAME ?? "admin";
const password = process.env.FIONAS_ADMIN_PASSWORD;
const uiApiKey = process.env.FIONAS_UI_API_KEY;
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
  if ((method === "GET" && path === "/inquiry-form") ||
      (method === "POST" && ["/estimate-preview", "/inquiries"].includes(path))) {
    if (!uiApiKey) throw new Error("Set FIONAS_UI_API_KEY to the local backend's configured UI key");
    headers.set("Authorization", `Bearer ${uiApiKey}`);
  }

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

function revisionFrom(data, path) {
  if (!Number.isInteger(data?.revision) || data.revision < 1) {
    throw new Error(`${path} did not return a catalog revision`);
  }
  return data.revision;
}

function usdCents(value) {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(String(value));
  if (!match || /[1-9]/.test((match[2] ?? "").slice(2))) {
    throw new Error(`Invalid USD amount from Fiona: ${value}`);
  }
  return BigInt(match[1]) * 100n + BigInt(((match[2] ?? "") + "00").slice(0, 2));
}

function dollars(value) {
  const cents = usdCents(value);
  return `$${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length > 1 || (args.length === 1 && args[0] !== "--capitalize-toppings")) {
    throw new Error("Usage: node scripts/setup-local-commerce.mjs [--capitalize-toppings]");
  }
  if (!uiApiKey) throw new Error("Set FIONAS_UI_API_KEY to the local backend's configured UI key");
  if (!password?.trim()) {
    throw new Error(
      "Set FIONAS_ADMIN_PASSWORD to your local bootstrap admin password, then run: " +
        "FIONAS_ADMIN_PASSWORD='your-local-password' node scripts/setup-local-commerce.mjs",
    );
  }
  if (Number(process.versions.node.split(".")[0]) < 20 || typeof fetch !== "function") {
    throw new Error("This script needs Node.js 20 or newer with built-in fetch");
  }

  const login = await request("POST", "/auth/login", {
    body: { username, password },
    expectedStatus: 204,
  });
  const setCookies = login.response.headers.getSetCookie?.() ?? [login.response.headers.get("Set-Cookie")].filter(Boolean);
  sessionCookie = setCookies
    .map((header) => header.match(/(?:^|,\s*)(__Host-fionas_session=[^;,\s]+)/)?.[1])
    .find(Boolean);
  if (!sessionCookie) throw new Error("POST /auth/login succeeded but returned no __Host-fionas_session cookie");
  console.log("Logged in as local administrator");

  if (args[0] === "--capitalize-toppings") {
    const catalog = (await request("GET", "/offering-catalog", { expectedStatus: 200 })).data;
    const category = catalog.categories.find(({ key }) => key === "topping");
    const updates = TOPPING_OPTIONS.map(({ key, displayName }) => {
      const offering = category?.offerings.find((option) => option.key === key);
      if (!offering) throw new Error(`Active topping ${key} was not found; no labels were changed`);
      return { offering, displayName };
    });
    let revision = catalog.revision;
    for (const { offering, displayName } of updates) {
      if (offering.displayName === displayName) continue;
      const path = `/offering-catalog/offerings/${encodeURIComponent(offering.key)}`;
      const updated = await request("PUT", path, {
        body: {
          expectedRevision: revision, category: category.key, displayName,
          ...(offering.description == null ? {} : { description: offering.description }),
          ...(offering.price == null ? {} : { price: offering.price }),
        },
        authenticated: true, expectedStatus: 200,
      });
      revision = revisionFrom(updated.data, path);
      console.log(`${offering.key}: ${displayName} (revision ${revision})`);
    }
    const latest = (await request("GET", "/offering-catalog", { expectedStatus: 200 })).data;
    const latestToppings = latest.categories.find(({ key }) => key === "topping").offerings;
    for (const { offering, displayName } of updates) {
      const current = latestToppings.find(({ key }) => key === offering.key);
      if (!current || current.displayName !== displayName || current.description !== offering.description ||
          JSON.stringify(current.price) !== JSON.stringify(offering.price)) {
        throw new Error(`Verification failed for topping ${offering.key}; reload the catalog before continuing`);
      }
    }
    console.log(`Topping labels verified at revision ${latest.revision}`);
    return;
  }

  let catalogRevision;
  try {
    const created = await request("POST", "/offering-catalog", { authenticated: true, expectedStatus: 201 });
    catalogRevision = revisionFrom(created.data, "/offering-catalog");
  } catch (error) {
    if (error instanceof HttpFailure && error.status === 409) {
      throw new Error(
        "Fiona's Offerings catalog already exists. This script seeds a fresh/disposable local database " +
          "only; existing catalogs are managed through revisioned mutations. " +
          "Reset the local database/volume before running it again.",
      );
    }
    throw error;
  }
  console.log(`Catalog initialized: revision ${catalogRevision}`);

  const categories = [
    { key: "soft-serve-flavor", displayName: "Soft Serve", minimumSelections: 1, maximumSelections: 2 },
    { key: "topping", displayName: "Toppings", minimumSelections: 4, maximumSelections: 6 },
    { key: "cone-option", displayName: "Cones", minimumSelections: 1, maximumSelections: 1 },
  ];
  for (const category of categories) {
    const path = "/offering-catalog/categories";
    const created = await request("POST", path, {
      body: { expectedRevision: catalogRevision, ...category }, authenticated: true, expectedStatus: 201,
    });
    catalogRevision = revisionFrom(created.data, path);
    console.log(`Added category ${category.key}: revision ${catalogRevision}`);
  }

  const toppings = TOPPING_OPTIONS.map(({ key }) => key);
  const offerings = [
    { key: "vanilla", category: "soft-serve-flavor", displayName: "Vanilla" },
    { key: "chocolate", category: "soft-serve-flavor", displayName: "Chocolate" },
    {
      key: "horchata", category: "soft-serve-flavor", displayName: "Horchata", description: "Premium soft serve",
      price: { kind: "PER_QUANTITY", amount: "0.50", currency: "USD", dimension: "guest" },
    },
    ...TOPPING_OPTIONS.map((topping) => ({ ...topping, category: "topping" })),
    { key: "cup", category: "cone-option", displayName: "Cups" },
    {
      key: "waffle-cone", category: "cone-option", displayName: "Waffle cones",
      price: { kind: "PER_QUANTITY", amount: "0.75", currency: "USD", dimension: "guest" },
    },
  ];
  for (const offering of offerings) {
    const path = "/offering-catalog/offerings";
    const created = await request("POST", path, {
      body: { expectedRevision: catalogRevision, ...offering }, authenticated: true, expectedStatus: 201,
    });
    catalogRevision = revisionFrom(created.data, path);
    console.log(`Added offering ${offering.key}: revision ${catalogRevision}`);
  }

  const catalog = await request("GET", "/offering-catalog", { expectedStatus: 200 });
  if (catalog.data?.revision !== catalogRevision) {
    throw new Error(`Catalog read returned revision ${catalog.data?.revision}, expected ${catalogRevision}`);
  }
  console.log(`\nCatalog ready at revision ${catalogRevision}`);

  const previewInputs = {
    catalogRevision,
    guestCount: 75,
    guestCountIsMinimum: false,
    durationMinutes: 120,
    selections: [
      { category: "soft-serve-flavor", offerings: ["vanilla", "horchata"] },
      { category: "topping", offerings: toppings },
      { category: "cone-option", offerings: ["waffle-cone"] },
    ],
  };
  const preview = (await request("POST", "/estimate-preview", { body: previewInputs, expectedStatus: 200 })).data;
  if (!preview || preview.currency !== "USD" || !Array.isArray(preview.lines)) {
    throw new Error(`Estimate preview returned an unexpected body: ${JSON.stringify(preview)}`);
  }
  console.log("\nEstimate preview");
  console.log("------------------------------------------------");
  for (const line of preview.lines) {
    console.log(`${line.description.padEnd(32)} ${dollars(line.total).padStart(14)}`);
  }
  console.log("------------------------------------------------");
  console.log(`${"Subtotal".padEnd(32)} ${dollars(preview.subtotal).padStart(14)}`);
  console.log(`${"Tax".padEnd(32)} ${dollars(preview.taxAmount).padStart(14)}`);
  console.log(`${"Total".padEnd(32)} ${dollars(preview.total).padStart(14)}`);
  console.log(`\nExpected total: ${dollars(EXPECTED_TOTAL)}`);
  if (usdCents(preview.total) !== usdCents(EXPECTED_TOTAL)) {
    console.error(`Unexpected estimate preview: ${JSON.stringify(preview, null, 2)}`);
    throw new Error(`Estimate total was ${dollars(preview.total)}, expected ${dollars(EXPECTED_TOTAL)}`);
  }
  console.log("Result: OK");
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
