package io.github.castab.fionas.commerce.notification

import io.github.castab.commerce.staff.UserId
import java.time.Instant

/**
 * Staff Web Push operations handed to the HTTP contract; null in `FionaOperations` when the
 * feature is disabled, so its routes answer `503 push_notifications_disabled`.
 */
class StaffNotificationOperations(
    /** The browser's `applicationServerKey` (VAPID public key, base64url). */
    val applicationServerKey: String,
    val enroll: (owner: UserId, endpoint: String, p256dh: String, auth: String, expiration: Instant?, label: String?) ->
    StaffPushDevices.Enrollment,
    val list: (UserId) -> List<StaffPushSubscription>,
    val revoke: (UserId, StaffPushSubscriptionId) -> Unit,
    val sendTest: (UserId, TestNotificationKey, StaffPushSubscriptionId) -> TestNotificationView,
    val readTest: (UserId, StaffNotificationId) -> TestNotificationView,
)
