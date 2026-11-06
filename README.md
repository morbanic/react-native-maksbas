# react-native-maksbas

Push notifications for React Native (Android and iOS). Talks to a
[`maksbas-nextjs`](../maksbas-nextjs) backend.

Self-contained native module — no `@react-native-firebase` dependency.

---

## Install

```bash
npm install react-native-maksbas
```

Straight from GitHub works too — `lib/` is not committed, so npm builds the
package from source during install via the `prepare` script:

```bash
npm install github:USER/react-native-maksbas     # default branch
npm install github:USER/react-native-maksbas#v0.1.0
```

The one thing that breaks this is `npm install --ignore-scripts` (or a CI that
sets it globally) — `prepare` never runs and the package lands without `lib/`.
Allow scripts for this dependency, or install from a published tarball.

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
| `getDeviceDetails()` | `DeviceDetails \| null` — full server record (platform, versions, `active`, `lastSeenAt`…) plus live permission, for debug screens |
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

Same JS API, same server, same token format: iOS goes through FCM too, and FCM
hands the message to APNs. The server adds an `apns` block with `aps.alert` to
every message (iOS will not draw a data-only message), while Android keeps
receiving data-only and drawing its own.

Setup in the host app:

1. **Firebase** — add an iOS app with your bundle id to the same Firebase
   project, download `GoogleService-Info.plist` and add it to the app target
   (Copy Bundle Resources). Without it the SDK logs a warning and stays off;
   the rest of the app runs normally.
2. **APNs key** — Apple Developer → Keys → new key with Apple Push
   Notifications service; upload the `.p8` with its Key ID and Team ID under
   Firebase → Project settings → Cloud Messaging → Apple app configuration.
3. **Capability** — Push Notifications on the target, i.e. `aps-environment`
   in the entitlements file (`development`; App Store export switches it to
   `production`).
4. **Podfile** — Firebase's Swift pods need module maps when React Native is
   built as static libraries:
   ```ruby
   pod 'GoogleUtilities', :modular_headers => true
   pod 'FirebaseCore', :modular_headers => true
   pod 'FirebaseCoreExtension', :modular_headers => true
   pod 'FirebaseInstallations', :modular_headers => true
   pod 'GoogleDataTransport', :modular_headers => true
   pod 'nanopb', :modular_headers => true
   ```

If the app also uses `@react-native-firebase` (which pulls Firebase through
Swift Package Manager), skip the pods above: the podspec then takes
FirebaseMessaging through RNFirebase's own SPM helper, at the same pinned
version, so FirebaseCore exists only once. RNFirebase with SPM requires
`use_frameworks! :linkage => :dynamic`.

No AppDelegate changes. The SDK configures Firebase (unless the app already
did), installs the `UNUserNotificationCenter` delegate and registers for remote
notifications on `UIApplicationDidFinishLaunchingNotification`, so a tap that
cold-starts the app is caught and parked for `getInitialNotification()`.
Notifications that aren't ours (no `mb_id`) are forwarded to whichever delegate
was installed before.

Differences from Android:

- `delivered` is reported only for pushes that arrive while the app is in the
  foreground. Background deliveries would need a Notification Service
  Extension; `opened` is always reported.
- `channelName`, `smallIcon` and `accentColor` are ignored — iOS draws the
  notification with the app icon.
- No image in the notification (also an extension's job); `image` still
  reaches JS in the payload.
- `getFcmToken()` waits up to 15 s for APNs to issue a device token. On a
  simulator that needs iOS 16+ on an Apple silicon Mac.

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
