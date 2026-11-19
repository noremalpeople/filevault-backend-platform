# 测试

在 backend 执行 `mvnw.cmd clean verify`。F0 的 MockMvc 测试检查服务健康响应。Windows 原生 MySQL 运行时，用 `scripts/smoke-test.ps1` 探测真实进程和数据源。

F0 实测 `mvnw.cmd clean verify` 共 1 项测试，失败、错误与跳过均为 0，打包成功。启动实际 JAR 后，两条健康接口均返回 UP。通过应用账号在 MySQL 8.4.13 的 `filevault_dev` 库确认 Flyway V1 成功，并在事务中插入探针用户、读取该记录及回滚后确认记录消失。授权范围核对为 `filevault_dev.*`。

F1 实测 `mvnw.cmd clean verify` 共 9 项测试，失败、错误和跳过均为 0。首次测试遇到当前沙盒的系统临时路径解析失败，将该次 JVM 的临时目录设置到可写工作区后通过。正常运行可使用 Windows 默认临时目录。

F1 另启动实际 JAR 和原生 MySQL，验证注册、重名拒绝、错误密码、会话 Cookie、CSRF、注销、持久化 BCrypt 哈希、两用户越权拒绝、文件名及内容不符拒绝、10 MiB 超限拒绝、分页、详情、下载字节及 SHA-256、重命名和软删除。确认 Flyway V1、V2 均成功。

F2 的 `mvnw.cmd clean verify` 共 11 项测试，失败、错误和跳过均为 0。原生 MySQL 配合真实 JAR 验证 14,541,840 字节 CSV 断点续传、连续两次应用启动、分片越权、错误尺寸和索引、相同内容重传、不同内容冲突、缺片、并发完成、错误 SHA-256、重复取消和到期回收。核对下载数据与原始字节相同，Flyway V3 成功。

F3 的 `mvnw.cmd clean verify` 共 13 项测试，失败、错误和跳过均为 0。真实 HTTP 和 MySQL 验证同用户跨扩展名去重、跨用户对象分离、软删除后剩余引用可下载、分享摘要与附件下载、非所有者拒绝、有效期边界、数据库仅存摘要、撤销幂等、过期与软删除失效，Flyway V4 成功。

## F4 自动化验收

- 后端 `mvnw.cmd clean verify` 执行 13 项测试并打包。
- 前端 `npm.cmd test` 执行 4 项测试，覆盖文件指纹、分片边界、缺片清单及续传缓存键。`npm.cmd run build` 执行 TypeScript 检查并生成生产静态资源。
- 启动真实应用后，在 `frontend` 执行 `npm.cmd run test:browser`。脚本通过本机 Chrome 注册、登录并上传 13 MiB CSV。暂停并刷新页面后，检查仅发送索引 1、2、3，私有下载与公开下载均逐字节比对源文件。刷新所有者页面后撤销分享，匿名接收方随后看到失效提示。最后验证重命名、软删除和退出。
- 浏览器验收使用随机生成的测试账号和合成文件，会在当前开发库保留测试记录。Chrome 使用非默认安装路径时，在本次终端设置 `FILEVAULT_CHROME`。要另存无令牌的截图，设置 `FILEVAULT_SCREENSHOTS` 为目标目录。

## F5 回归验收

在项目根目录执行以下命令。先按 README 准备运行中的原生 MySQL 和本机配置文件。

```powershell
$env:FILEVAULT_IT = 'true'
if ($env:FILEVAULT_JAVA_HOME) { $env:JAVA_HOME = $env:FILEVAULT_JAVA_HOME }
Get-Content .env.local | ForEach-Object {
    if ($_ -match '^(FILEVAULT_DB_(?:PORT|USER|PASSWORD))=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
Push-Location backend
.\mvnw.cmd clean verify
Pop-Location
Remove-Item Env:FILEVAULT_IT
Remove-Item Env:FILEVAULT_DB_PASSWORD
Push-Location frontend
npm.cmd ci
npm.cmd test
npm.cmd run build
Pop-Location
```

在一个终端运行 `.\scripts\start-local.ps1`，另一个终端运行：

```powershell
.\scripts\smoke-test.ps1
Push-Location frontend
npm.cmd run test:browser
Pop-Location
```

停止应用后可用 `.\scripts\start-backend.ps1 -Reconcile` 检查现有数据库与存储。集成测试使用端口自动分配的真实 Spring Boot、`filevault_dev` 和独立的临时存储目录，测试结束时清理本次生成的账号数据。不设置 `FILEVAULT_IT=true` 时，默认 Maven 验收仅运行单元测试。

F5 的后端全量验收执行 31 项测试，包含 18 项新增测试，其中 15 项连接真实 MySQL。失败、错误和跳过均为 0。测试覆盖实际静态访问、安全过滤链、Windows Junction、缺盘与同长度损坏恢复、相同及不同分片、并发完成、读取过程中的中止、后端重启、错误 SHA-256、过期及撤销、软删除、只读对账及宽限、清理重试、配额及回滚补偿。集成测试的对象和上传会话预留上限分别配置为 12 MiB，以较小样例验证边界。

前端 6 项测试通过，新增分片补传和失败会话显式重启用例。生产构建、真实 MySQL 健康检查和 Chrome 验收通过。浏览器在保留 F4 流程的基础上，验证服务端拒绝错误整文件摘要后能通过重新开始完成真实上传。

F5 最终验收使用 Windows、Java 21 和现有原生 MySQL 8.4。通过正常启动入口验证 V5 数据库及前端页面。停止验收进程后执行独立只读对账，无引用对象、无引用分片、缺失对象、缺失开放分片、超龄暂存候选五项均为 0。故障注入测试另外验证了非零结果和宽限期。F5 未重新测量吞吐量。

## F6 回归验收

后端 36 项通过，失败、错误和跳过均为 0。新增测试验证前缀分页和权限、查询计划结果等价、缓存键隔离与提交后失效、线程池饱和、任务失败后退避和应用重启后的过期领取恢复。缓存测试中同一用户连续三次读取仅加载一次统计 SQL，用户解析仍查数据库。SQL 执行器实测见 [MySQL 查询计划](mysql-optimization.md)。

前端 6 项测试和生产构建通过。浏览器脚本同时验证账号统计和跨页前缀查询入口。

## F7 回归验收

Maven 全量 42 项通过，失败、错误和跳过均为 0。新增固定窗口边界和并发限流单测，以及真实 HTTP 的登录限流与来源校验、Range 字节与分享权限、受保护的 Actuator 读数、软删除与分享访问竞争用例。文件与分片原有回归保持通过。

前端 6 项和生产构建通过。Chrome 验收继续覆盖注册、会话、断点续传、完整下载、分享、撤销、重命名、删除及重新开始。在本机实际读取受保护指标，浏览器运行中观察到 3 次上传请求（含预期的失败）、3 次合并、2 次下载，以及 1 次完成的定时巡检。F7 另用合成数据进行传输与完整性测量，详见 [性能记录](benchmark.md)。
