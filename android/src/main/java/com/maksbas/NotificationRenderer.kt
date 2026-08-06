package com.maksbas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * Draws the notification.
 *
 * Because sends are data-only, Android never renders anything by itself — every
 * pixel here is ours. That is the whole point of the data-only choice: identical
 * rendering whether the app was open, backgrounded, or dead.
 */
internal object NotificationRenderer {

    fun show(context: Context, payload: NotificationPayload) {
        val store = MaksbasStore(context)
        ensureChannel(context, store)

        val contentIntent = PendingIntent.getActivity(
            context,
            payload.id.hashCode(),
            MaksbasOpenActivity.intentFor(context, payload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, MaksbasStore.CHANNEL_ID)
            .setSmallIcon(resolveSmallIcon(context, store))
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            // Long bodies are truncated to one line unless a style says otherwise.
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))

        store.accentColor?.let { hex ->
            runCatching { builder.setColor(Color.parseColor(hex)) }
        }

        payload.image?.let { url ->
            downloadBitmap(url, store)?.let { bitmap ->
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .setSummaryText(payload.body),
                )
            }
        }

        // Keyed by the server-side notification id, so a redelivery of the same
        // push replaces the existing one instead of stacking a duplicate.
        val notificationId = payload.id.hashCode()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }.onFailure { error ->
            // Thrown when POST_NOTIFICATIONS was never granted. Not worth
            // crashing a background service over.
            if (store.debug) Log.d("Maksbas", "could not post notification: ${error.message}")
        }
    }

    /**
     * One channel, created on first use.
     *
     * A single channel means a user who mutes promotional noise also mutes
     * transactional messages — a real tradeoff, taken deliberately for setup
     * simplicity. Splitting it later means creating additional channels here and
     * honouring a `channel` field in the payload.
     */
    private fun ensureChannel(context: Context, store: MaksbasStore) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(MaksbasStore.CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            MaksbasStore.CHANNEL_ID,
            store.channelName,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = store.channelDescription
            enableVibration(true)
        }

        manager.createNotificationChannel(channel)
    }

    /**
     * Falls back to the launcher icon, which Android silhouettes into a white
     * square on the status bar. Ugly but visible — better than no icon, which
     * suppresses the notification entirely on some OEM builds.
     */
    private fun resolveSmallIcon(context: Context, store: MaksbasStore): Int {
        store.smallIcon?.let { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) return id
            val mipmap = context.resources.getIdentifier(name, "mipmap", context.packageName)
            if (mipmap != 0) return mipmap
        }
        return context.applicationInfo.icon
    }

    private fun downloadBitmap(url: String, store: MaksbasStore): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            doInput = true
        }
        connection.inputStream.use { BitmapFactory.decodeStream(it) }
    }.onFailure { error ->
        // A missing image must never cost the whole notification.
        if (store.debug) Log.d("Maksbas", "image download failed: ${error.message}")
    }.getOrNull()
}
