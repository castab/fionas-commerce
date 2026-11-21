package io.github.castab.fionas.commerce.notification

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The two JSON shapes Web Push owns, kept apart from operations and repositories: the outbox
 * work-item payload (Fiona ids only) and the encrypted browser payload (see
 * docs/staff-push-notifications.md). Neither is an HTTP DTO; neither carries customer data,
 * endpoints or keys.
 */
internal object WebPushPayloads {
    const val TEST_TITLE = "Fiona’s test notification"
    const val TEST_BODY = "Push notifications are working on this device."

    /** Every reminder shares one push Topic, so a newer one replaces an undelivered older one. */
    const val REMINDER_TOPIC = "unanswered-inquiries"

    fun reminderTitle(count: Int): String = if (count == 1) "1 inquiry needs to be answered!" else "$count inquiries need to be answered!"

    fun reminderBody(count: Int): String = if (count == 1) "Open Fiona’s admin to answer it." else "Open Fiona’s admin to answer them."

    fun workItem(
        field: String,
        id: UUID,
    ): JsonObject = buildJsonObject { put(field, JsonPrimitive(id.toString())) }

    fun workItemId(
        payload: JsonObject,
        field: String,
    ): UUID = UUID.fromString(checkNotNull(payload[field]).jsonPrimitive.content)

    fun browser(
        notification: StaffNotification,
        reminderPath: String,
    ): String =
        buildJsonObject {
            put("version", JsonPrimitive(1))
            put("notificationId", JsonPrimitive(notification.id.value.toString()))
            put("kind", JsonPrimitive(notification.kind.wire))
            when (notification.kind) {
                StaffNotificationKind.TEST -> {
                    put("title", JsonPrimitive(TEST_TITLE))
                    put("body", JsonPrimitive(TEST_BODY))
                    put("url", JsonNull)
                }
                StaffNotificationKind.UNANSWERED_INQUIRIES -> {
                    val count = checkNotNull(notification.inquiryCount)
                    put("title", JsonPrimitive(reminderTitle(count)))
                    put("body", JsonPrimitive(reminderBody(count)))
                    put("url", JsonPrimitive(reminderPath))
                }
            }
        }.toString()
}
