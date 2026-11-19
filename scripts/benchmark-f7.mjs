import assert from 'node:assert/strict'
import { createHash, randomBytes } from 'node:crypto'
import { performance } from 'node:perf_hooks'

const origin = new URL(process.env.FILEVAULT_BENCH_URL ?? 'http://127.0.0.1:8082')
assert.equal(origin.protocol, 'http:')
assert.equal(origin.hostname, '127.0.0.1')
const api = new URL('/api/v1/', origin)
const mib = 1024 * 1024

async function client() {
  let cookie
  let csrf
  const username = 'load_' + randomBytes(8).toString('hex')
  const password = randomBytes(16).toString('hex')
  async function request(path, status, options = {}) {
    const method = options.method ?? 'GET'
    const headers = new Headers(options.headers)
    if (cookie) headers.set('Cookie', cookie)
    if (csrf && method !== 'GET') headers.set(csrf.headerName, csrf.token)
    const response = await fetch(new URL(path, api), { ...options, method, headers,
      redirect: 'error', signal: AbortSignal.timeout(120_000) })
    const session = response.headers.getSetCookie().find(value => value.startsWith('JSESSIONID='))
    if (session) cookie = session.split(';', 1)[0]
    assert.equal(response.status, status, method + ' ' + path)
    return response
  }
  async function post(path, value, status) {
    return (await request(path, status, { method: 'POST', body: JSON.stringify(value),
      headers: { 'Content-Type': 'application/json' } })).json()
  }
  csrf = await (await request('auth/csrf', 200)).json()
  await post('auth/register', { username, password }, 201)
  await request('auth/login', 204, { method: 'POST', body: new URLSearchParams({ username, password }) })
  csrf = await (await request('auth/csrf', 200)).json()
  return { post, request }
}

async function trial(account, sizeMiB, label) {
  const data = Buffer.alloc(sizeMiB * mib, 'fixture,100,200\n')
  data.write(randomBytes(16).toString('hex') + ',1,2\n')
  const expectedSha256 = createHash('sha256').update(data).digest('hex')
  const started = performance.now()
  const session = await account.post('uploads', { filename: 'f7-' + label + '.csv',
    totalSize: data.length, expectedSha256 }, 201)
  for (let i = 0; i < session.chunkCount; i++) {
    const start = i * session.chunkSize
    await account.request('uploads/' + session.id + '/chunks/' + i, 204, { method: 'PUT',
      headers: { 'Content-Type': 'application/octet-stream' }, body: data.subarray(start, start + session.chunkSize) })
  }
  const saved = await (await account.request('uploads/' + session.id + '/complete', 200, { method: 'POST' })).json()
  const ended = performance.now()
  const response = await account.request('files/' + saved.fileId + '/download', 200)
  const downloadHash = createHash('sha256')
  let received = 0
  for await (const chunk of response.body) {
    received += chunk.length
    downloadHash.update(chunk)
  }
  const downloaded = performance.now()
  assert.equal(received, data.length)
  assert.equal(downloadHash.digest('hex'), expectedSha256)
  return { label, sizeMiB, chunks: session.chunkCount, started, ended,
    uploadMs: +((ended - started).toFixed(2)), downloadMs: +((downloaded - ended).toFixed(2)),
    uploadMiBPerSecond: +(sizeMiB * 1000 / (ended - started)).toFixed(2) }
}

const users = await Promise.all([client(), client(), client()])
const groups = [
  [{ user: users[0], size: 1, label: 'single-1' }],
  [{ user: users[0], size: 16, label: 'single-16' }],
  [{ user: users[0], size: 100, label: 'single-100' }],
  [{ user: users[2], size: 190, label: 'single-190' }],
  [{ user: users[0], size: 16, label: 'parallel-a' }, { user: users[1], size: 16, label: 'parallel-b' }],
]
const records = []
for (const group of groups) {
  const outcomes = await Promise.allSettled(group.map(item => trial(item.user, item.size, item.label)))
  const successful = outcomes.filter(item => item.status === 'fulfilled').map(item => item.value)
  const errors = outcomes.flatMap((item, i) => item.status === 'rejected'
    ? [{ label: group[i].label, error: item.reason?.name ?? 'Error' }] : [])
  const spread = successful.length ? Math.max(...successful.map(r => r.ended)) - Math.min(...successful.map(r => r.started)) : null
  records.push({ concurrentUploads: group.length, successes: successful.length, errors,
    groupUploadWindowMs: spread === null ? null : +spread.toFixed(2),
    trials: successful.map(({ started, ended, ...rest }) => rest) })
}
console.log(JSON.stringify(records, null, 2))
if (records.some(group => group.errors.length > 0)) process.exitCode = 1
