package com.maksbas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Locale
import java.util.TimeZone

class MaksbasModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val store = MaksbasStore(reactContext)

    override fun getName() = NAME

    override fun getConstants(): Map<String, Any> = mapOf(
        "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "language" to Locale.getDefault().toLanguageTag(),
        "timezone" to TimeZone.getDefault().id,
    )

    @ReactMethod
    fun configure(config: ReadableMap, promise: Promise) {
        store.endpoint = config.getString("endpoint")
        store.channelName = config.getString("channelName") ?: MaksbasStore.DEFAULT_CHANNEL_NAME
        store.channelDescription = config.getString("channelDescription") ?: ""
        store.smallIcon = config.getString("smallIcon")
        store.accentColor = config.getString("accentColor")
        store.debug = config.takeIf { it.hasKey("debug") }?.getBoolean("debug") ?: false
        promise.resolve(null)
    }

    @ReactMethod
    fun setCredentials(deviceId: String, deviceSecret: String, promise: Promise) {
        store.deviceId = deviceId
        store.deviceSecret = deviceSecret
        promise.resolve(null)
    }

    @ReactMethod
    fun clearCredentials(promise: Promise) {
        store.clearCredentials()
        promise.resolve(null)
    }

    @ReactMethod
    fun getFcmToken(promise: Promise) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> promise.resolve(token) }
            .addOnFailureListener { error ->
                promise.reject(
                    "fcm_token_failed",
                    "Could not get an FCM token. Check that google-services.json is in " +
                        "android/app/ and that the google-services Gradle plugin is applied. " +
                        "(${error.message})",
                    error,
                )
            }
    }

    @ReactMethod
    fun getPermissionStatus(promise: Promise) {
        promise.resolve(currentPermissionStatus())
    }

    /**
     * Asks for POST_NOTIFICATIONS.
     *
     * Android shows this dialog once. After the user declines, later calls
     * return immediately without any UI — which is why the SDK never fires this
     * on its own during `init()`.
     */
    @ReactMethod
    fun requestPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No runtime permission before Android 13, but the user can still
            // have switched notifications off in system settings.
            promise.resolve(currentPermissionStatus())
            return
        }

        if (currentPermissionStatus() == GRANTED) {
            promise.resolve(GRANTED)
            return
        }

        val activity = currentActivity as? PermissionAwareActivity
        if (activity == null) {
            promise.reject(
                "no_activity",
                "No foreground activity to show the permission dialog. Call requestPermission() " +
                    "from a mounted screen, not during module load.",
            )
            return
        }

        val listener = PermissionListener { requestCode, _, grantResults ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@PermissionListener false

            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            promise.resolve(if (granted) GRANTED else DENIED)
            true
        }

        activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            PERMISSION_REQUEST_CODE,
            listener,
        )
    }

    @ReactMethod
    fun getItem(key: String, promise: Promise) = promise.resolve(store.getItem(key))

    @ReactMethod
    fun setItem(key: String, value: String, promise: Promise) {
        store.setItem(key, value)
        promise.resolve(null)
    }

    @ReactMethod
    fun removeItem(key: String, promise: Promise) {
        store.removeItem(key)
        promise.resolve(null)
    }

    /** Returns and clears the notification that cold-started the app. */
    @ReactMethod
    fun getInitialNotification(promise: Promise) {
        val pending = store.pendingOpen
        if (pending == null) {
            promise.resolve(null)
            return
        }
        store.pendingOpen = null
        promise.resolve(NotificationPayload.fromJson(pending).toWritableMap())
    }

    @ReactMethod
    fun flushPendingEvents(promise: Promise) {
        promise.resolve(EventReporter.flush(reactApplicationContext))
    }

    /** Required by NativeEventEmitter; the bookkeeping happens on the JS side. */
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    private fun currentPermissionStatus(): String {
        val enabled = NotificationManagerCompat.from(reactApplicationContext)
            .areNotificationsEnabled()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (enabled) GRANTED else DENIED
        }

        val granted = reactApplicationContext.checkSelfPermission(
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        return when {
            granted && enabled -> GRANTED
            // Denied at the OS level even though the runtime permission is held —
            // the user turned notifications off in settings.
            granted -> DENIED
            else -> NOT_DETERMINED
        }
    }

    companion object {
        const val NAME = "Maksbas"
        private const val PERMISSION_REQUEST_CODE = 8213

        private const val GRANTED = "granted"
        private const val DENIED = "denied"
        private const val NOT_DETERMINED = "not_determined"

        /**
         * Pushes an event to JS if a React context happens to be alive.
         *
         * Returns false when there is none — a push that arrives with the app
         * closed is the normal case, not an error. Callers use the return value
         * to decide whether to park the payload instead.
         */
        @JvmStatic
        fun emit(context: Context, event: String, payload: NotificationPayload): Boolean {
            val reactContext = activeReactContext(context) ?: return false

            return runCatching {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(event, payload.toWritableMap())
                true
            }.getOrElse { error ->
                Log.d("Maksbas", "could not emit $event: ${error.message}")
                false
            }
        }

        @JvmStatic
        fun emitTokenRefresh(context: Context, token: String): Boolean {
            val reactContext = activeReactContext(context) ?: return false

            return runCatching {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("maksbas:tokenRefreshed", Arguments.createMap().apply {
                        putString("token", token)
                    })
                true
            }.getOrDefault(false)
        }

        private fun activeReactContext(context: Context): ReactContext? = runCatching {
            val application = context.applicationContext as? ReactApplication ?: return null
            val instanceManager = application.reactNativeHost.reactInstanceManager
            instanceManager.currentReactContext?.takeIf { it.hasActiveReactInstance() }
        }.getOrNull()
    }
}
