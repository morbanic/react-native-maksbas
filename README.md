# react-native-maksbas

Android push notifications for React Native. Talks to a
[`maksbas-nextjs`](../maksbas-nextjs) backend.

Self-contained native module — no `@react-native-firebase` dependency.

---

## Install

```bash
npm install react-native-maksbas
```

Then rebuild. A JS reload is not enough after adding a native package:

```bash
npx react-native run-android
```

### Firebase setup

FCM requires this regardless of which SDK you use.

1. Firebase Console → add an Android app with your `applicationId`
2. Download `google-services.json` → `android/app/google-services.json`
3. Add the Google services plugin:

```gradle
// android/build.gradle
buildscript {
  dependencies {
    classpath 'com.google.gms:google-services:4.4.2'
  }
}
```

```gradle
// android/app/build.gradle
apply plugin: 'com.google.gms.google-services'
```

The same Firebase project must supply the service account JSON your backend uses.
Mismatched projects produce `SENDER_ID_MISMATCH` on every send.

### Optional: a proper status bar icon

Android renders the small icon as a white silhouette. Without a dedicated
drawable your app icon becomes a white square.

Add a white-on-transparent PNG at
`android/app/src/main/res/drawable/ic_notification.png`, then pass
`smallIcon: 'ic_notification'` to `init()`.

---

## Usage

```ts
import { Maksbas } from 'react-native-maksbas'

// Once, at app start.
await Maksbas.init({
  endpoint: 'https://app.hr/api/maksbas',
  publicKey: 'pk_live_...',
  channelName: 'Obavijesti',
  smallIcon: 'ic_notification',
  accentColor: '#0B7285',
})
```

`init()` registers the device but **does not ask for permission**. Ask when the
user has context:

```ts
const status = await Maksbas.requestPermission()
// 'granted' | 'denied' | 'not_determined' | 'unsupported'
```

Android shows that dialog once. Fire it in the first second of the first launch
and most people decline — permanently.

### Attributes and segmentation

```ts
await Maksbas.setAttributes({ plan: 'pro', city: 'ST', age: 34 })

// Everything is stored as a string. null deletes the key.
await Maksbas.setAttributes({ userId: 'user_8123' })   // "login"
await Maksbas.setAttributes({ userId: null })          // "logout"
```

Your backend then targets `{ "key": "userId", "op": "eq", "value": "user_8123" }`.

### Reacting to notifications

```ts
useEffect(() => {
  const offOpened = Maksbas.onOpened((n) => {
    if (n.deeplink) navigate(n.deeplink)
  })

  const offReceived = Maksbas.onReceived((n) => {
    console.log('arrived while in foreground', n.title)
  })

  // A tap that cold-starts the app happens before any listener exists.
  Maksbas.getInitialNotification().then((n) => {
    if (n?.deeplink) navigate(n.deeplink)
  })

  return () => {
    offOpened()
    offReceived()
  }
}, [])
```

### API

| Method | Returns |
|---|---|
| `init(options)` | `DeviceInfo \| null` |
| `requestPermission()` | `PermissionStatus` |
| `getPermissionStatus()` | `PermissionStatus` |
| `setAttributes(attrs)` | stored attributes |
| `getDevice()` | `DeviceInfo \| null` |
| `onReceived(cb)` | unsubscribe function |
| `onOpened(cb)` | unsubscribe function |
| `getInitialNotification()` | `MaksbasNotification \| null` |
| `unregister()` | `void` |

---

## How it works

Messages are sent **data-only** — no `notification` block — so
`MaksbasMessagingService` runs for every push whether the app is foreground,
background, or not running, and draws the notification itself.

That is what makes two things possible:

- **Consistent rendering.** Android never draws its own version, so there are no
  duplicate notifications and the layout is identical in every app state.
- **Real delivery numbers.** `delivered` is reported from Kotlin, not JS. A push
  that arrives with the app closed has no JS context at all; reporting from JS
  would only count users who happened to have the app open.

Events that fail to report are queued in SharedPreferences and replayed on the
next launch.

The one case where nothing arrives is a force-stopped app. No push SDK can work
around that.

### Notification taps

The tap targets `MaksbasOpenActivity`, an invisible activity that records the
open and forwards to your app. It is an activity rather than a BroadcastReceiver
because Android 12 blocks notification trampolines that launch an activity from
a receiver or service.

If the notification carries a `deeplink` and your app has a matching intent
filter, that intent is used and your existing routing handles it. Otherwise the
launcher activity starts with the payload in its extras.

### Notification channel

One channel, created on first notification, named from `channelName`.

A single channel means a user who mutes promotional noise also mutes
transactional messages. That tradeoff was taken deliberately for setup
simplicity — splitting it means adding channels in `NotificationRenderer.kt` and
a `channel` field to the payload on both sides.

### iOS

Every method is a no-op. `init()` returns `null` instead of throwing, so a shared
codebase runs unchanged. `getFcmToken()` is the one method that throws, and only
if called directly.

---

## Requirements

- React Native ≥ 0.71
- `minSdkVersion` 23, `compileSdkVersion` 34
- Java 17

---

## Verifying it works

There is no automated coverage on the Android side — a real device and a real
Firebase project are unavoidable. Work through this once:

1. **Registration.** Launch with `debug: true`. Look for `registered as <uuid>`
   in logcat, then confirm the row:
   ```bash
   curl -X POST https://app.hr/api/maksbas/devices/query \
     -H "Authorization: Bearer $MAKSBAS_SECRET_KEY" -d '{}'
   ```

2. **Permission.** Call `requestPermission()` behind a button on Android 13+.
   The dialog appears once; use *Settings → Apps → your app → Notifications* to
   reset it between attempts.

3. **Foreground delivery.** Send with the app open. `onReceived` fires and the
   notification appears.

4. **Background delivery.** Home button, then send. Notification appears with
   your icon and colour.

5. **Cold start.** Force-stop, relaunch once so the process is registered, then
   swipe the app away and send. Tapping should launch the app, and
   `getInitialNotification()` should return the payload.

6. **Deep link.** Send with `deeplink: 'app://order/9'` and confirm routing.

7. **Delivery numbers.** After a few sends:
   ```bash
   curl https://app.hr/api/maksbas/notifications/<id> \
     -H "Authorization: Bearer $MAKSBAS_SECRET_KEY"
   ```
   `delivered` should track `sent`. If it stays at zero, the device could not
   reach the endpoint from Kotlin — check that `endpoint` is publicly reachable
   and not a `localhost` address.

8. **Token rotation.** Clear app storage and relaunch. The device should
   re-register rather than accumulate a second row.

### When nothing arrives

| Symptom | Cause |
|---|---|
| `fcm_token_failed` on init | `google-services.json` missing, or the plugin isn't applied |
| Registers, no notifications | Permission denied, or the device is `active = false` — check `/devices/query` |
| `SENDER_ID_MISMATCH` on the server | App and service account are from different Firebase projects |
| Notification shows, `delivered` stays 0 | Endpoint unreachable from the device |
| Works foreground, silent in background | Battery optimisation. Check *Settings → Apps → Battery → Unrestricted* |
| Native module isn't linked | Rebuild — a Metro reload doesn't link native code |
