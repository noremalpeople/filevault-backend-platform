# API

| 方法 | URL | 响应 |
|---|---|---|
| GET | /api/v1/health | 200，包含服务名与存活状态 |
| GET | /actuator/health | 200 UP 或 503 DOWN，包含数据源健康状态 |

| 方法 | URL | 响应 |
|---|---|---|
| GET | /api/v1/auth/csrf | 200，返回本会话的 CSRF 请求头名称和 token |
| POST | /api/v1/auth/register | 201，JSON `{"username":"demo_user","password":"<password>"}` |
| POST | /api/v1/auth/login | 204，表单编码的 username 和 password |
| POST | /api/v1/auth/logout | 204，销毁认证会话 |
| GET | /api/v1/auth/me | 200，当前用户名 |
| POST | /api/v1/files | 201，multipart 字段 `file`，上限 10 MiB |
| GET | /api/v1/files | 200，页码从 0 开始，`size` 取 1–100，`prefix` 为可选文件名前缀 |
| GET | /api/v1/files/stats | 200，当前用户可见文件数 `files` 和逻辑总字节数 `logicalBytes` |
| GET | /api/v1/files/{id} | 200，仅当前所有者可读 |
| GET | /api/v1/files/{id}/download | 200，流式附件 |
| PATCH | /api/v1/files/{id} | 204，JSON `{"displayName":"renamed.txt"}` |
| DELETE | /api/v1/files/{id} | 204，软删除 |

同源客户端保存服务端会话 Cookie。注册、登录、上传、改名、删除和退出都携带 GET csrf 获得的请求头。登录完成后重新取得 CSRF token。错误体包括 `code` 和 `message`。未登录访问文件列表返回 401，缺少 CSRF 返回 403，非所有者访问文件返回 404。

响应头 `X-Request-Id` 可与后端业务日志关联。登录、创建分片会话和读取公开分享受到本机限流约束，超出后返回 429 及 `Retry-After` 秒数。

| 方法 | URL | 响应 |
|---|---|---|
| POST | /api/v1/uploads | 201，JSON 包含 `filename`、`totalSize`、小写 64 字符 `expectedSha256` |
| GET | /api/v1/uploads/{id} | 200，状态、磁盘验证通过的 `received`、待修复的 `repair`、到期时间和可空的完成文件 ID |
| PUT | /api/v1/uploads/{id}/chunks/{index} | 204，二进制请求体，同内容重传幂等，可恢复缺失或损坏的分片 |
| POST | /api/v1/uploads/{id}/complete | 200，返回同一 `fileId` |
| POST | /api/v1/uploads/{id}/abort | 204，已取消时重试仍成功 |

分片索引从 0 开始，片大小固定 4 MiB，末片可更短。有效期为 24 小时。`repair` 项包含 `index` 和 `reason`，后者为 `MISSING` 或 `CORRUPT`。重传应使用原始分片。已有分片内容冲突、缺片或会话已关闭返回 409，长度不足或合并验证失败返回 422，超过片或小文件上传上限返回 413。整文件声明或文件内容验证失败后会话进入 `FAILED`，应创建新会话。已结束会话不返回分片索引。空间不足或全局暂存预算耗尽时返回 507。

| 方法 | URL | 响应 |
|---|---|---|
| POST | /api/v1/shares | 201，发送 `fileId`、`expiresInMinutes`，返回分享 ID、到期时间及只在创建时返回的链接 |
| GET | /api/v1/files/{id}/shares | 200，文件所有者可查询有效分享的 ID 和到期时间 |
| DELETE | /api/v1/shares/{id} | 204，撤销本账户链接，可重复撤销 |
| GET | /api/v1/public/shares/{token} | 200，公开摘要，无需会话 |
| GET | /api/v1/public/shares/{token}/download | 200，流式附件，无需会话 |

分享可设 1 分钟至 7 天，有效期内可重复使用。失效、撤销、未知令牌及已软删除文件的分享返回 404。响应不提供对象键、所有者信息和磁盘路径。

私有和公开下载支持单个 `Range: bytes=起点-终点`。合法范围返回 206 和 `Content-Range`，不可满足范围返回 416。发送 `If-Range` 时，只有与文件强 ETag 匹配才返回片段。每次请求仍需通过文件所有者或分享有效性校验。
