# API

| 方法 | URL | 响应 |
|---|---|---|
| GET | /api/v1/health | 200，包含服务名与存活状态 |
| GET | /actuator/health | 200 UP 或 503 DOWN，包含数据源健康状态 |

认证和文件操作按后续里程碑添加。
