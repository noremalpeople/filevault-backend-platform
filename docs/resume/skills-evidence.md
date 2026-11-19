# FileVault 技能证据

## 已实现且已测试

| 技能 | 真实功能与源码 | 测试或实测 | 常见面试问题 |
|---|---|---|---|
| Java IO/NIO | [LocalStorage](../../backend/src/main/java/com/filevault/storage/LocalStorage.java) 分片暂存、顺序打开和真实路径校验，[FileDownloads](../../backend/src/main/java/com/filevault/file/FileDownloads.java) 以固定缓冲区写出 | [StorageTest](../../backend/src/test/java/com/filevault/storage/StorageTest.java)、[MySQL 集成](../../backend/src/test/java/com/filevault/upload/MysqlReliabilityTest.java) 和 190 MiB 样例 | 怎样限制文件句柄、堆内存、磁盘占用？ |
| 并发与数据库事务 | [UploadWrites](../../backend/src/main/java/com/filevault/upload/UploadWrites.java) 最终登记，[FileService](../../backend/src/main/java/com/filevault/file/FileService.java) 用户锁下去重与回滚补偿 | 同会话并发完成、读入中止、对象去重、真实事务回滚检查行及磁盘 | 为什么锁只包围最终登记？何处仍需短暂 I/O？ |
| Spring Boot / MVC / Security | [应用入口](../../backend/src/main/java/com/filevault/FileVaultApplication.java)、[SecurityConfig](../../backend/src/main/java/com/filevault/auth/SecurityConfig.java)、[限流](../../backend/src/main/java/com/filevault/auth/RateLimitFilter.java) | 真正的容器、会话、CSRF、两用户隔离、429 和分享有效性测试 | 为什么保留 Session？可信来源如何确定？ |
| MyBatis、Flyway、联合索引 | [FileMapper](../../backend/src/main/java/com/filevault/file/FileMapper.java)、[V6](../../backend/src/main/resources/db/migration/V6__file_search_and_maintenance.sql) | 真实 MySQL 中 24,001 条合成文件行的对照执行计划，结果与顺序一致。[实验](../mysql-optimization.md) | 前缀范围为何不能同时保证全局创建时间顺序？ |
| Spring Cache / Caffeine | [FileStatistics](../../backend/src/main/java/com/filevault/file/FileStatistics.java) 和 [缓存配置](../../backend/src/main/java/com/filevault/file/StatisticsCacheConfig.java) | 同用户 3 次读取触发 1 次聚合，测试隔离、上传、重命名及软删除后的失效 | 为什么提交前清缓存可能重新缓存旧值？ |
| 线程池与定时调度 | [MaintenanceConfig](../../backend/src/main/java/com/filevault/storage/MaintenanceConfig.java)、[MaintenanceJobs](../../backend/src/main/java/com/filevault/storage/MaintenanceJobs.java)、[领取 SQL](../../backend/src/main/java/com/filevault/storage/MaintenanceJobMapper.java) | [MaintenanceJobsTest](../../backend/src/test/java/com/filevault/storage/MaintenanceJobsTest.java) 的饱和、退避和重启租约恢复，实际调度指标 | core/max/queue 的顺序是什么？拒绝后任务去哪了？ |
| SHA-256、恢复与对账 | [UploadService](../../backend/src/main/java/com/filevault/upload/UploadService.java)、[StorageReconciliation](../../backend/src/main/java/com/filevault/storage/StorageReconciliation.java) | 恢复缺片或同长度损坏后比对下载摘要，对账见到孤儿和缺失，现有目录扫描五项为零 | 整文件摘要与单片摘要分别解决什么问题？ |
| HTTP 文件传输 | [FileDownloads](../../backend/src/main/java/com/filevault/file/FileDownloads.java) | 真 HTTP 验证 206、416、强 ETag、后缀区间、授权与字节内容 | 为什么每次区间请求都要授权？ |
| 可观测性与测试 | [请求追踪](../../backend/src/main/java/com/filevault/common/RequestTraceFilter.java)、[指标](../../backend/src/main/java/com/filevault/common/TransferTelemetry.java) | 42 项后端、6 项前端、Chrome 和真实 Actuator 读数。[测试记录](../tests.md) | MDC 如何跨到下载线程？哪些字段不能做标签？ |

## 已实现但尚未完整验证

- GitHub Actions 工作流及本机对应构建已验证。GitHub 托管执行尚无记录。
- 大小与并发实验只在 Windows 同机回环中完成。宽泛前缀、深分页、远程网络、持续并发和长时间运行未覆盖。
- 真实应用重启测试恢复模拟的过期领取。没有在生产环境做断电和跨实例故障切换。

## 学习中或计划引入

分布式限流、共享缓存、多实例任务心跳、对象版本存储、蠕虫或恶意内容检测、归档恢复演练与全对象巡检。当前简历描述只引用已有源码与测试证据。
