package com.maksbas

import android.content.Intent
import android.os.Bundle
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject

/**
 * The wire contract with `maksbas-nextjs`.
 *
 * Keys are prefixed `ms_` so a host app's own `data` entries can never shadow
 * ours — `title` is a plausible thing for someone to pass through.
 *
 * This mirrors `src/notifications/payload.ts` on the server. The two packages
 * are independent, so a change on one side has to be made on the other by hand.
 */
internal data class NotificationPayload(
    val id: String,
    val title: String,
    val body: String,
    val image: String?,
    val deeplink: String?,
    val data: Map<String, String>,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_TITLE, title)
        put(KEY_BODY, body)
        put(KEY_IMAGE, image)
        put(KEY_DEEPLINK, deeplink)
        put(KEY_DATA, JSONObject(data))
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_ID, id)
        putString(KEY_TITLE, title)
        putString(KEY_BODY, body)
        putString(KEY_IMAGE, image)
        putString(KEY_DEEPLINK, deeplink)
        putString(KEY_DATA, JSONObject(data).toString())
    }

    /** Shape handed to JS listeners — see `MaksbasNotification` in types.ts. */
    fun toWritableMap(): WritableMap = Arguments.createMap().apply {
        putString("id", id)
        putString("title", title)
        putString("body", body)
        if (image != null) putString("image", image) else putNull("image")
        if (deeplink != null) putString("deeplink", deeplink) else putNull("deeplink")
        putMap("data", Arguments.createMap().apply {
            for ((key, value) in data) putString(key, value)
        })
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_IMAGE = "image"
        private const val KEY_DEEPLINK = "deeplink"
        private const val KEY_DATA = "data"

        /** Parses the `data` map of an incoming FCM message. */
        fun fromFcmData(data: Map<String, String>): NotificationPayload? {
            val id = data["mb_id"] ?: return null
            val title = data["mb_title"] ?: return null
            val body = data["mb_body"] ?: return null

            val extra = data["mb_data"]
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.let { json ->
                    json.keys().asSequence().associateWith { key -> json.optString(key, "") }
                }
                ?: emptyMap()

            return NotificationPayload(
                id = id,
                title = title,
                body = body,
                image = data["mb_image"],
                deeplink = data["mb_deeplink"],
                data = extra,
            )
        }

        fun fromIntent(intent: Intent): NotificationPayload? {
            val id = intent.getStringExtra(KEY_ID) ?: return null
            val extraJson = intent.getStringExtra(KEY_DATA)
            val extra = extraJson
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.let { json -> json.keys().asSequence().associateWith { key -> json.optString(key, "") } }
                ?: emptyMap()

            return NotificationPayload(
                id = id,
                title = intent.getStringExtra(KEY_TITLE).orEmpty(),
                body = intent.getStringExtra(KEY_BODY).orEmpty(),
                image = intent.getStringExtra(KEY_IMAGE),
                deeplink = intent.getStringExtra(KEY_DEEPLINK),
                data = extra,
            )
        }

        fun fromJson(json: JSONObject): NotificationPayload {
            val extraJson = json.optJSONObject(KEY_DATA)
            val extra = extraJson
                ?.let { obj -> obj.keys().asSequence().associateWith { key -> obj.optString(key, "") } }
                ?: emptyMap()

            return NotificationPayload(
                id = json.optString(KEY_ID),
                title = json.optString(KEY_TITLE),
                body = json.optString(KEY_BODY),
                image = json.optString(KEY_IMAGE).takeIf { it.isNotEmpty() && it != "null" },
                deeplink = json.optString(KEY_DEEPLINK).takeIf { it.isNotEmpty() && it != "null" },
                data = extra,
            )
        }
    }
}
