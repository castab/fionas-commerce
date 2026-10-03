// Replace the target endpoint's active catalog with the categories and offerings defined below.
// Run directly and enter the connection details when prompted; no environment variables are needed.

import { createInterface } from "node:readline";
import { Writable } from "node:stream";
import { isDeepStrictEqual } from "node:util";

const TOPPING_OPTIONS = [
  { key: "sprinkles", displayName: "Sprinkles" },
  { key: "oreos", displayName: "Oreos" },
  { key: "strawberries", displayName: "Strawberries" },
  { key: "brownies", displayName: "Brownies" },
  { key: "gummy-bears", displayName: "Gummy Bears" },
  { key: "cookie-dough", displayName: "Cookie Dough" },
  { key: "chopped-peanuts", displayName: "Chopped Peanuts", infoNote: "Contains peanuts" },
];
const HAND_SCOOPED_OPTIONS = [
  { key: "hand-scooped-chocolate-chip", displayName: "Chocolate Chip" },
  { key: "hand-scooped-chocolate", displayName: "Chocolate" },
  { key: "hand-scooped-vanilla-bean", displayName: "Vanilla Bean" },
  { key: "hand-scooped-strawberry", displayName: "Strawberry" },
  { key: "hand-scooped-butter-pecan", displayName: "Butter Pecan", availability: "UNAVAILABLE", infoNote: "Contains tree nuts" },
  { key: "hand-scooped-mint-chip", displayName: "Mint Chip" },
  {
    key: "hand-scooped-new-york-cheesecake", displayName: "New York Cheesecake", availability: "UNAVAILABLE",
    statusNote: "Back on the menu this fall!", badge: "Returning soon",
  },
];

let baseUrl;
let origin;
let sessionCookie;

async function connectionDetails() {
  const terminal = Boolean(process.stdin.isTTY && process.stdout.isTTY);
  let hidden = false;
  const output = new Writable({
    write(chunk, encoding, callback) {
      if (!hidden) process.stdout.write(chunk, encoding);
      callback();
    },
  });
  const reader = createInterface({ input: process.stdin, output, terminal, historySize: 0, crlfDelay: Infinity });
  // Stop echoing between prompts, including when several answers are pasted at once.
  reader.on("line", () => { hidden = true; });
  // The iterator queues lines, so piped input works even if all three answers arrive together.
  const lines = reader[Symbol.asyncIterator]();
  async function ask(label, secret = false) {
    hidden = false;
    reader.setPrompt(label);
    reader.prompt();
    hidden = secret && terminal;
    try {
      const answer = await lines.next();
      if (answer.done) throw new Error("Input ended before all connection details were supplied");
      return answer.value;
    } finally {
      if (secret && terminal) process.stdout.write("\n");
      hidden = true;
    }
  }
  try {
    const baseUrl = (await ask("Base URL [http://localhost:8080]: ")).trim() || "http://localhost:8080";
    let url;
    try {
      url = new URL(baseUrl);
    } catch {
      throw new Error("Base URL must be a valid http:// or https:// URL");
    }
    if (!["http:", "https:"].includes(url.protocol)) throw new Error("Base URL must use http:// or https://");
    const username = (await ask("Admin username: ")).trim();
    if (!username) throw new Error("Admin username must not be blank");
    const password = await ask("Admin password: ", true);
    if (!password.trim()) throw new Error("Admin password must not be blank");
    return { baseUrl, origin: url.origin, username, password };
  } finally {
    reader.close();
    output.end();
  }
}

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

function revisionFrom(data, path) {
  if (!Number.isInteger(data?.revision) || data.revision < 1) {
    throw new Error(`${path} did not return a catalog revision`);
  }
  return data.revision;
}

// The API may omit absent optional properties; compare their values rather than JSON property order.
function offeringDefinition(offering) {
  return {
    key: offering.key, category: offering.category, displayName: offering.displayName,
    description: offering.description ?? null, price: offering.price ?? null,
    selectionState: offering.selectionState, availability: offering.availability,
    badge: offering.badge ?? null, statusNote: offering.statusNote ?? null, infoNote: offering.infoNote ?? null,
  };
}

function categoryDefinition(category, offerings) {
  return {
    key: category.key, displayName: category.displayName, description: category.description ?? null,
    minimumSelections: category.minimumSelections ?? 0, maximumSelections: category.maximumSelections ?? null,
    offerings: offerings.map(offeringDefinition),
  };
}

async function replaceCatalog(categories, offerings) {
  let current;
  try {
    current = (await request("GET", "/offering-catalog", { expectedStatus: 200 })).data;
  } catch (error) {
    if (!(error instanceof HttpFailure && error.status === 404)) throw error;
    current = (await request("POST", "/offering-catalog", { authenticated: true, expectedStatus: 201 })).data;
    console.log(`Catalog initialized: revision ${revisionFrom(current, "/offering-catalog")}`);
  }
  let revision = revisionFrom(current, "/offering-catalog");
  const retiredCategories = (await request("GET", "/offering-catalog/retired/categories", {
    authenticated: true, expectedStatus: 200,
  })).data;
  const retiredOfferings = (await request("GET", "/offering-catalog/retired/offerings", {
    authenticated: true, expectedStatus: 200,
  })).data;
  if (retiredCategories.revision !== revision || retiredOfferings.revision !== revision) {
    throw new Error("Catalog changed while reading its identities; rerun the script with the current catalog");
  }
  const knownCategories = new Set([
    ...current.categories.map(({ key }) => key),
    ...retiredCategories.categories.map(({ category }) => category.key),
  ]);
  const activeKeys = current.categories.flatMap(({ offerings }) => offerings.map(({ key }) => key));
  const knownOfferings = new Set([
    ...activeKeys, ...retiredOfferings.offerings.map(({ offering }) => offering.key),
  ]);

  // Each mutation commits separately. Thread its returned revision; never retry a conflicting write.
  if (activeKeys.length > 0) {
    const path = "/offering-catalog/offerings/retire";
    const retired = await request("POST", path, {
      body: { expectedRevision: revision, keys: activeKeys }, authenticated: true, expectedStatus: 200,
    });
    revision = revisionFrom(retired.data, path);
    console.log(`Retired ${activeKeys.length} offerings: revision ${revision}`);
  }
  for (const category of current.categories) {
    const path = `/offering-catalog/categories/${encodeURIComponent(category.key)}?expectedRevision=${revision}`;
    const retired = await request("DELETE", path, { authenticated: true, expectedStatus: 200 });
    revision = revisionFrom(retired.data, path);
    console.log(`Retired category ${category.key}: revision ${revision}`);
  }
  for (const { key, ...definition } of categories) {
    const restoring = knownCategories.has(key);
    const path = restoring ? `/offering-catalog/categories/${encodeURIComponent(key)}/restore` : "/offering-catalog/categories";
    const written = await request("POST", path, {
      body: { expectedRevision: revision, ...(restoring ? {} : { key }), ...definition },
      authenticated: true, expectedStatus: restoring ? 200 : 201,
    });
    revision = revisionFrom(written.data, path);
    console.log(`${restoring ? "Restored" : "Added"} category ${key}: revision ${revision}`);
  }

  // Add and restore use different endpoints. Contiguous batches preserve the script's order
  // even when new and previously used keys alternate within a category.
  const batches = [];
  for (const offering of offerings) {
    const restoring = knownOfferings.has(offering.key);
    const last = batches.at(-1);
    if (last?.restoring === restoring) last.offerings.push(offering);
    else batches.push({ restoring, offerings: [offering] });
  }
  for (const batch of batches) {
    const path = batch.restoring ? "/offering-catalog/offerings/restore" : "/offering-catalog/offerings";
    const written = await request("POST", path, {
      body: { expectedRevision: revision, offerings: batch.offerings },
      authenticated: true, expectedStatus: batch.restoring ? 200 : 201,
    });
    revision = revisionFrom(written.data, path);
    console.log(`${batch.restoring ? "Restored" : "Added"} ${batch.offerings.length} offerings in one batch: revision ${revision}`);
  }

  const latest = (await request("GET", "/offering-catalog", { expectedStatus: 200 })).data;
  if (latest.catalogId !== current.catalogId || latest.revision !== revision ||
      !isDeepStrictEqual(
        latest.categories.map((category) => categoryDefinition(category, category.offerings)),
        categories.map((category) => categoryDefinition(category, offerings.filter((offering) => offering.category === category.key))),
      )) {
    throw new Error("Catalog verification failed; reload the catalog before continuing");
  }
  console.log(`\nCatalog verified at revision ${revision}`);
  console.log("Result: OK");
}

async function main() {
  if (process.argv.length > 2) {
    throw new Error("Usage: node scripts/replace-catalog.mjs");
  }
  if (Number(process.versions.node.split(".")[0]) < 20 || typeof fetch !== "function") {
    throw new Error("This script needs Node.js 20 or newer with built-in fetch");
  }

  const connection = await connectionDetails();
  baseUrl = connection.baseUrl;
  origin = connection.origin;
  const login = await request("POST", "/auth/login", {
    body: { username: connection.username, password: connection.password },
    expectedStatus: 204,
  });
  const setCookies = login.response.headers.getSetCookie?.() ?? [login.response.headers.get("Set-Cookie")].filter(Boolean);
  sessionCookie = setCookies
    .map((header) => header.match(/(?:^|,\s*)(__Host-fionas_session=[^;,\s]+)/)?.[1])
    .find(Boolean);
  if (!sessionCookie) throw new Error("POST /auth/login succeeded but returned no __Host-fionas_session cookie");
  console.log(`Logged in as administrator at ${baseUrl}`);

  const categories = [
    { key: "soft-serve-flavor", displayName: "Soft Serve", minimumSelections: 1, maximumSelections: 2 },
    { key: "hand-scooped-flavor", displayName: "Hand-Scooped flavors", minimumSelections: 4, maximumSelections: 4 },
    { key: "topping", displayName: "Toppings", minimumSelections: 4, maximumSelections: 6 },
    { key: "cone-option", displayName: "Cones", minimumSelections: 1, maximumSelections: 1 },
  ];
  const offerings = [
    { key: "vanilla", category: "soft-serve-flavor", displayName: "Vanilla" },
    { key: "chocolate", category: "soft-serve-flavor", displayName: "Chocolate" },
    {
      key: "horchata", category: "soft-serve-flavor", displayName: "Horchata", description: "Premium soft serve",
      price: { kind: "PER_QUANTITY", amount: "0.50", currency: "USD", dimension: "guest" },
    },
    ...HAND_SCOOPED_OPTIONS.map((flavor) => ({ ...flavor, category: "hand-scooped-flavor" })),
    ...TOPPING_OPTIONS.map((topping) => ({ ...topping, category: "topping" })),
    { key: "cup", category: "cone-option", displayName: "Cups" },
    {
      key: "waffle-cone", category: "cone-option", displayName: "Waffle cones",
      price: { kind: "PER_QUANTITY", amount: "0.75", currency: "USD", dimension: "guest" },
    },
  ].map((offering) => ({ selectionState: "ENABLED", availability: "AVAILABLE", ...offering }));
  await replaceCatalog(categories, offerings);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
