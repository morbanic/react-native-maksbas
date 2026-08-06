package com.maksbas

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives every push.
 *
 * Since the server sends data-only messages, this runs whether the app is in the
 * foreground, backgrounded, or not running at all — which is exactly why
 * delivery can be measured and the notification always looks the same.
 *
 * The one case nothing arrives is a force-stopped app. That is an Android
 * guarantee no push SDK can work around.
 */
class MaksbasMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val store = MaksbasStore(this)
        val payload = NotificationPayload.fromFcmData(message.data)

        if (payload == null) {
            // Not ours — another SDK in the same app may own this message.
            if (store.debug) Log.d("Maksbas", "ignoring message without mb_id")
            return
        }

        if (store.debug) Log.d("Maksbas", "received ${payload.id}")

        NotificationRenderer.show(this, payload)
        EventReporter.report(this, payload.id, "delivered")

        // Only reaches JS if the app happens to be running. The notification and
        // the delivery event above do not depend on it.
        MaksbasModule.emit(this, "maksbas:received", payload)
    }

    /**
     * FCM rotates tokens on reinstall, restore, and occasionally on its own.
     * Without forwarding this, the server keeps a retired token and every send
     * to this device fails silently.
     */
    override fun onNewToken(token: String) {
        val store = MaksbasStore(this)
        if (store.debug) Log.d("Maksbas", "FCM token rotated")

        MaksbasModule.emitTokenRefresh(this, token)
    }
}
