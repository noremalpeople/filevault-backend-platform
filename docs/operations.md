# 日志、指标与限流

## 日志

HTTP 响应携带服务端生成的 `X-Request-Id`，请求线程以 MDC 关联业务日志。异步下载线程在开始传输前设置同一个请求 ID，传输结束后清理。上传、合并及下载日志记录操作、结果和毫秒耗时。后台巡检使用任务 ID、尝试次数、结果和异常类别。业务日志不记录路径、用户名、Cookie、密码、分享令牌或文件内容。默认输出结构化 JSON 到控制台，可通过 `FILEVAULT_LOG_FORMAT` 调整控制台格式。若设置 `LOGGING_FILE_NAME`，文件使用 JSON 格式，轮转默认单文件 10 MB、归档上限 100 MB、最多 7 个历史周期。生产反向代理的访问日志需要单独对分享请求路径脱敏。

## 指标

健康接口公开总状态。其他 Actuator 端点仅暴露指标，并要求通过 `X-FileVault-Metrics-Key` 提交匹配的运维密钥。未配置至少 16 字节的密钥时拒绝访问。应用默认绑定回环。通过环境变量 `FILEVAULT_METRICS_KEY` 从本机安全来源注入随机密钥，使用带请求头的本机客户端读取。访问密钥不进入 Git 或浏览器业务页面。

| 指标 | 单位和统计边界 |
|---|---|
| `filevault.upload.requests` | 请求数，计 multipart 上传与分片完成的 HTTP 成功或失败。幂等重试按请求计数。 |
| `filevault.merge.duration` | 秒，统计分片读取、单片校验和暂存合并。随后发生的整文件声明错误由上传请求计为失败。 |
| `filevault.download.duration` | 秒，统计异步任务内打开文件、定位和写出字节，不含权限校验或网络末端确认。 |
| `filevault.maintenance.attempts` | 已写入数据库结果的任务尝试数，区分成功和失败。 |
| `filevault.maintenance.rejected` | 执行器饱和导致的拒绝次数，任务仍保留在数据库。 |
| `filevault.maintenance.executor.active` / `.queued` | 当前活动线程数及队列任务数。 |
| `jvm.memory.used`、`jvm.threads.live` | Micrometer 标准 JVM 字节计量和线程数。 |

业务指标只使用固定的 `outcome` 标签，无账号和文件维度。Actuator 定时器给出 COUNT、TOTAL_TIME 和 MAX。指标在进程内累计，退出后重新计数。

## 限流与下载

默认按 60 秒固定窗口计数。登录按连接来源最多 20 次，建立上传会话按登录用户最多 30 次，公开分享读取按连接来源最多 180 次。可分别设置 `FILEVAULT_RATE_LOGIN`、`FILEVAULT_RATE_UPLOADS`、`FILEVAULT_RATE_SHARES`。上限返回 429 和秒数形式的 Retry-After。分片 PUT 可按实际进度重试，上传会话原有资源限制继续生效。来源由服务器连接确定，客户端提供的转发地址不参与。桶的容量和过期均受限制，高基数时桶可能被逐出。固定窗口跨边界可能出现短时突发，多实例需另行协调。

下载支持单个字节区间、后缀区间及开放式终点。每次请求都会先判定文件权限或分享有效性。成功区间返回 206，非法或不可满足区间返回 416。If-Range 仅比较强 ETag，日期或不匹配的 ETag 则返回完整文件。
