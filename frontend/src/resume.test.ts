import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrf } from './api'
import { chunkBounds, fingerprint, missingChunks, resumeKey, sendFile } from './resume'

afterEach(() => {
  clearCsrf()
  vi.unstubAllGlobals()
})

async function cachedFile() {
  const file = new File(['abcdef'], 'repair.txt')
  const key = resumeKey('alice', file, await fingerprint(file))
  const cache = new Map([[key, 'previous']])
  vi.stubGlobal('localStorage', {
    getItem: (id: string) => cache.get(id) ?? null,
    setItem: (id: string, value: string) => cache.set(id, value),
    removeItem: (id: string) => cache.delete(id),
  })
  return { file, key, cache }
}

describe('upload resumption', () => {
  it('selects only missing indexes in order', () => {
    expect(missingChunks(4, [2, 0, 2])).toEqual([1, 3])
    expect(missingChunks(2, [1, 0])).toEqual([])
  })
  it('uses the true byte length of the final chunk', () => {
    expect(chunkBounds(2, 10, 4)).toEqual([8, 10])
  })
  it('does not reuse a cached session across accounts or filenames', () => {
    expect(resumeKey('alice', { name: 'x.txt', size: 3 }, 'hash'))
      .not.toEqual(resumeKey('bob', { name: 'x.txt', size: 3 }, 'hash'))
    expect(resumeKey('alice', { name: 'x.txt', size: 3 }, 'hash'))
      .not.toEqual(resumeKey('alice', { name: 'y.txt', size: 3 }, 'hash'))
  })
  it('hashes file bytes with SHA-256', async () => {
    await expect(fingerprint(new Blob(['abc']))).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })
  it('resends a damaged index while skipping a verified index', async () => {
    const { file, key, cache } = await cachedFile()
    const fetch = vi.fn(async (url: string) => {
      if (url.endsWith('/previous')) return Response.json({
        id: 'previous', status: 'OPEN', totalSize: 6, chunkCount: 2, chunkSize: 3,
        received: [0], repair: [{ index: 1, reason: 'CORRUPT' }],
      })
      if (url.endsWith('/csrf')) return Response.json({ headerName: 'X-CSRF', token: 'test-token' })
      if (url.endsWith('/chunks/1')) return new Response(null, { status: 204 })
      if (url.endsWith('/complete')) return Response.json({ fileId: 'file' })
      throw new Error('Unexpected request')
    })
    vi.stubGlobal('fetch', fetch)
    await expect(sendFile(file, 'alice', vi.fn(), new AbortController().signal)).resolves.toBe('file')
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/uploads/previous', '/api/v1/auth/csrf', '/api/v1/uploads/previous/chunks/1',
      '/api/v1/uploads/previous/complete',
    ])
    expect(cache.has(key)).toBe(false)
  })
  it('requires an explicit restart for a failed upload and creates a fresh session', async () => {
    const { file } = await cachedFile()
    const fetch = vi.fn(async (url: string) => {
      if (url.endsWith('/previous')) return Response.json({ status: 'FAILED' })
      if (url.endsWith('/csrf')) return Response.json({ headerName: 'X-CSRF', token: 'test-token' })
      if (url.endsWith('/previous/abort')) return Response.json({ code: 'UPLOAD_FAILED' }, { status: 409 })
      if (url.endsWith('/uploads')) return Response.json({ id: 'fresh', chunkCount: 1, chunkSize: 6, received: [] })
      if (url.endsWith('/fresh/chunks/0')) return new Response(null, { status: 204 })
      if (url.endsWith('/fresh/complete')) return Response.json({ fileId: 'new-file' })
      throw new Error('Unexpected request')
    })
    vi.stubGlobal('fetch', fetch)
    const signal = new AbortController().signal
    await expect(sendFile(file, 'alice', vi.fn(), signal)).rejects.toMatchObject({ code: 'UPLOAD_FAILED' })
    expect(fetch).toHaveBeenCalledTimes(1)
    await expect(sendFile(file, 'alice', vi.fn(), signal, true)).resolves.toBe('new-file')
  })
})
