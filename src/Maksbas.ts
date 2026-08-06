import { NativeModules, Platform } from 'react-native'
import { MaksbasApi, MaksbasApiError } from './api'
import { NATIVE_EVENT, isSupported, native, nativeEvents } from './native'
import type {
  Attributes,
  DeviceInfo,
  MaksbasNotification,
  MaksbasOptions,
  PermissionStatus,
  StoredAttributes,
  Unsubscribe,
} from './types'

const SDK_VERSION = '0.1.0'

const KEY = {
  deviceId: 'maksbas.deviceId',
  deviceSecret: 'maksbas.deviceSecret',
  fcmToken: 'maksbas.fcmToken',
} as const

type Listener<T> = (payload: T) => void

class MaksbasClient {
  private options: MaksbasOptions | null = null
  private api: MaksbasApi | null = null
  private deviceId: string | null = null
  private deviceSecret: string | null = null

  /** Concurrent `init()` calls share one run instead of racing two registrations. */
  private initPromise: Promise<DeviceInfo | null> | null = null

  private readonly receivedListeners = new Set<Listener<MaksbasNotification>>()
  private readonly openedListeners = new Set<Listener<MaksbasNotification>>()
  private nativeSubscriptions: Array<{ remove: () => void }> = []

  /**
   * Sets the SDK up and registers this device.
   *
   * Deliberately does *not* ask for notification permission — call
   * `requestPermission()` when the user has some context for why. Android gives
   * you one prompt; spending it in the first second of the first launch is how
   * you end up with a permanently muted user base.
   */
  async init(options: MaksbasOptions): Promise<DeviceInfo | null> {
    if (this.initPromise) return this.initPromise

    this.initPromise = this.doInit(options).catch((error) => {
      // Let a later call try again rather than wedging the SDK for the session.
      this.initPromise = null
      throw error
    })

    return this.initPromise
  }

  private async doInit(options: MaksbasOptions): Promise<DeviceInfo | null> {
    this.options = { ...options, endpoint: stripTrailingSlash(options.endpoint) }
    this.api = new MaksbasApi(this.options.endpoint, options.publicKey, options.debug ?? false)

    if (Platform.OS !== 'android') {
      this.log('not Android — SDK is inert')
      return null
    }
    if (!isSupported) {
      throw new Error(
        '[maksbas] native module missing. Rebuild the app (`npx react-native run-android`); ' +
          'a JS reload is not enough after installing a native package.',
      )
    }

    await native.configure({
      endpoint: this.options.endpoint,
      channelName: options.channelName ?? 'Obavijesti',
      channelDescription: options.channelDescription ?? '',
      smallIcon: options.smallIcon ?? null,
      accentColor: options.accentColor ?? null,
      debug: options.debug ?? false,
    })

    this.attachNativeListeners()

    const [storedId, storedSecret, storedToken] = await Promise.all([
      native.getItem(KEY.deviceId),
      native.getItem(KEY.deviceSecret),
      native.getItem(KEY.fcmToken),
    ])

    const fcmToken = await native.getFcmToken()
    const permission = await native.getPermissionStatus()

    let device: DeviceInfo

    if (storedId && storedSecret) {
      this.deviceId = storedId
      this.deviceSecret = storedSecret

      if (storedToken !== fcmToken) {
        this.log('FCM token rotated — updating the server')
        await this.syncToken(fcmToken, permission === 'granted')
      }

      device = await this.readDevice(fcmToken, permission)
    } else {
      device = await this.registerDevice(fcmToken, permission === 'granted')
    }

    await native.setCredentials(this.deviceId as string, this.deviceSecret as string)

    // The background service parks events it couldn't deliver; now that we're
    // online with credentials, push them through.
    const flushed = await native.flushPendingEvents().catch(() => 0)
    if (flushed > 0) this.log(`flushed ${flushed} queued events`)

    return device
  }

  private async registerDevice(fcmToken: string, granted: boolean): Promise<DeviceInfo> {
    const api = this.requireApi()

    const result = await api.register({
      fcmToken,
      platform: 'android',
      notificationsEnabled: granted,
      sdkVersion: SDK_VERSION,
      deviceModel: nativeConstant('Model'),
      osVersion: String(Platform.Version),
      language: nativeConstant('Language'),
      timezone: nativeConstant('Timezone'),
    })

    this.deviceId = result.deviceId
    this.deviceSecret = result.deviceSecret

    await Promise.all([
      native.setItem(KEY.deviceId, result.deviceId),
      native.setItem(KEY.deviceSecret, result.deviceSecret),
      native.setItem(KEY.fcmToken, fcmToken),
    ])

    this.log(`registered as ${result.deviceId}`)

    return {
      id: result.deviceId,
      attributes: result.attributes,
      notificationsEnabled: result.notificationsEnabled,
      fcmToken,
    }
  }

  private async syncToken(fcmToken: string, granted: boolean): Promise<void> {
    try {
      await this.requireApi().updateDevice(
        this.deviceId as string,
        this.deviceSecret as string,
        { fcmToken, notificationsEnabled: granted },
      )
      await native.setItem(KEY.fcmToken, fcmToken)
    } catch (error) {
      if (error instanceof MaksbasApiError && (error.status === 404 || error.status === 409)) {
        // The row is gone, or the token now belongs to a different device row.
        // Either way our credentials are stale — start over.
        this.log('stored device is stale, registering again')
        await this.registerDevice(fcmToken, granted)
        return
      }
      throw error
    }
  }

  private async readDevice(fcmToken: string, permission: PermissionStatus): Promise<DeviceInfo> {
    try {
      const remote = (await this.requireApi().getDevice(
        this.deviceId as string,
        this.deviceSecret as string,
      )) as { attributes?: StoredAttributes; notificationsEnabled?: boolean }

      return {
        id: this.deviceId as string,
        attributes: remote.attributes ?? {},
        notificationsEnabled: remote.notificationsEnabled ?? permission === 'granted',
        fcmToken,
      }
    } catch (error) {
      if (error instanceof MaksbasApiError && error.status === 404) {
        return this.registerDevice(fcmToken, permission === 'granted')
      }
      throw error
    }
  }

  /**
   * Asks for the Android 13 notification permission.
   *
   * On Android 12 and below there is no runtime permission, so this resolves
   * `granted` without showing anything.
   */
  async requestPermission(): Promise<PermissionStatus> {
    this.assertReady('requestPermission')

    const status = await native.requestPermission()

    if (this.deviceId && this.deviceSecret) {
      await this.requireApi()
        .updateDevice(this.deviceId, this.deviceSecret, {
          notificationsEnabled: status === 'granted',
        })
        .catch((error) => this.log(`could not report permission state: ${error}`))
    }

    return status
  }

  async getPermissionStatus(): Promise<PermissionStatus> {
    if (Platform.OS !== 'android') return 'unsupported'
    return native.getPermissionStatus()
  }

  /**
   * Merges attributes onto this device. Pass `null` to delete a key.
   *
   * There is no separate concept of a logged-in user — signing out is
   * `setAttributes({ userId: null })`.
   */
  async setAttributes(attributes: Attributes): Promise<StoredAttributes> {
    this.assertReady('setAttributes')

    const payload: Record<string, string | null> = {}
    for (const [key, value] of Object.entries(attributes)) {
      payload[key] = value === null || value === undefined ? null : String(value)
    }

    const result = await this.requireApi().setAttributes(
      this.deviceId as string,
      this.deviceSecret as string,
      payload,
    )
    return result.attributes
  }

  async getDevice(): Promise<DeviceInfo | null> {
    if (!this.deviceId || !this.deviceSecret) return null

    const [remote, fcmToken, permission] = await Promise.all([
      this.requireApi().getDevice(this.deviceId, this.deviceSecret) as Promise<{
        attributes?: StoredAttributes
        notificationsEnabled?: boolean
      }>,
      native.getItem(KEY.fcmToken),
      this.getPermissionStatus(),
    ])

    return {
      id: this.deviceId,
      attributes: remote.attributes ?? {},
      notificationsEnabled: remote.notificationsEnabled ?? permission === 'granted',
      fcmToken: fcmToken ?? '',
    }
  }

  /** Fires when a push arrives while the app is in the foreground. */
  onReceived(listener: Listener<MaksbasNotification>): Unsubscribe {
    this.receivedListeners.add(listener)
    return () => this.receivedListeners.delete(listener)
  }

  /** Fires when the user taps a notification. */
  onOpened(listener: Listener<MaksbasNotification>): Unsubscribe {
    this.openedListeners.add(listener)
    return () => this.openedListeners.delete(listener)
  }

  /**
   * The notification that cold-started the app, or null.
   *
   * A tap that launches the process happens before any JS listener exists, so
   * `onOpened` cannot fire for it. Check this once on mount.
   */
  async getInitialNotification(): Promise<MaksbasNotification | null> {
    if (Platform.OS !== 'android') return null
    return native.getInitialNotification()
  }

  /** Removes this device from the server and forgets the local credentials. */
  async unregister(): Promise<void> {
    if (this.deviceId && this.deviceSecret) {
      await this.requireApi()
        .deleteDevice(this.deviceId, this.deviceSecret)
        .catch((error) => this.log(`delete failed, clearing locally anyway: ${error}`))
    }

    await Promise.all([
      native.removeItem(KEY.deviceId),
      native.removeItem(KEY.deviceSecret),
      native.removeItem(KEY.fcmToken),
      native.clearCredentials(),
    ])

    this.deviceId = null
    this.deviceSecret = null
    this.initPromise = null
  }

  private attachNativeListeners(): void {
    if (!nativeEvents) return

    for (const subscription of this.nativeSubscriptions) subscription.remove()

    this.nativeSubscriptions = [
      nativeEvents.addListener(NATIVE_EVENT.received, (payload: MaksbasNotification) => {
        for (const listener of this.receivedListeners) listener(payload)
      }),
      nativeEvents.addListener(NATIVE_EVENT.opened, (payload: MaksbasNotification) => {
        for (const listener of this.openedListeners) listener(payload)
      }),
      nativeEvents.addListener(NATIVE_EVENT.tokenRefreshed, (payload: { token: string }) => {
        // Fired by onNewToken. Without this the server keeps a token FCM has
        // already retired and every send to this device silently fails.
        void this.handleTokenRefresh(payload.token)
      }),
    ]
  }

  private async handleTokenRefresh(token: string): Promise<void> {
    if (!this.deviceId || !this.deviceSecret) return
    this.log('FCM token refreshed')
    const permission = await this.getPermissionStatus()
    await this.syncToken(token, permission === 'granted').catch((error) =>
      this.log(`token sync failed: ${error}`),
    )
  }

  private requireApi(): MaksbasApi {
    if (!this.api) throw new Error('[maksbas] call init() first')
    return this.api
  }

  private assertReady(method: string): void {
    if (!this.options) {
      throw new Error(`[maksbas] call init() before ${method}()`)
    }
  }

  private log(message: string): void {
    if (this.options?.debug) console.log(`[maksbas] ${message}`)
  }
}

function stripTrailingSlash(value: string): string {
  return value.endsWith('/') ? value.slice(0, -1) : value
}

/** Optional constants exposed by the native module; absent on older builds. */
function nativeConstant(name: 'Model' | 'Language' | 'Timezone'): string | undefined {
  const constants = (NativeModules.Maksbas as { getConstants?: () => Record<string, string> })
    ?.getConstants?.()
  return constants?.[name.toLowerCase()]
}

export const Maksbas = new MaksbasClient()
export type { MaksbasClient }
