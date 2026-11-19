import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'
import { api, ApiError, json, type UploadSession } from './api'

export function chunkBounds(index: number, length: number, chunkSize: number): [number, number] {
  const start = index * chunkSize
  return [start, Math.min(start + chunkSize, length)]
}

export function missingChunks(count: number, received: number[]): number[] {
  const done = new Set(received)
  return Array.from({ length: count }, (_, index) => index).filter(index => !done.has(index))
}

export function resumeKey(username: string, file: Pick<File, 'name' | 'size'>, digest: string) {
  return ['filevault', username, file.size, digest, encodeURIComponent(file.name)].join(':')
}

export async function fingerprint(file: Blob, signal?: AbortSignal): Promise<string> {
  const hash = sha256.create()
  const reader = file.stream().getReader()
  try {
    for (;;) {
      signal?.throwIfAborted()
      const { value, done } = await reader.read()
      if (done) return bytesToHex(hash.digest())
      hash.update(value)
    }
  } finally {
    await reader.cancel()
    reader.releaseLock()
  }
}

export async function sendFile(
  file: File, username: string, onProgress: (completed: number, total: number) => void, signal: AbortSignal,
): Promise<string> {
  const expectedSha256 = await fingerprint(file, signal)
  signal.throwIfAborted()
  const key = resumeKey(username, file, expectedSha256)
  const existing = localStorage.getItem(key)
  let session: UploadSession | undefined
  if (existing) {
    try {
      const found = await api<UploadSession>('/uploads/' + existing, { signal })
      if (found.status === 'COMPLETED' && found.fileId) {
        localStorage.removeItem(key)
        onProgress(file.size, file.size)
        return found.fileId
      }
      if (found.status === 'OPEN' && found.totalSize === file.size) session = found
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) throw error
    }
  }
  if (!session) {
    session = await api<UploadSession>('/uploads', {
      ...json('POST', { filename: file.name, totalSize: file.size, expectedSha256 }), signal,
    })
    localStorage.setItem(key, session.id)
  }
  const url = '/uploads/' + session.id
  const missing = missingChunks(session.chunkCount, session.received)
  const storage = session.chunkSize
  let completed = file.size - missing.reduce((sum, index) => {
    const [start, end] = chunkBounds(index, file.size, storage)
    return sum + end - start
  }, 0)
  onProgress(completed, file.size)
  for (const index of missing) {
    signal.throwIfAborted()
    const [start, end] = chunkBounds(index, file.size, storage)
    await api(url + '/chunks/' + index, {
      method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' }, body: file.slice(start, end), signal,
    })
    completed += end - start
    onProgress(completed, file.size)
  }
  const result = await api<{ fileId: string }>(url + '/complete', { method: 'POST', signal })
  localStorage.removeItem(key)
  return result.fileId
}
