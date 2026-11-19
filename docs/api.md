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
