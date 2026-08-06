/**
 * Attribute values are strings on the server. Numbers and booleans are accepted
 * here and coerced, and `null` deletes the key.
 */
export type AttributeValue = string | number | boolean | null

export type Attributes = Record<string, AttributeValue>

/** What the server stores and returns. */
export type StoredAttributes = Record<string, string>

export interface MaksbasOptions {
  /**
   * Where `maksbas-nextjs` is mounted, including the base path.
   * @example 'https://app.hr/api/maksbas'
   */
  endpoint: string

  /** The public key. Safe to ship in the app — it can only register devices. */
  publicKey: string

  /** Display name of the notification channel shown in Android settings. */
  channelName?: string

  /** Longer description shown under the channel name. */
  channelDescription?: string

  /**
   * Drawable name for the small icon in the status bar, without extension.
   * Falls back to the app icon, which Android renders as a white square on
   * many devices — worth setting.
   * @example 'ic_notification'
   */
  smallIcon?: string

  /** Accent colour for the small icon, as `#RRGGBB`. */
  accentColor?: string

  /** Logs every SDK step. Leave off in production. */
  debug?: boolean
}

export interface MaksbasNotification {
  /** Server-side notification id. Stable across devices. */
  id: string
  title: string
  body: string
  image?: string
  deeplink?: string
  /** Whatever you passed as `data` when sending. Always string values. */
  data: Record<string, string>
}

export interface DeviceInfo {
  id: string
  attributes: StoredAttributes
  notificationsEnabled: boolean
  fcmToken: string
}

export type PermissionStatus = 'granted' | 'denied' | 'not_determined' | 'unsupported'

export type Unsubscribe = () => void
