package com.maksbas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Invisible activity the notification taps into.
 *
 * A BroadcastReceiver would be the obvious choice, but Android 12 blocks
 * notification trampolines that launch an activity from a receiver or service.
 * Activity-to-activity is still allowed, so this one records the open, hands the
 * payload to the app, and finishes before anything is drawn.
 */
class MaksbasOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = MaksbasStore(this)
        val payload = NotificationPayload.fromIntent(intent)

        if (payload == null) {
            finish()
            return
        }

        if (store.debug) Log.d("Maksbas", "opened ${payload.id}")

        EventReporter.report(this, payload.id, "opened")

        // If JS is already running it gets the event now. If the tap is what
        // started the process, nothing is listening yet — park it for
        // getInitialNotification() to pick up on mount.
        val delivered = MaksbasModule.emit(this, "maksbas:opened", payload)
        if (!delivered) {
            store.pendingOpen = payload.toJson()
        }

        launchApp(payload)
        finish()
    }

    private fun launchApp(payload: NotificationPayload) {
        val intent = resolveLaunchIntent(payload)
        if (intent == null) {
            Log.w("Maksbas", "no launchable activity found for ${packageName}")
            return
        }

        intent.putExtras(payload.toBundle())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    /**
     * A deeplink goes through the app's own intent filters when one matches, so
     * existing routing keeps working. Otherwise fall back to the launcher.
     */
    private fun resolveLaunchIntent(payload: NotificationPayload): Intent? {
        payload.deeplink?.let { link ->
            val deeplinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(packageName)
            if (deeplinkIntent.resolveActivity(packageManager) != null) return deeplinkIntent
        }
        return packageManager.getLaunchIntentForPackage(packageName)
    }

    companion object {
        fun intentFor(context: Context, payload: NotificationPayload): Intent =
            Intent(context, MaksbasOpenActivity::class.java).apply {
                putExtras(payload.toBundle())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
