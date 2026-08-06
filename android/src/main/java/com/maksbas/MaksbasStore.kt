package com.maksbas

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed storage shared by the JS layer and the background
 * messaging service.
 *
 * The service runs with no JS context, so it cannot ask JavaScript for the
 * endpoint or the device credentials when a push lands at 3am. Everything it
 * needs has to already be on disk.
 */
internal class MaksbasStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var endpoint: String?
        get() = prefs.getString(KEY_ENDPOINT, null)
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value).apply()

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceSecret: String?
        get() = prefs.getString(KEY_DEVICE_SECRET, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_SECRET, value).apply()

    var channelName: String
        get() = prefs.getString(KEY_CHANNEL_NAME, DEFAULT_CHANNEL_NAME) ?: DEFAULT_CHANNEL_NAME
        set(value) = prefs.edit().putString(KEY_CHANNEL_NAME, value).apply()

    var channelDescription: String
        get() = prefs.getString(KEY_CHANNEL_DESCRIPTION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHANNEL_DESCRIPTION, value).apply()

    var smallIcon: String?
        get() = prefs.getString(KEY_SMALL_ICON, null)
        set(value) = prefs.edit().putString(KEY_SMALL_ICON, value).apply()

    var accentColor: String?
        get() = prefs.getString(KEY_ACCENT_COLOR, null)
        set(value) = prefs.edit().putString(KEY_ACCENT_COLOR, value).apply()

    var debug: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    /**
     * A tap that cold-starts the app happens long before any JS listener exists,
     * so the notification is parked here and picked up by
     * `getInitialNotification()`.
     */
    var pendingOpen: JSONObject?
        get() = prefs.getString(KEY_PENDING_OPEN, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        set(value) = prefs.edit().putString(KEY_PENDING_OPEN, value?.toString()).apply()

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_SECRET)
            .apply()
    }

    fun getItem(key: String): String? = prefs.getString(prefixed(key), null)

    fun setItem(key: String, value: String) {
        prefs.edit().putString(prefixed(key), value).apply()
    }

    fun removeItem(key: String) {
        prefs.edit().remove(prefixed(key)).apply()
    }

    /** Events the service couldn't deliver, replayed on the next app start. */
    fun queuedEvents(): JSONArray =
        prefs.getString(KEY_EVENT_QUEUE, null)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: JSONArray()

    fun queueEvent(event: JSONObject) {
        val queue = queuedEvents()
        // Bounded so a device that is offline for a week doesn't grow the file
        // without limit. Oldest goes first — the newest events are the ones
        // still worth reporting.
        val trimmed = JSONArray()
        val start = maxOf(0, queue.length() - (MAX_QUEUED_EVENTS - 1))
        for (i in start until queue.length()) trimmed.put(queue.get(i))
        trimmed.put(event)
        prefs.edit().putString(KEY_EVENT_QUEUE, trimmed.toString()).apply()
    }

    fun clearQueuedEvents() {
        prefs.edit().remove(KEY_EVENT_QUEUE).apply()
    }

    private fun prefixed(key: String) = "kv:$key"

    companion object {
        private const val PREFS_NAME = "maksbas"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_DEVICE_SECRET = "deviceSecret"
        private const val KEY_CHANNEL_NAME = "channelName"
        private const val KEY_CHANNEL_DESCRIPTION = "channelDescription"
        private const val KEY_SMALL_ICON = "smallIcon"
        private const val KEY_ACCENT_COLOR = "accentColor"
        private const val KEY_DEBUG = "debug"
        private const val KEY_PENDING_OPEN = "pendingOpen"
        private const val KEY_EVENT_QUEUE = "eventQueue"

        const val DEFAULT_CHANNEL_NAME = "Obavijesti"
        const val CHANNEL_ID = "maksbas_default"
        private const val MAX_QUEUED_EVENTS = 200
    }
}
