package com.maksbas

import android.content.Context
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reports `delivered` and `opened` events straight from the native side.
 *
 * This cannot live in JavaScript: a push that arrives while the app is closed
 * runs the messaging service with no JS context at all. Reporting from JS would
 * mean only counting deliveries to users who happened to have the app open,
 * which is a delivery rate that measures nothing.
 *
 * Failures are queued and replayed on the next app start rather than retried in
 * place — the device is usually offline, and a retry loop inside a background
 * service is a good way to drain a battery.
 */
internal object EventReporter {

    private val executor = Executors.newSingleThreadExecutor()

    fun report(context: Context, notificationId: String, type: String) {
        val store = MaksbasStore(context)
        val event = JSONObject().apply {
            put("notificationId", notificationId)
            put("type", type)
        }

        executor.execute {
            val sent = send(store, JSONArray().put(event))
            if (!sent) {
                store.queueEvent(event)
                log(store, "queued $type event for $notificationId")
            }
        }
    }

    /**
     * Replays whatever is queued. Returns the number of events sent.
     *
     * Called from JS once the SDK is initialised and credentials are known to be
     * valid.
     */
    fun flush(context: Context): Int {
        val store = MaksbasStore(context)
        val queue = store.queuedEvents()
        if (queue.length() == 0) return 0

        // Clear first: a duplicate event is a no-op on the server thanks to the
        // unique index, whereas a crash between send and clear would replay
        // forever.
        store.clearQueuedEvents()

        val sent = send(store, queue)
        if (!sent) {
            for (i in 0 until queue.length()) {
                (queue.opt(i) as? JSONObject)?.let { store.queueEvent(it) }
            }
            return 0
        }
        return queue.length()
    }

    private fun send(store: MaksbasStore, events: JSONArray): Boolean {
        val endpoint = store.endpoint ?: return false
        val deviceId = store.deviceId ?: return false
        val secret = store.deviceSecret ?: return false

        return try {
            val url = URL("$endpoint/devices/$deviceId/events")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $secret")
                setRequestProperty("Content-Type", "application/json")
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(JSONObject().put("events", events).toString())
            }

            val status = connection.responseCode
            connection.disconnect()

            // 4xx means the request itself is wrong — bad credentials, a deleted
            // notification. Replaying it forever would never succeed, so treat it
            // as handled and drop it.
            val handled = status in 200..299 || status in 400..499
            if (!handled) log(store, "event report failed with HTTP $status")
            handled
        } catch (error: Exception) {
            log(store, "event report failed: ${error.message}")
            false
        }
    }

    private fun log(store: MaksbasStore, message: String) {
        if (store.debug) Log.d("Maksbas", message)
    }
}
