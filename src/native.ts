import { NativeEventEmitter, NativeModules, Platform } from 'react-native'
import type { MaksbasNotification, PermissionStatus } from './types'

export interface MaksbasNativeModule {
  /**
   * Hands the native side everything it needs to talk to the server on its own.
   * The messaging service runs with no JS context in the background, so it can't
   * ask JavaScript for the endpoint when a push lands.
   */
  configure(config: {
    endpoint: string
    channelName: string
    channelDescription: string
    smallIcon: string | null
    accentColor: string | null
    debug: boolean
  }): Promise<void>

  /** Stores the credentials the background service uses to report events. */
  setCredentials(deviceId: string, deviceSecret: string): Promise<void>

  clearCredentials(): Promise<void>

  getFcmToken(): Promise<string>

  requestPermission(): Promise<PermissionStatus>

  getPermissionStatus(): Promise<PermissionStatus>

  /** SharedPreferences-backed, so the SDK needs no AsyncStorage dependency. */
  getItem(key: string): Promise<string | null>
  setItem(key: string, value: string): Promise<void>
  removeItem(key: string): Promise<void>

  /** The notification that cold-started the app, if any. Consumed once. */
  getInitialNotification(): Promise<MaksbasNotification | null>

  /** Retries any delivery/open events the background service failed to send. */
  flushPendingEvents(): Promise<number>
}

const LINKING_ERROR =
  "[maksbas] The native module isn't linked.\n" +
  '- Rebuild the app after installing (`npx react-native run-android` / `pod install` + Xcode build), not just reload JS\n' +
  '- Android: make sure google-services.json is in android/app/\n' +
  '- iOS: make sure GoogleService-Info.plist is in the app target\n' +
  '- If you use Expo, this package needs a development build, not Expo Go'

const SUPPORTED_PLATFORM = Platform.OS === 'android' || Platform.OS === 'ios'

/**
 * Other platforms (web, Windows, tvOS…) get working no-ops rather than a crash,
 * so a shared codebase can call `init()` everywhere.
 */
const unsupportedNoop: MaksbasNativeModule = {
  async configure() {},
  async setCredentials() {},
  async clearCredentials() {},
  async getFcmToken() {
    throw new Error(`[maksbas] push is not supported on ${Platform.OS}`)
  },
  async requestPermission() {
    return 'unsupported'
  },
  async getPermissionStatus() {
    return 'unsupported'
  },
  async getItem() {
    return null
  },
  async setItem() {},
  async removeItem() {},
  async getInitialNotification() {
    return null
  },
  async flushPendingEvents() {
    return 0
  },
}

function resolveModule(): MaksbasNativeModule {
  if (!SUPPORTED_PLATFORM) return unsupportedNoop

  const module = NativeModules.Maksbas as MaksbasNativeModule | undefined
  if (!module) {
    return new Proxy({} as MaksbasNativeModule, {
      get() {
        throw new Error(LINKING_ERROR)
      },
    })
  }
  return module
}

export const native = resolveModule()

export const isSupported = SUPPORTED_PLATFORM && Boolean(NativeModules.Maksbas)

export const nativeEvents =
  SUPPORTED_PLATFORM && NativeModules.Maksbas ? new NativeEventEmitter(NativeModules.Maksbas) : null

export const NATIVE_EVENT = {
  received: 'maksbas:received',
  opened: 'maksbas:opened',
  tokenRefreshed: 'maksbas:tokenRefreshed',
} as const
