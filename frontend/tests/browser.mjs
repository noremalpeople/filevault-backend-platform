import { chromium } from 'playwright-core'
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'

const base = new URL(process.env.FILEVAULT_BROWSER_URL ?? 'http://127.0.0.1:8082')
if (base.protocol !== 'http:' || base.hostname !== '127.0.0.1') {
  throw new Error('Use a local FileVault instance on 127.0.0.1.')
}
const browser = await chromium.launch({
  executablePath: process.env.FILEVAULT_CHROME,
  channel: process.env.FILEVAULT_CHROME ? undefined : 'chrome',
  headless: true,
  chromiumSandbox: true,
})
try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 880 }, acceptDownloads: true })
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  const errors = []
  page.on('pageerror', error => errors.push(error.name))
  const username = 'demo_' + randomBytes(4).toString('hex')
  const password = randomBytes(16).toString('hex')
  await page.goto(base.href)
  await page.getByRole('heading', { name: '登录文件库' }).waitFor()
  await page.getByRole('button', { name: '创建账号' }).click()
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '注册', exact: true }).click()
  await page.getByText('账号已创建，可以登录。').waitFor()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('heading', { name: '我的文件' }).waitFor()

  const csv = Buffer.alloc(13 * 1024 * 1024, 'filevault,1,2,3\n')
  const fixture = { name: 'sample-workspace.csv', mimeType: 'text/csv', buffer: csv }
  await page.locator('input[type=file]').setInputFiles(fixture)
  let pauseRoute
  const blocked = new Promise(resolve => { pauseRoute = resolve })
  await page.route('**/chunks/1', async route => {
    pauseRoute(route)
  }, { times: 1 })
  await page.getByRole('button', { name: '开始上传' }).click()
  const timeout = AbortSignal.timeout(15_000)
  const intercepted = await Promise.race([
    blocked,
    new Promise((_, reject) => timeout.addEventListener('abort', () => reject(timeout.reason), { once: true })),
  ])
  await page.getByRole('button', { name: '暂停' }).click()
  await intercepted.abort().catch(() => {})
  await page.getByText('已暂停，可继续上传').waitFor()
  await page.reload()
  await page.getByRole('heading', { name: '我的文件' }).waitFor()
  await page.locator('input[type=file]').setInputFiles(fixture)
  const replayed = []
  const track = request => {
    if (request.method() === 'PUT') replayed.push(Number(request.url().split('/').at(-1)))
  }
  page.on('request', track)
  await page.getByRole('button', { name: '开始上传' }).click()
  await page.getByText('文件已上传。').waitFor()
  assert.equal(await page.getByRole('button', { name: '开始上传' }).isDisabled(), true)
  page.off('request', track)
  assert.deepEqual(replayed, [1, 2, 3])
  const row = page.getByRole('row').filter({ hasText: fixture.name })
  const downloadEvent = page.waitForEvent('download')
  await row.getByRole('link', { name: '下载' }).click()
  const download = await downloadEvent
  assert.equal(download.suggestedFilename(), fixture.name)
  assert.deepEqual(await readFile(await download.path()), csv)

  const screenshots = process.env.FILEVAULT_SCREENSHOTS
  if (screenshots) {
    await mkdir(screenshots, { recursive: true })
    await page.screenshot({ path: join(screenshots, 'filevault-workspace.png'), fullPage: true })
  }

  await row.getByRole('button', { name: '分享' }).click()
  if (screenshots) {
    await page.getByRole('button', { name: '生成链接' }).waitFor()
    await page.screenshot({ path: join(screenshots, 'filevault-share.png'), fullPage: true })
  }
  await page.getByRole('button', { name: '生成链接' }).click()
  const shareLink = await page.getByLabel('分享链接').inputValue()
  const guest = await browser.newContext({ acceptDownloads: true })
  const publicPage = await guest.newPage()
  await publicPage.goto(shareLink)
  await publicPage.getByRole('heading', { name: fixture.name }).waitFor()
  const sharedEvent = publicPage.waitForEvent('download')
  await publicPage.getByRole('link', { name: '下载附件' }).click()
  assert.deepEqual(await readFile(await (await sharedEvent).path()), csv)
  await page.reload()
  await row.getByRole('button', { name: '分享' }).click()
  assert.equal(await page.getByLabel('分享链接').count(), 0)
  await page.getByRole('button', { name: '撤销链接' }).click()
  await page.getByRole('dialog').getByText('分享已撤销。').waitFor()
  await publicPage.reload()
  await publicPage.getByRole('alert').waitFor()
  await page.getByRole('button', { name: '关闭' }).click()
  await row.getByRole('button', { name: '重命名' }).click()
  await page.getByLabel('文件名', { exact: true }).fill('renamed-workspace.csv')
  await page.getByRole('button', { name: '保存' }).click()
  const renamed = page.getByRole('row').filter({ hasText: 'renamed-workspace.csv' })
  await renamed.getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await page.getByText('文件已删除。').waitFor()
  assert.equal(await renamed.count(), 0)

  const restartFixture = {
    name: 'restart-after-checksum.txt', mimeType: 'text/plain', buffer: Buffer.from('FileVault restart fixture'),
  }
  await page.route('**/api/v1/uploads', async route => {
    const declared = route.request().postDataJSON()
    await route.continue({ postData: JSON.stringify({ ...declared, expectedSha256: '0'.repeat(64) }) })
  }, { times: 1 })
  await page.locator('input[type=file]').setInputFiles(restartFixture)
  await page.getByRole('button', { name: '开始上传' }).click()
  await page.getByText('上传校验失败，请重新开始').waitFor()
  await page.getByRole('button', { name: '重新开始上传' }).click()
  await page.getByText('文件已上传。').waitFor()
  await page.getByRole('row').filter({ hasText: restartFixture.name }).waitFor()

  await page.getByRole('button', { name: '退出' }).click()
  await page.getByRole('heading', { name: '登录文件库' }).waitFor()
  assert.deepEqual(errors, [])
  console.log('PASS Chromium UI authentication, resume after reload, verified downloads, sharing, revoke, rename, delete and restart after checksum failure')
} catch (error) {
  console.error('Browser acceptance failed', error.name, String(error.message).replace(/share=[A-Za-z0-9_-]{43}/g, 'share=[redacted]'))
  process.exitCode = 1
} finally {
  await browser.close()
}
