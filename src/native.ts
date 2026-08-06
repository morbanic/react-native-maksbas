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
  '- Rebuild the app after installing (`npx react-native run-android`), not just reload JS\n' +
  '- Make sure google-services.json is in android/app/\n' +
  '- If you use Expo, this package needs a development build, not Expo Go'

/**
 * iOS gets working no-ops rather than a crash.
 *
 * The decision was Android-only, but a shared codebase will still call
 * `init()` on both platforms. Throwing there would make every iOS launch fail
 * over a feature that simply isn't implemented yet.
 */
const iosNoop: MaksbasNativeModule = {
  async configure() {},
  async setCredentials() {},
  async clearCredentials() {},
  async getFcmToken() {
    throw new Error('[maksbas] iOS push is not implemented in this version')
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
  if (Platform.OS !== 'android') return iosNoop

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

export const isSupported = Platform.OS === 'android' && Boolean(NativeModules.Maksbas)

export const nativeEvents =
  Platform.OS === 'android' && NativeModules.Maksbas
    ? new NativeEventEmitter(NativeModules.Maksbas)
    : null

export const NATIVE_EVENT = {
  received: 'maksbas:received',
  opened: 'maksbas:opened',
  tokenRefreshed: 'maksbas:tokenRefreshed',
} as const
