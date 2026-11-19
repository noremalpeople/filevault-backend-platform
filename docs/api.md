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
| GET | /api/v1/files | 200，页码从 0 开始，`size` 取 1–100 |
| GET | /api/v1/files/{id} | 200，仅当前所有者可读 |
| GET | /api/v1/files/{id}/download | 200，流式附件 |
| PATCH | /api/v1/files/{id} | 204，JSON `{"displayName":"renamed.txt"}` |
| DELETE | /api/v1/files/{id} | 204，软删除 |

同源客户端保存服务端会话 Cookie。注册、登录、上传、改名、删除和退出都携带 GET csrf 获得的请求头。登录完成后重新取得 CSRF token。错误体包括 `code` 和 `message`。未登录访问文件列表返回 401，缺少 CSRF 返回 403，非所有者访问文件返回 404。

| 方法 | URL | 响应 |
|---|---|---|
| POST | /api/v1/uploads | 201，JSON 包含 `filename`、`totalSize`、小写 64 字符 `expectedSha256` |
| GET | /api/v1/uploads/{id} | 200，状态、已上传索引 `received`、到期时间和可空的完成文件 ID |
| PUT | /api/v1/uploads/{id}/chunks/{index} | 204，二进制请求体，同内容重传幂等 |
| POST | /api/v1/uploads/{id}/complete | 200，返回同一 `fileId` |
| POST | /api/v1/uploads/{id}/abort | 204，已取消时重试仍成功 |

分片索引从 0 开始，片大小固定 4 MiB，末片可更短。有效期为 24 小时。分片冲突、缺片或会话已关闭返回 409，长度不足或合并哈希不匹配返回 422，超过片或小文件上传上限返回 413。后台清理完成后，已结束会话的 `received` 可为空。
