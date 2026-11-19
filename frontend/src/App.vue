<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError, clearCsrf, json, type Share, type VaultFile } from './api'
import { sendFile } from './resume'

type PublicFile = Pick<VaultFile, 'displayName' | 'sizeBytes' | 'mediaType'> & { expiresAt: string }
type ActiveShare = Pick<Share, 'id' | 'expiresAt'>
const user = ref('')
const username = ref('')
const password = ref('')
const registering = ref(false)
const busy = ref(false)
const loading = ref(true)
const failure = ref('')
const notice = ref('')
const files = ref<VaultFile[]>([])
const page = ref(0)
const pageSize = 20
const filter = ref('')
const input = ref<HTMLInputElement>()
const selected = ref<File>()
const uploading = ref(false)
const completed = ref(0)
const uploadLabel = ref('')
let transfer: AbortController | undefined
const dialog = ref<HTMLDialogElement>()
const sharing = ref<VaultFile>()
const activeShares = ref<ActiveShare[]>([])
const minutes = ref(60)
const link = ref<Share>()
const fileDialog = ref<HTMLDialogElement>()
const editing = ref<{ file: VaultFile; mode: 'rename' | 'delete' }>()
const displayName = ref('')
const publicFile = ref<PublicFile>()
const hash = new URLSearchParams(location.hash.slice(1))
const publicToken = hash.get('share') ?? ''
const publicPath = '/public/shares/' + encodeURIComponent(publicToken)
const visibleFiles = computed(() => files.value.filter(file =>
  file.displayName.toLocaleLowerCase().includes(filter.value.trim().toLocaleLowerCase()),
))
const percentage = computed(() => selected.value ? Math.floor(completed.value * 100 / selected.value.size) : 0)
const shareUrl = computed(() => {
  if (!link.value) return ''
  const token = link.value.url.split('/').at(-1)!
  return location.origin + '/#share=' + encodeURIComponent(token)
})

function explain(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

async function action(operation: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  failure.value = ''
  notice.value = ''
  try { await operation() } catch (error) { failure.value = explain(error) }
  finally { busy.value = false }
}

async function loadPage(index = 0) {
  const result = await api<VaultFile[]>('/files?page=' + index + '&size=' + pageSize)
  page.value = index
  files.value = result
}

async function authenticate() {
  await action(async () => {
    if (/^[a-z0-9_]{3,32}$/.test(username.value) === false) throw new Error('用户名应为 3–32 位小写字母、数字或下划线。')
    if (registering.value) {
      await api('/auth/register', json('POST', { username: username.value, password: password.value }))
      registering.value = false
      notice.value = '账号已创建，可以登录。'
      return
    }
    await api('/auth/login', { method: 'POST', body: new URLSearchParams({
      username: username.value, password: password.value,
    }) })
    password.value = ''
    clearCsrf()
    user.value = (await api<{ username: string }>('/auth/me')).username
    await loadPage()
  })
}

function choose(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  selected.value = file
  completed.value = 0
  uploadLabel.value = file ? '等待上传' : ''
}

async function upload() {
  const file = selected.value
  if (!file || uploading.value) return
  if (file.size === 0) { failure.value = '请选择非空文件。'; return }
  uploading.value = true
  failure.value = ''
  notice.value = ''
  transfer = new AbortController()
  uploadLabel.value = '正在计算 SHA-256'
  try {
    await sendFile(file, user.value, (bytes, total) => {
      completed.value = bytes
      uploadLabel.value = bytes === total ? '正在校验并合并' : '正在上传'
    }, transfer.signal)
    uploadLabel.value = '上传完成'
    notice.value = '文件已上传。'
    selected.value = undefined
    completed.value = 0
    if (input.value) input.value.value = ''
    await loadPage()
  } catch (error) {
    uploadLabel.value = transfer.signal.aborted ? '已暂停，可继续上传' : '上传未完成，可重试'
    if (!transfer.signal.aborted) failure.value = explain(error)
  } finally {
    uploading.value = false
    transfer = undefined
  }
}

async function logout() {
  await action(async () => {
    transfer?.abort()
    await api('/auth/logout', { method: 'POST' })
    clearCsrf()
    user.value = ''
    files.value = []
    selected.value = undefined
    completed.value = 0
    filter.value = ''
    closeShare()
  })
}

async function openShare(file: VaultFile) {
  sharing.value = file
  link.value = undefined
  activeShares.value = []
  minutes.value = 60
  dialog.value?.showModal()
  await action(async () => {
    activeShares.value = await api<ActiveShare[]>('/files/' + file.id + '/shares')
  })
}

function closeShare() {
  dialog.value?.close()
  sharing.value = undefined
  link.value = undefined
  activeShares.value = []
}

async function createShare() {
  const file = sharing.value
  if (!file) return
  await action(async () => {
    link.value = await api<Share>('/shares', json('POST', {
      fileId: file.id, expiresInMinutes: minutes.value,
    }))
    activeShares.value.unshift({ id: link.value.id, expiresAt: link.value.expiresAt })
  })
}

async function copyLink() {
  await action(async () => {
    await navigator.clipboard.writeText(shareUrl.value)
    notice.value = '链接已复制。'
  })
}

async function revokeShare(id: string) {
  await action(async () => {
    await api('/shares/' + id, { method: 'DELETE' })
    activeShares.value = activeShares.value.filter(share => share.id !== id)
    if (link.value?.id === id) link.value = undefined
    notice.value = '分享已撤销。'
  })
}

function openFileDialog(file: VaultFile, mode: 'rename' | 'delete') {
  editing.value = { file, mode }
  displayName.value = file.displayName
  failure.value = ''
  notice.value = ''
  fileDialog.value?.showModal()
}

function closeFileDialog() {
  fileDialog.value?.close()
  editing.value = undefined
}

async function saveFileChange() {
  const change = editing.value
  if (!change) return
  await action(async () => {
    const path = '/files/' + change.file.id
    if (change.mode === 'rename') {
      await api(path, json('PATCH', { displayName: displayName.value }))
    } else {
      await api(path, { method: 'DELETE' })
    }
    closeFileDialog()
    await loadPage(change.mode === 'delete' && files.value.length === 1 ? Math.max(0, page.value - 1) : page.value)
    notice.value = change.mode === 'rename' ? '文件已重命名。' : '文件已删除。'
  })
}

function size(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KiB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MiB'
}

function date(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value + 'Z'))
}

onMounted(async () => {
  try {
    if (publicToken) publicFile.value = await api<PublicFile>(publicPath)
    else {
      user.value = (await api<{ username: string }>('/auth/me')).username
      await loadPage()
    }
  } catch (error) {
    if (!publicToken && error instanceof ApiError && error.status === 401) return
    failure.value = explain(error)
  } finally { loading.value = false }
})
</script>

<template>
  <header class="topbar">
    <a class="brand" href="/" aria-label="FileVault 首页">
      <span class="brand-mark" aria-hidden="true">F</span><span>FileVault</span>
    </a>
    <div v-if="user" class="userbar">
      <span class="account">{{ user }}</span>
      <button class="quiet" type="button" :disabled="busy" @click="logout">退出</button>
    </div>
  </header>

  <main v-if="loading" class="narrow" aria-busy="true">正在加载…</main>
  <main v-else-if="publicToken" class="narrow">
    <section class="card form-card">
      <h1>共享文件</h1>
      <p v-if="failure" class="alert error" role="alert">{{ failure }}</p>
      <template v-if="publicFile">
        <h2>{{ publicFile.displayName }}</h2>
        <p class="muted">{{ size(publicFile.sizeBytes) }} · 有效至 {{ date(publicFile.expiresAt) }}</p>
        <a class="primary button" :href="'/api/v1' + publicPath + '/download'">下载附件</a>
      </template>
    </section>
  </main>
  <main v-else-if="!user" class="narrow">
    <section class="card form-card">
      <h1>{{ registering ? '创建账号' : '登录文件库' }}</h1>
      <form @submit.prevent="authenticate">
        <label>用户名<input v-model="username" autocomplete="username" required maxlength="32" /></label>
        <label>密码<input v-model="password" type="password"
          :autocomplete="registering ? 'new-password' : 'current-password'" required /></label>
        <p v-if="failure" class="alert error" role="alert">{{ failure }}</p>
        <p v-if="notice" class="alert" role="status">{{ notice }}</p>
        <button class="primary" type="submit" :disabled="busy">{{ registering ? '注册' : '登录' }}</button>
      </form>
      <button class="quiet switch" type="button" @click="registering = !registering; failure = ''; notice = ''">
        {{ registering ? '返回登录' : '创建账号' }}
      </button>
    </section>
  </main>

  <main v-else class="workspace">
    <div class="page-heading"><h1>我的文件</h1></div>
    <section class="card uploader" aria-labelledby="upload-heading">
      <div class="section-title">
        <span class="upload-mark" aria-hidden="true">↑</span>
        <div><h2 id="upload-heading">上传文件</h2><p class="muted">支持断点续传 · 默认上限 200 MiB</p></div>
      </div>
      <label class="picker" :class="{ disabled: uploading }">
        <span>{{ selected ? selected.name : '选择文件' }}</span>
        <input ref="input" type="file" accept=".pdf,.txt,.md,.csv,.png,.jpg,.jpeg"
          :disabled="uploading" @change="choose" />
      </label>
      <div v-if="selected" class="progress-area">
        <div class="progress-label"><span>{{ uploadLabel }}</span><span>{{ percentage }}%</span></div>
        <progress :value="completed" :max="selected.size || 1" aria-label="上传进度" />
      </div>
      <div class="actions">
        <button class="primary" type="button" :disabled="!selected || uploading" @click="upload">
          {{ selected && completed > 0 ? '继续上传' : '开始上传' }}
        </button>
        <button v-if="uploading" type="button" @click="transfer?.abort()">暂停</button>
      </div>
    </section>
    <p v-if="failure" class="alert error" role="alert">{{ failure }}</p>
    <p v-if="notice" class="alert" role="status">{{ notice }}</p>
    <section class="card listing">
      <div class="table-toolbar">
        <h2>文件列表</h2>
        <input v-model="filter" type="search" placeholder="筛选当前页" aria-label="筛选当前页文件" />
      </div>
      <div v-if="!visibleFiles.length" class="empty">{{ filter ? '没有匹配的文件' : '这一页还没有文件' }}</div>
      <div v-else class="table-scroll">
        <table>
          <thead><tr><th>文件名</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="file in visibleFiles" :key="file.id">
              <td><span class="extension">{{ file.displayName.split('.').at(-1) }}</span>{{ file.displayName }}</td>
              <td>{{ size(file.sizeBytes) }}</td><td>{{ date(file.createdAt) }}</td>
              <td class="row-actions">
                <a class="quiet" :href="'/api/v1/files/' + file.id + '/download'">下载</a>
                <button class="quiet" type="button" :disabled="busy" @click="openShare(file)">分享</button>
                <button class="quiet" type="button" :disabled="busy" @click="openFileDialog(file, 'rename')">重命名</button>
                <button class="quiet" type="button" :disabled="busy" @click="openFileDialog(file, 'delete')">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pagination">
        <button type="button" :disabled="page === 0 || busy" @click="action(() => loadPage(page - 1))">上一页</button>
        <span>第 {{ page + 1 }} 页</span>
        <button type="button" :disabled="files.length < pageSize || busy" @click="action(() => loadPage(page + 1))">下一页</button>
      </footer>
    </section>
  </main>
  <dialog ref="dialog" aria-labelledby="share-dialog-title" @cancel="closeShare">
    <div v-if="sharing" class="form-card">
      <div class="dialog-heading"><h2 id="share-dialog-title">分享文件</h2><button class="quiet" type="button" :disabled="busy" @click="closeShare">关闭</button></div>
      <p class="muted">{{ sharing.displayName }}</p>
      <p v-if="failure" class="alert error" role="alert">{{ failure }}</p>
      <p v-if="notice" class="alert" role="status">{{ notice }}</p>
      <form v-if="!link" @submit.prevent="createShare">
        <label>有效期（分钟）<input v-model.number="minutes" type="number" min="1" max="10080" required /></label>
        <button class="primary" type="submit" :disabled="busy">生成链接</button>
      </form>
      <div v-else>
        <label>分享链接<input :value="shareUrl" readonly /></label>
        <p class="muted">有效至 {{ date(link.expiresAt) }}</p>
        <div class="actions">
          <button class="primary" type="button" :disabled="busy" @click="copyLink">复制链接</button>
        </div>
      </div>
      <ul v-if="activeShares.length" class="share-list" aria-label="有效链接">
        <li v-for="share in activeShares" :key="share.id">
          <span class="muted">有效至 {{ date(share.expiresAt) }}</span>
          <button type="button" :disabled="busy" @click="revokeShare(share.id)">撤销链接</button>
        </li>
      </ul>
    </div>
  </dialog>
  <dialog ref="fileDialog" aria-labelledby="file-dialog-title" @cancel="closeFileDialog">
    <div v-if="editing" class="form-card">
      <div class="dialog-heading">
        <h2 id="file-dialog-title">{{ editing.mode === 'rename' ? '重命名文件' : '删除文件' }}</h2>
        <button class="quiet" type="button" :disabled="busy" @click="closeFileDialog">关闭</button>
      </div>
      <p v-if="failure" class="alert error" role="alert">{{ failure }}</p>
      <form @submit.prevent="saveFileChange">
        <label v-if="editing.mode === 'rename'">文件名<input v-model="displayName" required /></label>
        <p v-else>删除 {{ editing.file.displayName }} 后，已有分享链接会失效。</p>
        <button class="primary" type="submit" :disabled="busy">
          {{ editing.mode === 'rename' ? '保存' : '确认删除' }}
        </button>
      </form>
    </div>
  </dialog>
</template>
