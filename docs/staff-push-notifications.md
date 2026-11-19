# Staff Web Push notifications

Staff members enroll a browser or installed PWA, send themselves a test notification, and
(optionally) receive a push for every newly accepted catering inquiry. This document is the
operations guide and the `fionas-admin` integration contract. The architectural contract is in
[AGENTS.md](../AGENTS.md#staff-web-push-notifications).

## How it fits together

```text
HTTP (staff USER) ──► one transaction: notification job + delivery rows
                       + runtime outbox work item (producer fionas-commerce.webpush)
                       + notification.queued event (producer fionas-commerce.notifications)
                            │
StaffPushDispatcher ◄───────┘  runtime OutboxDispatcher: claims, leases, fencing, backoff
   │  tx A: recheck device + eligibility, record attempt token
   │  provider call (no transaction)
   │  tx B: delivery outcome + notification.push_accepted | push_failed event
   ▼
NotificationEventPublisher ──► JetStream <prefix>.notification.<event>.v1 (Nats-Msg-Id = event id)
```

New inquiries: `CreateInquiry` (HTTP, NATS intake and the recovery CLI) records one
`NEW_INQUIRY` job in the inquiry's own transaction, keyed by the inquiry id. A background
fan-out resolves enrolled devices of ACTIVE staff currently holding `fionas.inquiries.read`
(runtime authorization, read outside the business transaction), creates one delivery per
device, and publishes `notification.queued` once. Every dispatch rechecks the device and the
owner's eligibility first.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `FIONAS_PUSH_ENABLED` | `false` | Staff push routes, test sends and the Web Push dispatcher. Disabled: config answers `enabled: false`, other routes `503 push_notifications_disabled`, nothing is ever sent. |
| `FIONAS_PUSH_VAPID_PUBLIC_KEY` / `FIONAS_PUSH_VAPID_PRIVATE_KEY` | — | Required when enabled. Raw P-256 keys, base64url: 65-byte uncompressed public key, 32-byte private scalar. Startup verifies they form a pair. |
| `FIONAS_PUSH_VAPID_PUBLIC_KEY_FILE` / `FIONAS_PUSH_VAPID_PRIVATE_KEY_FILE` | — | Alternative to the values above: a mounted file holding the same base64url text. Exactly one form per key. |
| `FIONAS_PUSH_VAPID_SUBJECT` | — | Required. `mailto:` or `https:` contact sent to push services in the VAPID JWT. |
| `FIONAS_PUSH_ALLOWED_HOSTS` | built-in list | Comma-separated exact hosts or `*.suffix` wildcards. Default: `fcm.googleapis.com`, `*.push.services.mozilla.com`, `updates.push.services.mozilla.com`, `*.notify.windows.com`, `web.push.apple.com`, `*.push.apple.com`. |
| `FIONAS_PUSH_SEND_TIMEOUT_MS` | `8000` | Provider connect/request timeout, 1000–10000. |
| `FIONAS_PUSH_INQUIRY_NOTIFICATIONS_ENABLED` | `false` | The automatic new-inquiry trigger. Requires `FIONAS_PUSH_ENABLED=true` (startup fails otherwise). |
| `FIONAS_PUSH_INQUIRY_PATH` | `/requests/{inquiryId}` | Relative admin click destination for new-inquiry pushes. |
| `FIONAS_NOTIFICATION_EVENTS_ENABLED` | `false` | Publishes notification events to JetStream. Events are appended durably whenever push is enabled; this only composes the publisher. |
| `FIONAS_NOTIFICATION_EVENTS_SUBJECT_PREFIX` | `fionas.events` | Subjects are `<prefix>.notification.{queued,push_accepted,push_failed}.v1`. |
| `FIONAS_NOTIFICATION_EVENTS_STREAM` | `FIONAS_EVENTS` | Expected stream (`Nats-Expected-Stream`; only a PubAck from it confirms). |
| `FIONAS_NOTIFICATION_NATS_URL` (+ `_CREDS_FILE` \| `_USER`/`_PASSWORD` \| `_TOKEN`) | reuse `FIONAS_NATS_*` | Dedicated notification destination. Unset: the inquiry outbox settings are reused, with one shared connection, which requires `FIONAS_OUTBOX_PUBLISHER_ENABLED=true`. Set to the same servers and credentials: still one shared connection. |

Every `*_NATS_URL` (inquiry outbox, intake, conversations, notifications) accepts one URL or a
comma-separated server list of one scheme (`nats://` or `tls://`).

### VAPID keys

Generate once per environment, store as secrets, and never rotate casually: rotating
invalidates every browser subscription, and staff must re-enroll.

```bash
npx web-push generate-vapid-keys --json
```

`publicKey` becomes `FIONAS_PUSH_VAPID_PUBLIC_KEY`, `privateKey` becomes
`FIONAS_PUSH_VAPID_PRIVATE_KEY`. The public key is served to browsers by
`GET /staff/notifications/config`. The private key is never logged or rendered.

### NATS TLS

TLS trust and client certificates come from the JVM, not from Fiona settings. With a `tls://`
URL the client uses the JVM default `SSLContext`, and Fiona switches on server **hostname
verification** (jnats 2.26 does not verify hostnames by itself; Fiona sets `HTTPS` endpoint
identification on every NATS TLS socket). This also applies to the existing inquiry outbox,
intake and conversation connections that use `tls://`: their server certificates must name the
configured host (DNS SAN, or IP SAN for an IP address URL).

- **Public CA:** nothing to configure.
- **Private CA:** build one PKCS12 truststore containing the JDK `cacerts` **plus** your CA.
  Replacing the truststore with only the private CA breaks Stripe, Resend and the push services.

  ```bash
  cp "$JAVA_HOME/lib/security/cacerts" fionas-trust.p12
  keytool -importcert -noprompt -alias fionas-nats-ca -file nats-ca.pem -keystore fionas-trust.p12 -storepass changeit
  ```

- **mTLS:** a PKCS12 keystore with the client key and certificate chain. Note it is offered to
  any TLS server that requests a client certificate.

Mount the files and pass the standard properties, for example:

```bash
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/secrets/fionas-trust.p12 -Djavax.net.ssl.trustStorePassword=... -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.keyStore=/secrets/fionas-client.p12 -Djavax.net.ssl.keyStorePassword=... -Djavax.net.ssl.keyStoreType=PKCS12"
```

### JetStream stream and grants

The stream must capture the notification subjects. The existing `FIONAS_EVENTS` stream with
subject filter `fionas.events.>` already does with the default prefix. With another prefix, add
`<prefix>.notification.>` to the chosen stream. Commerce never creates or alters streams.

The publishing identity needs `publish` on `<prefix>.notification.>` (default
`fionas.events.notification.>`) and `subscribe` on `_INBOX.>`. When the notification events share
the inquiry outbox identity, add that grant to it.

### Rollout: test sends first, automatic notifications later

1. Deploy with `FIONAS_PUSH_ENABLED=true`, VAPID settings, and
   `FIONAS_PUSH_INQUIRY_NOTIFICATIONS_ENABLED=false`. Optionally `FIONAS_NOTIFICATION_EVENTS_ENABLED=true`.
2. Staff enroll devices in `fionas-admin` and send test notifications; verify display and the
   status/JetStream trace below.
3. Set `FIONAS_PUSH_INQUIRY_NOTIFICATIONS_ENABLED=true` and redeploy. Only inquiries accepted
   afterwards create jobs; nothing is backfilled.

Disabling push again stops the dispatcher; queued deliveries remain pending in the runtime
outbox (producer `fionas-commerce.webpush`) and resume, or fail as `delivery_expired`, when
re-enabled. Rolling back to a release without this feature leaves those rows untouched.

### Diagnostics

Logs never contain endpoints, keys, payloads or staff identities beyond ids:

- `event=staff_push_attempt delivery=<id> attempt=<n> result=<Accepted|Gone|Rejected|Retryable|Unknown|EndpointNotAllowed>`
- `event=staff_push_delivery_completed delivery=<id> state=<state> reason=<reason>`
- `event=staff_push_fanned_out notification=<id> deliveries=<n>`
- `event=nats_outbox_publish_unconfirmed name=notifications code=<PUBLISH_FAILED|STREAM_MISMATCH>`
- runtime backlog for producers `fionas-commerce.webpush` and `fionas-commerce.notifications`.

Tracing one notification in Postgres:

```sql
SELECT n.id, n.kind, d.id AS delivery, d.state, d.outcome_reason, d.attempts, d.provider_code, d.completed_at
FROM fionas.staff_notifications n JOIN fionas.staff_notification_deliveries d ON d.notification_id = n.id
WHERE n.id = '<notificationId>';

SELECT id, event_type, destination, published_at FROM commerce.outbox_events
WHERE producer_id = 'fionas-commerce.notifications' AND aggregate_id = '<notificationId>' ORDER BY aggregate_sequence;
```

In JetStream, the message id (`Nats-Msg-Id`) equals that `outbox_events.id`, and
`Fionas-Aggregate-Id` / `data.notificationId` equal the notification id:

```bash
nats stream get FIONAS_EVENTS --last-for fionas.events.notification.push_accepted.v1
```

## Delivery semantics

| Status | Meaning |
| --- | --- |
| `queued` | Durably queued; no provider answer yet. |
| `retrying` | A transient failure (`provider_unavailable`, `provider_throttled`, `transport_failure`) or an uncertain result (`provider_outcome_unknown`); another attempt follows. |
| `push_accepted` | The push service accepted the message (2xx). **Not proof of display**: the device may be offline or suppress it. |
| `push_failed` | Terminal: `subscription_expired` (404/410; the registration is retired), `provider_rejected` (other 4xx, or a redirect, which is never followed), `endpoint_not_allowed`, a transient reason that outlasted the window, `provider_outcome_unknown` (an attempt timed out or lost its response and acceptance was never confirmed), or `delivery_expired` (the window passed with no provider answer). |
| `skipped` | Not sent: `subscription_revoked` / `subscription_expired` (the device was removed or expired before dispatch) or `recipient_not_eligible` (owner disabled, or lost `fionas.inquiries.read`). No JetStream outcome event. |

- Delivery windows: 10 minutes for tests, 1 hour for new-inquiry pushes. The push `TTL` is the
  remaining window.
- Retries follow the runtime outbox's bounded backoff (5 s up to 5 min between attempts);
  `Retry-After` is honored as a minimum, and one that ends past the window fails immediately.
- **Duplicates:** a retry after an uncertain result can display the notification twice. Each
  request carries a `Topic` derived from the notification id so a push service can collapse an
  undelivered copy, and the payload's `notificationId` lets the service worker suppress a
  repeat. The notification id never changes across attempts.
- Removing a device prevents later dispatches. A request already handed to the push service may
  still arrive.
- A 404/410 retires only the registration version the attempt used; if the browser re-enrolled
  the same endpoint with new keys meanwhile, the newer registration stays active.

## `fionas-admin` integration contract

All routes require a staff session cookie (`__Host-fionas_session`), unsafe methods a trusted
`Origin`, and answer `Cache-Control: no-store`. A SERVICE token is `403`. Errors use the runtime
envelope `{"code": "...", "message": "..."}`. Devices and tests are private: another staff
member's ids are `404`, indistinguishable from unknown ids.

| Route | operationId | Success | Errors |
| --- | --- | --- | --- |
| `GET /staff/notifications/config` | `getStaffNotificationConfig` | `200` | 401, 403, 500 |
| `POST /staff/notifications/subscriptions` | `enrollStaffPushSubscription` | `201` new, `200` same endpoint | 400, 401, 403, 409 `push_endpoint_in_use`, 422 `validation_failed`, 500, 503 `push_notifications_disabled` |
| `GET /staff/notifications/subscriptions` | `listStaffPushSubscriptions` | `200` | 401, 403, 500, 503 |
| `DELETE /staff/notifications/subscriptions/{subscriptionId}` | `revokeStaffPushSubscription` | `204` (also when already removed) | 400, 401, 403, 404, 500, 503 |
| `POST /staff/notifications/test` (+ `Idempotency-Key`) | `sendStaffTestNotification` | `202` | 400, 401, 403, 404, 409 `IDEMPOTENCY_KEY_REUSED`, 500, 503 |
| `GET /staff/notifications/{notificationId}` | `getStaffTestNotification` | `200` | 400, 401, 403, 404, 500, 503 |

```http
GET /staff/notifications/config
200 {"enabled": true, "vapidPublicKey": "BOrJ5ZqX…"}        # disabled: {"enabled": false}

POST /staff/notifications/subscriptions
{"subscription": <PushSubscription.toJSON()>, "label": "Front desk iPad"}
201 {"subscriptionId": "6b1f…", "label": "Front desk iPad", "service": "APPLE", "state": "ACTIVE",
     "enrolledAt": "2026-11-18T17:02:11.204Z", "refreshedAt": "2026-11-18T17:02:11.204Z"}

GET /staff/notifications/subscriptions
200 {"devices": [ …same shape…, "state": "ACTIVE" | "EXPIRED", "expirationTime"? ]}

POST /staff/notifications/test        Idempotency-Key: <uuid per click>
{"subscriptionId": "6b1f…"}
202 {"notificationId": "1a5b…", "status": "queued"}

GET /staff/notifications/1a5b…
200 {"notificationId": "1a5b…", "kind": "test", "subscriptionId": "6b1f…", "status": "push_accepted",
     "attempts": 1, "queuedAt": "…", "lastAttemptAt": "…", "completedAt": "…", "providerCode": 201}
```

Absent optional properties are omitted. `failureReason` appears for `push_failed`, `skipped` and
while `retrying`.

### Enrollment

1. Load `GET /staff/notifications/config`; hide the feature when `enabled` is false.
2. On a user gesture: `Notification.requestPermission()`, then
   `registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: vapidPublicKey })`
   (convert base64url to a `Uint8Array`).
3. `POST` the subscription's `toJSON()` with an optional label; store the returned
   `subscriptionId` locally (for example IndexedDB) to mark "this device".
4. Re-enroll whenever `pushManager.getSubscription()` returns a subscription (on app load, and
   from the service worker's `pushsubscriptionchange`); the same endpoint keeps its id.

### Account switch and logout

An endpoint belongs to one staff member at a time. If enrollment answers
`409 push_endpoint_in_use`, the browser's subscription is still enrolled for another account:
call `subscription.unsubscribe()`, subscribe again (a fresh endpoint), and enroll it. On logout,
`DELETE` this device's `subscriptionId` first, then `unsubscribe()`, so the next user starts
clean. A remote removal (another device's `DELETE`) only stops server sends; that browser stays
subscribed locally until it re-enrolls or unsubscribes.

### Test button

Generate one `Idempotency-Key` per click and reuse it for retries of that click, including after a
timeout. The same key returns the same `notificationId`; poll
`GET /staff/notifications/{notificationId}` (for example every 2 s for up to a minute) until the
status is terminal.

### Service worker payload

Encrypted JSON, at most a few hundred bytes:

```json
{"version": 1, "notificationId": "1a5bd3fd-6a0a-4d2e-9a7c-3f8f0c2d9e41", "kind": "test",
 "title": "Fiona’s test notification", "body": "Push notifications are working on this device.", "url": null}
```

```json
{"version": 1, "notificationId": "…", "kind": "new_inquiry", "title": "New catering inquiry",
 "body": "Open Fiona’s admin to review it.", "url": "/requests/0a6e8577-e811-487f-aa5d-3d30b74b25bd"}
```

`url` is null or a path relative to the admin origin; never navigate to an absolute URL from a
payload. Use `notificationId` as the notification `tag` so a duplicate replaces rather than
stacks. Payloads never carry customer names, contact details or inquiry contents.

## Smoke-test checklist for `fionas-admin`

| Step | Verified in this PR | Needs live admin |
| --- | --- | --- |
| Log in as staff | Route contract and session/Origin enforcement (`StaffPushNotificationsSpec`) | Real browser session |
| Load push configuration | `enabled`/`vapidPublicKey`, disabled shape | Browser `applicationServerKey` conversion |
| Enroll the current browser | Validation, re-enrollment id stability, conflict, concurrent enrollment | Real `PushSubscription` from Chrome/Firefox/Safari/PWA |
| Send a test to its `subscriptionId` | `202`, idempotent replay, removal, isolation | Button wiring and key reuse |
| Confirm actual display on the device | — (a fake provider stands in) | Real push service delivery, service worker `showNotification` |
| Find the provider outcome in the status API | `push_accepted` / `push_failed` / `skipped` / `retrying` mapping, restart recovery | Real provider answer codes |
| Find it in JetStream by `notificationId` | Same `Nats-Msg-Id` after a broker outage, stream mismatch stays pending, one provider call (`StaffNotificationEventsIntegrationSpec`) | Deployed stream, grants and TLS settings |
