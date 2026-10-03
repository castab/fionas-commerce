# Commerce 0.0.21 rollout guide

This backend change consumes published commerce-runtime 0.0.21 (matching domain transitively)
and retains http4k 6.58.0.0 and Java 25. It does not release a Fiona version, publish, deploy,
implement frontend changes, or recreate any deployed database. Those actions are separately
scheduled. All existing databases are disposable; no conversion migration is provided.

## Compatibility prerequisites

Regenerate affected clients from the generated executable OpenAPI contract. The form's
`definitionVersion` is 11. Implement `CHIPS` for duration, soft serve flavors, hand-scooped
flavors, toppings, and cones/cups. Duration selects one `INTEGER_CHOICE` value; offering chips select one or several
keys as catalog `minSelections`/`maxSelections` require. Event type remains `SELECT`.
Preserve question order, keys, bindings, and requiredness.
Hand-scooped flavors follow soft serve in the existing service section and use
`offering:hand-scooped-flavor`, bound to `/pricingInputs/selections`. The local category
requires exactly four hand-scooped flavors alongside soft serve. Five of its seven flavors
are available; Butter Pecan and New York Cheesecake stay visible and unavailable, with their
nut/returning text. Toppings offer seven choices, including Chopped Peanuts, with limits
still four to six. Category constraints remain authoritative for each environment's catalog.

Render optional `badge`, `statusNote`, and `infoNote` for CHIPS. Catalog offering text passes
through the runtime offering payload unchanged. Fiona's code-owned duration and event-type
options currently use fixed null defaults; non-CHIPS options supply no text, with no endpoint
or API for editing code-owned option text. The fields remain on the shared option types;
there is no new CHIPS presentation wire shape. Supporting more controls/editing is deferred.
Absent text is omitted; supplied text is nonblank and is not trimmed. Availability is
independent: show enabled unavailable options but block selection; omit disabled options. A reassuring status note never makes an unavailable option selectable.

Use these batch administration contracts, with the last observed `expectedRevision`:

| Operation | Request | Success | Operation ID |
|---|---|---|---|
| POST `/offering-catalog/offerings` | `{expectedRevision, offerings: [...]}` | 201 `{revision, offerings: [...]}` | `fionasOfferingsAddOfferings` |
| PUT `/offering-catalog/offerings` | `{expectedRevision, offerings: [...]}` | 200 `{revision, offerings: [...]}` | `fionasOfferingsUpdateOfferings` |
| POST `/offering-catalog/offerings/retire` | `{expectedRevision, keys: [...]}` | 200 `{revision}` | `fionasOfferingsRetireOfferings` |
| POST `/offering-catalog/offerings/restore` | `{expectedRevision, offerings: [...]}` | 200 `{revision, offerings: [...]}` | `fionasOfferingsRestoreOfferings` |

Every offering item includes its key and explicit selection state/availability. Update/restore
are complete replacements: preserve fields you want to retain; omission clears optional text,
description, and price. Batches are nonempty, ordered, atomic, and advance one revision. Thread
the returned revision; never fetch latest to substitute a precondition or retry stale writes.
Item GET and category mutations remain, including category DELETE's query revision. Remove
historical revision reads and single-offering mutation clients/operation IDs. Retired discovery
returns the last representation and `lastSeenRevision`; it does not retrieve old catalogs.

Preview, public submission, staff creation at all stages, and change orders require current
pricing inputs. Stale input returns 409 `CATALOG_REVISION_STALE` with no-store; missing catalog
or future revision returns 404. Refresh/bypass cached form data and require review of changed
selections/prices before a new request. Staff pricing from an old inquiry also requires review.
Keep document-version conflicts distinct. Recorded documents and transitions need no catalog.

Use one non-secret idempotency key per logical visible inquiry submission, shared across
browser duplicate requests and backend retries/timeouts. Preserve it for unchanged retries.
A committed replay returns its original receipt after catalog edits. `IDEMPOTENCY_KEY_REUSED`
means changed intent under a committed key; `CATALOG_REVISION_STALE` means an uncommitted
command needs refresh/review. A rejected command commits neither claim nor business writes.

## Local disposable database reset

Stop Fiona before removing the repository's disposable Compose volume. From the repository:

```powershell
docker compose down --volumes
docker compose up -d
```

Start the upgraded application with the existing local database, bootstrap, Origin, and
service-token configuration. Check `/ready`, then run:

```powershell
node scripts/setup-local-commerce.mjs
```

Supply the existing `FIONAS_ADMIN_PASSWORD` environment variable; the script also accepts
`FIONAS_BASE_URL`, `FIONAS_ORIGIN`, and `FIONAS_ADMIN_USERNAME`. It logs in as the bootstrap
administrator, initializes revision 1, adds four categories, and adds all 19 offerings
in one batch at revision 6. It refuses an existing catalog. The acceptance preview selects
the first four available hand-scooped flavors and the original six toppings, totaling $681.25.
New offerings have no catalog surcharge; option text does not change availability automatically.
Runtime V11/V12 reject populated legacy catalog storage; V12 replaces
`commerce.offerings_snapshots` with `commerce.offerings_catalogs`. Fiona's migrations remain
unchanged through V11. Empty databases migrate normally through runtime V12.

For label-only upkeep, run `node scripts/setup-local-commerce.mjs --capitalize-toppings`.
It updates changed labels in one batch and verifies description, price, state, availability,
and all three text fields. Already-correct labels cause no update/revision.
All seven configured toppings must exist; missing options abort before mutation. Label-only
upkeep does not upgrade an older catalog with the new entries; enter them through revisioned APIs.

## Deployed stop-and-recreate cutover

Apply this sequence separately to each environment:

1. Complete frontend compatibility above, regenerate affected clients, and prepare matching artifacts/configuration.
2. Stop all old backend instances and customer submissions. Keep traffic stopped throughout provisioning and checks.
3. Recreate the explicitly selected disposable database and connect only the upgraded backend. Old/new backend versions must not share the new catalog schema.
4. Verify migration success (runtime V12, Fiona V11), backend version, health, and readiness.
5. Bootstrap the administrator and re-provision roles, service identities, credentials, and service-token configuration. A database reset removes prior identities and sessions too.
6. Enter the production catalog through its API; preserve observed revisions between mutations. The local acceptance script is only for fresh local/disposable setup.
7. Verify the compatible frontend and backend together: all five CHIPS questions, cardinality, option text and availability, batch administration, and stale-input review/retry handling.
8. Resume traffic only after the smoke checks pass.

Smoke checks: add multiple offerings in one batch and confirm one revision and order; read the
form and verify all five CHIPS hints/event SELECT and optional text; preview current inputs;
publish an update; confirm stale preview and new inquiry conflict with no-store and no partial
writes; refresh/review and succeed; replay the successful inquiry with its original key after
another publication; read/transition its recorded financial document. Exercise label updates
with notes and disabled/unavailable state and verify preservation and the no-op repeat.

Recovery is a stopped cutover too: stop traffic and all instances, select the backend version,
and provision a database compatible with that version before restarting it. Do not point the
old backend at the new schema or weaken runtime migration rejection. Because this is a
recreation cutover, prior environment data is discarded; no in-place rollback or conversion
is promised. Re-provision identities/catalog and repeat readiness/smoke checks before resuming.
