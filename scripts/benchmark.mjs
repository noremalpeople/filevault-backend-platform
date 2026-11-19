import assert from 'node:assert/strict'
import { createHash, randomBytes } from 'node:crypto'
import { performance } from 'node:perf_hooks'

const origin = new URL(process.env.FILEVAULT_BENCH_URL ?? 'http://127.0.0.1:8082')
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1') {
  throw new Error('Use a local FileVault instance on 127.0.0.1.')
}
const base = new URL('/api/v1/', origin)
const identity = 'bench_' + randomBytes(6).toString('hex')
const password = randomBytes(16).toString('hex')
let cookie
let csrf

async function request(path, status, { method = 'GET', body, contentType } = {}) {
  const headers = new Headers()
  if (cookie) headers.set('Cookie', cookie)
  if (csrf && method !== 'GET') headers.set(csrf.headerName, csrf.token)
  if (contentType) headers.set('Content-Type', contentType)
  const response = await fetch(new URL(path, base), {
    method, body, headers, redirect: 'error', signal: AbortSignal.timeout(30000),
  })
  const newCookie = response.headers.getSetCookie().find(value => value.startsWith('JSESSIONID='))
  if (newCookie) cookie = newCookie.split(';', 1)[0]
  assert.equal(response.status, status, method + ' ' + path)
  return response
}

async function postJson(path, status, fields) {
  return (await request(path, status, {
    method: 'POST', contentType: 'application/json', body: JSON.stringify(fields),
  })).json()
}

const digest = bytes => createHash('sha256').update(bytes).digest('hex')

csrf = await (await request('auth/csrf', 200)).json()
await postJson('auth/register', 201, { username: identity, password })
await request('auth/login', 204, {
  method: 'POST', body: new URLSearchParams({ username: identity, password }),
})
csrf = await (await request('auth/csrf', 200)).json()

const records = []
for (let trial = 1; trial <= 3; trial++) {
  const sample = Buffer.alloc(16 * 1024 * 1024, 'benchmark,100,200\n')
  sample.write(identity + ',' + trial + '\n')
  const expectedSha256 = digest(sample)
  const start = performance.now()
  const session = await postJson('uploads', 201, {
    filename: 'benchmark-' + trial + '.csv', totalSize: sample.length, expectedSha256,
  })
  for (let index = 0; index < session.chunkCount; index++) {
    const offset = index * session.chunkSize
    await request('uploads/' + session.id + '/chunks/' + index, 204, {
      method: 'PUT', body: sample.subarray(offset, offset + session.chunkSize),
      contentType: 'application/octet-stream',
    })
  }
  const result = await (await request('uploads/' + session.id + '/complete', 200, { method: 'POST' })).json()
  const milliseconds = performance.now() - start
  const response = await request('files/' + result.fileId + '/download', 200)
  assert.equal(digest(Buffer.from(await response.arrayBuffer())), expectedSha256)
  records.push({
    trial, bytes: sample.length, chunks: session.chunkCount, concurrentUploads: 1,
    milliseconds: Number(milliseconds.toFixed(2)),
    mebibytesPerSecond: Number((sample.length / (1024 * 1024) / (milliseconds / 1000)).toFixed(2)),
  })
}
console.log(JSON.stringify(records, null, 2))
