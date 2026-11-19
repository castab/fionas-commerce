package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.inquiry.IdempotencyKeyReused
import io.github.castab.fionas.commerce.notification.DeviceLabel
import io.github.castab.fionas.commerce.notification.PushEndpoint
import io.github.castab.fionas.commerce.notification.PushEndpointInUse
import io.github.castab.fionas.commerce.notification.StaffNotificationId
import io.github.castab.fionas.commerce.notification.StaffNotificationOperations
import io.github.castab.fionas.commerce.notification.StaffPushSubscription
import io.github.castab.fionas.commerce.notification.StaffPushSubscriptionId
import io.github.castab.fionas.commerce.notification.TestNotificationKey
import io.github.castab.fionas.commerce.notification.TestNotificationView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Header
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import org.http4k.lens.PathLens
import java.time.Instant
import java.util.UUID

@Serializable
data class StaffNotificationConfigResponse(
    @ApiProperty(description = "False when staff push notifications are disabled in this deployment; then no key is given.")
    val enabled: Boolean,
    @ApiProperty(
        description =
            "The VAPID public key for `pushManager.subscribe({ applicationServerKey })`: an uncompressed P-256 point, " +
                "base64url without padding. Present only when enabled.",
    )
    val vapidPublicKey: String? = null,
)

@Serializable
data class PushSubscriptionKeysRequest(
    @ApiProperty(description = "The subscription's P-256 public key, base64url (`PushSubscription.toJSON().keys.p256dh`).")
    val p256dh: String,
    @ApiProperty(description = "The subscription's 16-byte authentication secret, base64url (`keys.auth`).")
    val auth: String,
)

@Serializable
data class PushSubscriptionRequest(
    @ApiProperty(
        description =
            "The push service URL. Must be https on an allowed public push service (FCM, Mozilla, Windows, Apple) and at most " +
                "${PushEndpoint.MAX_LENGTH} characters. Never returned or logged.",
        maxLength = PushEndpoint.MAX_LENGTH,
    )
    val endpoint: String,
    @ApiProperty(description = "The browser's `expirationTime`: milliseconds since the epoch, or absent/null.")
    val expirationTime: Long? = null,
    val keys: PushSubscriptionKeysRequest,
)

@Serializable
data class EnrollPushSubscriptionRequest(
    @ApiProperty(description = "The browser's `PushSubscription.toJSON()`, unchanged.")
    val subscription: PushSubscriptionRequest,
    @ApiProperty(
        description = "Optional name for this device. Trimmed; blank means none.",
        maxLength = DeviceLabel.MAX_LENGTH,
    )
    val label: String? = null,
)

@Serializable
enum class PushDeviceStateResponse {
    /** Receives notifications. */
    ACTIVE,

    /** The push service reported the subscription gone; re-enroll from the browser to receive again. */
    EXPIRED,
}

@Serializable
data class PushDeviceResponse(
    @ApiProperty(format = "uuid", description = "Stable device id; re-enrolling the same browser subscription keeps it.")
    val subscriptionId: String,
    val label: String? = null,
    @ApiProperty(description = "The push service that issued the subscription: FCM, MOZILLA, WINDOWS, APPLE or OTHER.")
    val service: String,
    val state: PushDeviceStateResponse,
    @ApiProperty(format = "date-time") val enrolledAt: String,
    @ApiProperty(format = "date-time", description = "The latest re-enrollment of the same subscription.")
    val refreshedAt: String,
    @ApiProperty(format = "date-time", description = "The browser-reported expiry, when it gave one.")
    val expirationTime: String? = null,
)

@Serializable
data class PushDeviceListResponse(
    val devices: List<PushDeviceResponse>,
)

@Serializable
data class SendTestNotificationRequest(
    @ApiProperty(format = "uuid", description = "One of the caller's active devices.")
    val subscriptionId: String,
)

@Serializable
enum class StaffNotificationStatusResponse {
    /** Durably queued; not yet answered by the push service. */
    @SerialName("queued")
    QUEUED,

    /** A transient failure or uncertain result; another attempt will follow within the delivery window. */
    @SerialName("retrying")
    RETRYING,

    /** The push service accepted the message. Not proof of display: the device may be offline or suppress it. */
    @SerialName("push_accepted")
    PUSH_ACCEPTED,

    /** Terminal failure; see failureReason. */
    @SerialName("push_failed")
    PUSH_FAILED,

    /** Not sent: the device was revoked or expired, or its owner was no longer eligible, before dispatch. */
    @SerialName("skipped")
    SKIPPED,
}

@Serializable
data class TestNotificationQueuedResponse(
    @ApiProperty(format = "uuid", description = "Stable across retries of the same Idempotency-Key.")
    val notificationId: String,
    @ApiProperty(description = "`queued` for a new send; a replay reports the original notification's current status.")
    val status: StaffNotificationStatusResponse,
)

@Serializable
data class TestNotificationResponse(
    @ApiProperty(format = "uuid") val notificationId: String,
    @ApiProperty(description = "Always `test` on this route.")
    val kind: String,
    @ApiProperty(format = "uuid", description = "The device the test targeted.")
    val subscriptionId: String,
    val status: StaffNotificationStatusResponse,
    @ApiProperty(description = "Provider attempts made so far.")
    val attempts: Int,
    @ApiProperty(format = "date-time") val queuedAt: String,
    @ApiProperty(format = "date-time") val lastAttemptAt: String? = null,
    @ApiProperty(format = "date-time") val completedAt: String? = null,
    @ApiProperty(
        description =
            "Machine-readable reason, present for push_failed and skipped, and the latest reason while retrying: " +
                "subscription_expired, subscription_revoked, recipient_not_eligible, provider_rejected, provider_unavailable, " +
                "provider_throttled, transport_failure, endpoint_not_allowed, provider_outcome_unknown, delivery_expired.",
    )
    val failureReason: String? = null,
    @ApiProperty(description = "The push service's HTTP status for the latest answered attempt.")
    val providerCode: Int? = null,
)

private val notificationsTag =
    Tag("Staff notifications", "The authenticated staff member's Web Push devices, test sends and their delivery outcomes.")
private val configBody = jsonBody(StaffNotificationConfigResponse.serializer())
private val enrollBody = jsonBody(EnrollPushSubscriptionRequest.serializer())
private val deviceBody = jsonBody(PushDeviceResponse.serializer())
private val devicesBody = jsonBody(PushDeviceListResponse.serializer())
private val testBody = jsonBody(SendTestNotificationRequest.serializer())
private val queuedBody = jsonBody(TestNotificationQueuedResponse.serializer())
private val statusBody = jsonBody(TestNotificationResponse.serializer())
private val notificationErrors = jsonBody(ErrorResponse.serializer())
private val pushDisabled = ErrorResponse(PUSH_NOTIFICATIONS_DISABLED, "Staff push notifications are disabled")
private val subscriptionIdPath = Path.of("subscriptionId", "The device's id.", mapOf("schema" to mapOf("format" to "uuid")))
private val notificationIdPath = Path.of("notificationId", "The test notification's id.", mapOf("schema" to mapOf("format" to "uuid")))
private val testKeyHeader =
    Header.required(
        "Idempotency-Key",
        "Opaque test identity, 1–128 ASCII letters, digits, underscores or hyphens. Retrying with the same key and device " +
            "returns the original notificationId; one key never names two devices. Not a credential.",
        mapOf(
            "schema" to
                mapOf("minLength" to 1, "maxLength" to TestNotificationKey.MAX_LENGTH, "pattern" to TestNotificationKey.PATTERN),
        ),
    )

internal const val PUSH_NOTIFICATIONS_DISABLED = "push_notifications_disabled"
internal const val PUSH_ENDPOINT_IN_USE = "push_endpoint_in_use"
internal const val STAFF_NOTIFIER_REQUIRED = "Push notification devices belong to an authenticated staff user"

private val exampleDevice =
    PushDeviceResponse(
        "6b1f0f8e-5a8e-4a9a-9d84-1f0a3c8f2b10",
        "Front desk iPad",
        "APPLE",
        PushDeviceStateResponse.ACTIVE,
        "2026-11-18T17:02:11.204Z",
        "2026-11-18T17:02:11.204Z",
    )
private const val EXAMPLE_NOTIFICATION = "1a5bd3fd-6a0a-4d2e-9a7c-3f8f0c2d9e41"

private val noStore =
    Filter { next -> { request -> next(request).header("Cache-Control", "no-store") } }

private fun enabled(operations: StaffNotificationOperations?) =
    Filter { next ->
        { request ->
            if (operations == null) {
                Response(Status.SERVICE_UNAVAILABLE).with(notificationErrors of pushDisabled)
            } else {
                next(request)
            }
        }
    }

/** Fiona-local conflict codes in the runtime ErrorResponse envelope. */
private val notificationConflicts =
    Filter { next ->
        { request ->
            try {
                next(request)
            } catch (failure: CommerceFailure.Conflict) {
                val code =
                    when (failure.cause) {
                        is PushEndpointInUse -> PUSH_ENDPOINT_IN_USE
                        is IdempotencyKeyReused -> IDEMPOTENCY_KEY_REUSED
                        else -> throw failure
                    }
                Response(Status.CONFLICT).with(notificationErrors of ErrorResponse(code, failure.message!!))
            }
        }
    }

/** Push devices are personal: any authenticated staff USER manages only their own; a SERVICE is `403`. */
private fun AccessControl.staffNotifier(): Filter = authenticated().then(requireStaffNotifier)

private fun RouteMetaDsl.staffNotificationMetadata(
    id: String,
    title: String,
    details: String,
    unsafe: Boolean = false,
) {
    operationId = id
    summary = title
    tags += notificationsTag
    description =
        "$details Requires an authenticated staff USER (any permissions; a SERVICE is 403). Devices and notifications are " +
        "private to their owner: another staff member's ids are indistinguishable from unknown ids. Cache-Control: no-store."
    principalAuthentication()
    security = staffSessionSecurity
    returningError(
        ErrorCategory.FORBIDDEN,
        "the authenticated principal is a SERVICE, not a staff user" + if (unsafe) ", $UNTRUSTED_ORIGIN." else ".",
        STAFF_NOTIFIER_REQUIRED,
    )
    returningError(ErrorCategory.INTERNAL_FAILURE, "An unexpected failure; its cause is never described.", INTERNAL_FAILURE)
}

private fun RouteMetaDsl.disabledResponse() =
    returning(
        Status.SERVICE_UNAVAILABLE,
        notificationErrors to pushDisabled,
        "`$PUSH_NOTIFICATIONS_DISABLED`: staff push notifications are not enabled in this deployment.",
    )

internal fun staffNotificationRoutes(
    operations: StaffNotificationOperations?,
    access: AccessControl,
): List<ContractRoute> =
    listOf(
        "/staff/notifications/config" meta {
            staffNotificationMetadata(
                "getStaffNotificationConfig",
                "Read staff push notification setup",
                "Whether Web Push is enabled and, when it is, the VAPID public key the browser needs to subscribe. " +
                    "Answers 200 even when the feature is disabled, so the admin app can load it before the enrollment gesture.",
            )
            returning(Status.OK, configBody to StaffNotificationConfigResponse(true, "BOrJ5ZqXQ8d6…base64url-uncompressed-P-256"))
        } bindContract Method.GET to
            noStore.then(access.staffNotifier()).then { _: Request ->
                Response(Status.OK).with(
                    configBody of StaffNotificationConfigResponse(operations != null, operations?.applicationServerKey),
                )
            },
        "/staff/notifications/subscriptions" meta {
            staffNotificationMetadata(
                "enrollStaffPushSubscription",
                "Enroll this browser for staff push notifications",
                "Registers the browser's PushSubscription for the authenticated staff member. Re-enrolling the same active " +
                    "endpoint keeps its subscriptionId (200) and refreshes its keys, expiry and label; a new endpoint is 201. " +
                    "An endpoint actively enrolled for another staff account is `409 $PUSH_ENDPOINT_IN_USE` without disclosing " +
                    "that account: unsubscribe in the browser and subscribe again to obtain a fresh endpoint, then enroll it.",
                unsafe = true,
            )
            preFlightExtraction = PreFlightExtraction.None
            receiving(
                enrollBody to
                    EnrollPushSubscriptionRequest(
                        PushSubscriptionRequest(
                            "https://web.push.apple.com/QGuQyavXutnMH8v7…",
                            null,
                            PushSubscriptionKeysRequest(
                                "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM",
                                "tBHItJI5svbpez7KI4CCXg",
                            ),
                        ),
                        "Front desk iPad",
                    ),
            )
            returning(Status.CREATED, deviceBody to exampleDevice, "A new device enrollment.")
            returning(Status.OK, deviceBody to exampleDevice, "The same active endpoint was already enrolled for the caller; same id.")
            returningError(ErrorCategory.MALFORMED_REQUEST, "The body is not the documented JSON.", "Malformed request")
            returningError(
                ErrorCategory.VALIDATION_FAILED,
                "the endpoint is not https on an allowed push service, a key is malformed, or the label is invalid.",
                "Push endpoint is not a supported push service",
            )
            returning(
                Status.CONFLICT,
                notificationErrors to ErrorResponse(PUSH_ENDPOINT_IN_USE, "This browser is enrolled for another staff account"),
                "`$PUSH_ENDPOINT_IN_USE`: the endpoint is actively enrolled for another staff account (owner not disclosed).",
            )
            disabledResponse()
        } bindContract Method.POST to
            noStore.then(access.staffNotifier()).then(enabled(operations)).then(notificationConflicts).then { request: Request ->
                val input = enrollBody(request)
                val expiration = input.subscription.expirationTime?.let(Instant::ofEpochMilli)
                val enrollment =
                    checkNotNull(operations).enroll(
                        staffUser(request),
                        input.subscription.endpoint,
                        input.subscription.keys.p256dh,
                        input.subscription.keys.auth,
                        expiration,
                        input.label,
                    )
                Response(if (enrollment.created) Status.CREATED else Status.OK)
                    .with(deviceBody of enrollment.subscription.response())
            },
        "/staff/notifications/subscriptions" meta {
            staffNotificationMetadata(
                "listStaffPushSubscriptions",
                "List my push devices",
                "The authenticated staff member's devices: ACTIVE ones and those their push service expired (EXPIRED). " +
                    "Revoked devices are not listed. Endpoints and keys are never returned.",
            )
            returning(Status.OK, devicesBody to PushDeviceListResponse(listOf(exampleDevice)))
            disabledResponse()
        } bindContract Method.GET to
            noStore.then(access.staffNotifier()).then(enabled(operations)).then { request: Request ->
                Response(Status.OK).with(
                    devicesBody of PushDeviceListResponse(checkNotNull(operations).list(staffUser(request)).map { it.response() }),
                )
            },
        "/staff/notifications/subscriptions" / subscriptionIdPath meta {
            staffNotificationMetadata(
                "revokeStaffPushSubscription",
                "Remove a push device",
                "Revokes one of the caller's devices server-side, from this browser or remotely. Later dispatches to it are " +
                    "skipped; a request already handed to the push service may still arrive. Repeating the removal is a " +
                    "harmless 204. Unknown ids and other staff members' devices are 404.",
                unsafe = true,
            )
            returning(Status.NO_CONTENT to "The device is revoked (or already was).")
            returningError(ErrorCategory.MALFORMED_REQUEST, "subscriptionId is not a UUID.", "Malformed request")
            returningError(ErrorCategory.NOT_FOUND, "No such device of the caller.", "Push subscription was not found")
            disabledResponse()
        } bindContract (Method.DELETE) to { id: String ->
            noStore.then(access.staffNotifier()).then(enabled(operations)).then { request: Request ->
                checkNotNull(operations).revoke(staffUser(request), StaffPushSubscriptionId(uuid(id, subscriptionIdPath)))
                Response(Status.NO_CONTENT)
            }
        },
        "/staff/notifications/test" meta {
            staffNotificationMetadata(
                "sendStaffTestNotification",
                "Send a test notification to one of my devices",
                "Queues one push to the given device with server-defined copy (title “Fiona’s test notification”, body " +
                    "“Push notifications are working on this device.”) and answers 202 after the job commits. Idempotency-Key " +
                    "is required: the same key and device return the original notificationId, even after a lost response or " +
                    "after the device was removed; the same key with another device is `409 $IDEMPOTENCY_KEY_REUSED`. A new " +
                    "key for a removed or unknown device is 404 and queues nothing. Track the outcome with " +
                    "`GET /staff/notifications/{notificationId}`.",
                unsafe = true,
            )
            headers += testKeyHeader
            preFlightExtraction = PreFlightExtraction.None
            receiving(testBody to SendTestNotificationRequest(exampleDevice.subscriptionId))
            returning(
                Status.ACCEPTED,
                queuedBody to TestNotificationQueuedResponse(EXAMPLE_NOTIFICATION, StaffNotificationStatusResponse.QUEUED),
                "The test is durably queued (or this key's existing test is returned).",
            )
            returningError(
                ErrorCategory.MALFORMED_REQUEST,
                "Idempotency-Key is missing, repeated or invalid; subscriptionId is not a UUID; or the body is not JSON.",
                "Malformed request",
            )
            returningError(ErrorCategory.NOT_FOUND, "No such active device of the caller.", "Push subscription was not found")
            returning(
                Status.CONFLICT,
                notificationErrors to ErrorResponse(IDEMPOTENCY_KEY_REUSED, "This key already represents a test to a different device"),
                "`$IDEMPOTENCY_KEY_REUSED`: the key already names a test to another device.",
            )
            disabledResponse()
        } bindContract Method.POST to
            noStore.then(access.staffNotifier()).then(enabled(operations)).then(notificationConflicts).then { request: Request ->
                val key =
                    try {
                        require(request.headerValues("Idempotency-Key").size == 1)
                        TestNotificationKey(testKeyHeader(request))
                    } catch (failure: IllegalArgumentException) {
                        throw LensFailure(Invalid(testKeyHeader.meta), cause = failure)
                    }
                val input = testBody(request)
                val target =
                    try {
                        UUID.fromString(input.subscriptionId)
                    } catch (failure: IllegalArgumentException) {
                        throw LensFailure(Invalid(testBody.metas.single().copy(name = "subscriptionId")), cause = failure)
                    }
                val view = checkNotNull(operations).sendTest(staffUser(request), key, StaffPushSubscriptionId(target))
                Response(Status.ACCEPTED).with(
                    queuedBody of
                        TestNotificationQueuedResponse(
                            view.notification.id.value
                                .toString(),
                            StaffNotificationStatusResponse.valueOf(view.delivery.state.name),
                        ),
                )
            },
        "/staff/notifications" / notificationIdPath meta {
            staffNotificationMetadata(
                "getStaffTestNotification",
                "Track one of my test notifications",
                "The current delivery status of a test the caller sent. `push_accepted` means the push service accepted it, " +
                    "not that the device displayed it. While retrying, failureReason is the latest transient reason. A retry " +
                    "after an uncertain result (provider_outcome_unknown) may display the notification twice; its " +
                    "notificationId stays the same.",
            )
            returning(
                Status.OK,
                statusBody to
                    TestNotificationResponse(
                        EXAMPLE_NOTIFICATION,
                        "test",
                        exampleDevice.subscriptionId,
                        StaffNotificationStatusResponse.PUSH_ACCEPTED,
                        1,
                        "2026-11-18T17:03:00.010Z",
                        "2026-11-18T17:03:00.412Z",
                        "2026-11-18T17:03:00.733Z",
                        null,
                        201,
                    ),
            )
            returningError(ErrorCategory.MALFORMED_REQUEST, "notificationId is not a UUID.", "Malformed request")
            returningError(ErrorCategory.NOT_FOUND, "No such test notification of the caller.", "Notification was not found")
            disabledResponse()
        } bindContract Method.GET to { id: String ->
            noStore.then(access.staffNotifier()).then(enabled(operations)).then { request: Request ->
                val view = checkNotNull(operations).readTest(staffUser(request), StaffNotificationId(uuid(id, notificationIdPath)))
                Response(Status.OK).with(statusBody of view.response())
            }
        },
    )

private fun uuid(
    segment: String,
    lens: PathLens<String>,
): UUID =
    try {
        UUID.fromString(segment)
    } catch (failure: IllegalArgumentException) {
        throw LensFailure(Invalid(lens.meta), cause = failure)
    }

private fun StaffPushSubscription.response() =
    PushDeviceResponse(
        id.value.toString(),
        label?.value,
        service.name,
        if (active) PushDeviceStateResponse.ACTIVE else PushDeviceStateResponse.EXPIRED,
        enrolledAt.toString(),
        refreshedAt.toString(),
        expirationTime?.toString(),
    )

private fun TestNotificationView.response() =
    TestNotificationResponse(
        notification.id.value.toString(),
        notification.kind.wire,
        delivery.subscriptionId.value.toString(),
        StaffNotificationStatusResponse.valueOf(delivery.state.name),
        delivery.attempts,
        notification.createdAt.toString(),
        delivery.lastAttemptAt?.toString(),
        delivery.completedAt?.toString(),
        delivery.reason?.code,
        delivery.providerCode,
    )
