# FileVault 后端面试指南

从一次上传讲起。浏览器计算整文件摘要，服务端创建带所有者的会话和 4 MiB 分片布局，逐片登记并最终流式合并。MySQL 保存权限和状态，本地磁盘保存字节。用两种存储的提交边界解释错误恢复，再展开可测的取舍。[系统设计](../architecture.md)

## 1. Java IO 与 NIO

`InputStream` 允许顺序处理小缓冲区。`Files` 和 `Path` 提供文件移动、真实路径校验与不跟随链接的打开方式。暂存使用 64 KiB 缓冲及摘要流，合并按顺序一次打开一个分片。下载先定位，再按响应长度写出。对象与显示文件名分离，用户输入无需参与文件路径拼接。

源码看 [LocalStorage](../../backend/src/main/java/com/filevault/storage/LocalStorage.java) 和 [FileDownloads](../../backend/src/main/java/com/filevault/file/FileDownloads.java)。测试看 [StorageTest](../../backend/src/test/java/com/filevault/storage/StorageTest.java)、Junction 和 Range 集成用例。追问通常是缓冲区会不会随文件大小增长、为什么仍要限制暂存空间，以及文件移动失败后如何恢复。

## 2. Java 并发与线程池

任务提交先用核心线程，其后入有界队列。队列满时才扩到最大线程数，仍饱和则拒绝。项目默认核心 1、最大 2、队列 4。拒绝不会丢掉数据库任务，轮询可重试。线程安全由进程内提交集合和数据库领取条件共同保证，进程内集合只负责去重提交。

源码看 [MaintenanceConfig](../../backend/src/main/java/com/filevault/storage/MaintenanceConfig.java) 和 [MaintenanceJobs](../../backend/src/main/java/com/filevault/storage/MaintenanceJobs.java)。[MaintenanceJobsTest](../../backend/src/test/java/com/filevault/storage/MaintenanceJobsTest.java) 验证饱和拒绝、I/O 失败及重启恢复。追问通常是直接执行拒绝策略是否会阻塞调度线程，以及关机等待和租约的关系。

## 3. Spring Boot 与 MVC

Boot 装配 MVC、管理端点、数据源及 Flyway。Controller 负责 HTTP 输入和认证主体，Service 决定业务事务。只读对账命令启动无 Web 容器的应用上下文，关闭 Flyway 和任务调度。静态资源映射仅公开入口和资产。Vue 生产构建由同一应用提供，避免开发代理与真实登录流程分裂。

源码看 [FileVaultApplication](../../backend/src/main/java/com/filevault/FileVaultApplication.java)、[StaticWebConfig](../../backend/src/main/java/com/filevault/storage/StaticWebConfig.java) 和 [FileController](../../backend/src/main/java/com/filevault/file/FileController.java)。完整启动、危险目录拒绝及无 Web 对账都有集成测试。追问如何辨别自动配置和手动配置，以及控制器为何不负责事务提交。

## 4. Spring Security、CSRF 与用户隔离

Session Cookie 关联服务端登录状态。变更请求需要另一份 CSRF token，避免浏览器携带 Cookie 时执行未经授权的跨站变更。文件、会话和分享管理继续用认证主体约束 MyBatis SQL，随机 ID 本身不承担授权。分享仅保存令牌摘要，按到期时间及撤销状态判断。登录和公开分享按连接地址限流，创建上传会话按登录主体限流。

源码看 [SecurityConfig](../../backend/src/main/java/com/filevault/auth/SecurityConfig.java)、[RateLimitFilter](../../backend/src/main/java/com/filevault/auth/RateLimitFilter.java) 和 [ShareMapper](../../backend/src/main/java/com/filevault/share/ShareMapper.java)。[集成套件](../../backend/src/test/java/com/filevault/upload/MysqlReliabilityTest.java) 测试跨用户、CSRF、429、转发头与分享失效。追问为什么伪造转发头不能换桶，为什么分享撤销无法回收已经完成或已开始的下载。

## 5. MyBatis 与 Flyway

Mapper 用参数绑定传值。用户前缀内的百分号、下划线及转义符还必须按 LIKE 语义转义。Flyway 保存版本及校验记录，V6 增加前缀索引与任务表，旧迁移不修改。与对象关联时保留所有者条件，防止无意扩大查询范围。

源码看 [FileMapper](../../backend/src/main/java/com/filevault/file/FileMapper.java)、[UploadMapper](../../backend/src/main/java/com/filevault/upload/UploadMapper.java) 和 [V6](../../backend/src/main/resources/db/migration/V6__file_search_and_maintenance.sql)。真实 MySQL 验证了结果、排序与跨租户边界。追问绑定参数与字符串拼接有什么区别，已执行的 Flyway 迁移为什么要保持稳定。

## 6. 联合索引与 EXPLAIN

B-tree 索引先按所有者与删除状态等值过滤，再扫描名称前缀范围。名称取范围后，索引序无法直接给出跨名称的创建时间顺序，因此仍有排序。`EXPLAIN ANALYZE` 对照按逻辑文件行数、实际执行时间和 join 驱动选择来解释，不能直接外推 API 延迟。

[实验记录](../mysql-optimization.md) 比较了 24,001 条合成数据中的 6 条命中。源码和结果断言在 [FileMapper](../../backend/src/main/java/com/filevault/file/FileMapper.java) 与 [MySQL 集成套件](../../backend/src/test/java/com/filevault/upload/MysqlReliabilityTest.java)。追问为什么仅加索引仍可能选旧计划，宽前缀或深分页何时需要另一种策略。

## 7. MySQL 事务、锁与幂等

会话的 `FOR UPDATE` 让最终登记串行化，已完成的请求返回已有结果。读取请求体和合并在事务外执行，关闭或取消会话后在最终行锁内拒绝写入。用户锁保护同用户去重和配额判断，完成时遵循会话锁到用户锁的顺序。竞争者可能重复合并，只有一次写入最终结果。

源码看 [UploadWrites](../../backend/src/main/java/com/filevault/upload/UploadWrites.java) 与 [FileService](../../backend/src/main/java/com/filevault/file/FileService.java)。集成套件检查双完成同 ID、取消竞争、逻辑文件与物理对象数、数据库回滚后磁盘状态。追问 READ COMMITTED 的作用、为何只锁应用关键区间、能否从 HTTP 200 推断幂等。

## 8. Spring Cache 与 Caffeine

统计接口依照认证主体解析数据库用户 ID，用该 ID 作为 Caffeine 缓存键。TTL 和容量控制驻留。Spring Cache 的同步加载避免同键并发贯穿，同一事务中的变更通过提交后监听器失效。统计的逻辑总字节数与真实对象配额有不同含义。

源码看 [FileStatistics](../../backend/src/main/java/com/filevault/file/FileStatistics.java) 和 [配置](../../backend/src/main/java/com/filevault/file/StatisticsCacheConfig.java)。集成套件验证三读一聚合、账号隔离和三种变更后的加载。追问事务回滚为何无需失效、缓存是否可用于授权、为什么多实例要独立解决失效。

## 9. 持久化异步任务、重试与调度

调度按时间窗口幂等建单，工作器凭条件更新领取。领取令牌约束最终写入，旧领取者在租约被接管后不能覆盖新状态。失败按次数退避，上限三次。只读对账可安全重新运行。进程重启恢复依据由数据库持久化，单机任务集合不承担恢复。

源码看 [MaintenanceSchedule](../../backend/src/main/java/com/filevault/storage/MaintenanceSchedule.java)、[MaintenanceJobMapper](../../backend/src/main/java/com/filevault/storage/MaintenanceJobMapper.java) 和 [MaintenanceJobsTest](../../backend/src/test/java/com/filevault/storage/MaintenanceJobsTest.java)。追问“恰好一次”主张为什么不成立，或者单次任务超过租约时多实例需要什么机制。

## 10. 分片协议与 SHA-256

浏览器用账号、名称、长度和摘要定位存储的会话 ID。数据库有分片行时还要验证磁盘文件，恢复只能补传数据库已接受的原始摘要。合并逐片再校验，整文件摘要还验证顺序和声明。SHA-256 证明内容匹配，签名、认证和权限由其他机制负责。

源码看 [resume.ts](../../frontend/src/resume.ts)、[UploadService](../../backend/src/main/java/com/filevault/upload/UploadService.java) 和 [浏览器回归](../../frontend/tests/browser.mjs)。真实文件测试覆盖同长度破损、缺文件、并发完成及服务重启。追问已有片与坏片怎样区分、整文件摘要错了能否沿用会话。

## 11. 数据库与文件系统一致性

文件移动先于数据库提交，事务回滚回调尝试补偿本次新增文件。进程崩溃或数据库提交结果未知时仍可能遗留孤儿。只读扫描按宽限期比较对象、开放会话分片和数据库引用。软删除仍保留物理对象和归属关系。

源码看 [FileService](../../backend/src/main/java/com/filevault/file/FileService.java)、[StorageReconciliation](../../backend/src/main/java/com/filevault/storage/StorageReconciliation.java) 和 [可靠性文档](../reliability.md)。集成用例用真实 MySQL 和字节验证补偿与双向异常计数。追问备份恢复不同步会怎样、为什么不直接删除扫描命中的文件。

## 12. HTTP Range、日志、指标与性能

合法单区间返回 206 和长度，范围无效返回 416。强 ETag 匹配才满足 If-Range，权限仍逐请求检查。结构化日志通过 MDC 关联请求 ID，异步流显式恢复 ID。Micrometer 按固定结果标签计数和计时，Actuator 指标受独立密钥保护。性能脚本从会话创建计到完成响应，下载及客户端摘要另计。

源码看 [FileDownloads](../../backend/src/main/java/com/filevault/file/FileDownloads.java)、[RequestTraceFilter](../../backend/src/main/java/com/filevault/common/RequestTraceFilter.java) 和 [TransferTelemetry](../../backend/src/main/java/com/filevault/common/TransferTelemetry.java)。真实测试校验返回区间字节、授权、指标接口和请求 ID。追问为什么避免用用户 ID 当标签、服务端定时器与端到端耗时的差别、单机双并发结果为什么不能代表容量。[运维说明](../operations.md) · [性能记录](../benchmark.md)
