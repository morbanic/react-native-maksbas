import type { StoredAttributes } from './types'

export class MaksbasApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'MaksbasApiError'
  }
}

interface RequestOptions {
  method: string
  path: string
  token: string
  body?: unknown
  /** Total attempts including the first. @default 3 */
  attempts?: number
}

export interface RegisterResponse {
  deviceId: string
  deviceSecret: string
  attributes: StoredAttributes
  notificationsEnabled: boolean
}

/**
 * Thin HTTP client for the Maksbas endpoints the device is allowed to call.
 *
 * Retries are deliberately narrow: network failures and 5xx only. A 4xx means
 * the request itself is wrong, and repeating it just wastes battery.
 */
export class MaksbasApi {
  constructor(
    private readonly endpoint: string,
    private readonly publicKey: string,
    private readonly debug = false,
  ) {}

  register(body: {
    fcmToken: string
    platform: string
    attributes?: StoredAttributes
    notificationsEnabled: boolean
    appVersion?: string
    sdkVersion?: string
    deviceModel?: string
    osVersion?: string
    language?: string
    timezone?: string
  }): Promise<RegisterResponse> {
    return this.request<RegisterResponse>({
      method: 'POST',
      path: '/devices',
      token: this.publicKey,
      body,
    })
  }

  updateDevice(
    deviceId: string,
    deviceSecret: string,
    body: Record<string, unknown>,
  ): Promise<unknown> {
    return this.request({
      method: 'PATCH',
      path: `/devices/${deviceId}`,
      token: deviceSecret,
      body,
    })
  }

  setAttributes(
    deviceId: string,
    deviceSecret: string,
    attributes: Record<string, string | null>,
  ): Promise<{ attributes: StoredAttributes }> {
    return this.request({
      method: 'PATCH',
      path: `/devices/${deviceId}/attributes`,
      token: deviceSecret,
      body: attributes,
    })
  }

  getDevice(deviceId: string, deviceSecret: string): Promise<Record<string, unknown>> {
    return this.request({
      method: 'GET',
      path: `/devices/${deviceId}`,
      token: deviceSecret,
    })
  }

  deleteDevice(deviceId: string, deviceSecret: string): Promise<unknown> {
    return this.request({
      method: 'DELETE',
      path: `/devices/${deviceId}`,
      token: deviceSecret,
    })
  }

  private async request<T>({ method, path, token, body, attempts = 3 }: RequestOptions): Promise<T> {
    const url = `${this.endpoint}${path}`
    let lastError: unknown

    for (let attempt = 1; attempt <= attempts; attempt++) {
      try {
        const response = await fetch(url, {
          method,
          headers: {
            authorization: `Bearer ${token}`,
            ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
          },
          ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        })

        const text = await response.text()
        const payload = text ? safeParse(text) : {}

        if (response.ok) return payload as T

        const error = new MaksbasApiError(
          response.status,
          (payload as { error?: { code?: string } })?.error?.code ?? 'unknown',
          (payload as { error?: { message?: string } })?.error?.message ?? text.slice(0, 200),
        )

        // The request is wrong, not the connection. Retrying changes nothing.
        if (response.status < 500) throw error

        lastError = error
      } catch (error) {
        if (error instanceof MaksbasApiError && error.status < 500) throw error
        lastError = error
      }

      if (attempt < attempts) {
        const delay = 2 ** (attempt - 1) * 500
        this.log(`retrying ${method} ${path} in ${delay}ms`)
        await sleep(delay)
      }
    }

    throw lastError
  }

  private log(message: string): void {
    if (this.debug) console.log(`[maksbas] ${message}`)
  }
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return {}
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
