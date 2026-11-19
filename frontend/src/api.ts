export interface VaultFile {
  id: string
  displayName: string
  sha256: string
  sizeBytes: number
  mediaType: string
  createdAt: string
}

export interface UploadSession {
  id: string
  chunkSize: number
  chunkCount: number
  totalSize: number
  status: 'OPEN' | 'COMPLETED' | 'ABORTED' | 'EXPIRED'
  received: number[]
  fileId: string | null
}

export interface Share {
  id: string
  url: string
  expiresAt: string
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) { super(message) }
}

let csrf: { headerName: string; token: string } | undefined

export function clearCsrf() { csrf = undefined }

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers = new Headers(options.headers)
  if (method !== 'GET') {
    csrf ??= await api<typeof csrf & object>('/auth/csrf')
    headers.set(csrf.headerName, csrf.token)
  }
  const response = await fetch('/api/v1' + path, { ...options, headers, credentials: 'same-origin' })
  if (!response.ok) {
    const error = await response.json().catch(() => undefined) as { message?: string } | undefined
    throw new ApiError(response.status, error?.message ?? '请求失败，请重试。')
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}

export function json(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}
