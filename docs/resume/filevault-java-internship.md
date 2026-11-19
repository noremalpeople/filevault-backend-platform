# FileVault 实习简历项目描述

**FileVault｜大文件断点续传与安全分享平台**

Java 21、Spring Boot、Spring Security、MyBatis、MySQL、Flyway、Spring Cache、Caffeine、Micrometer

- 实现 4 MiB 分片上传与断点续传。对已登记分片核对磁盘长度及 SHA-256，允许按原摘要修复缺失或损坏分片。将网络读取和流式合并移出写事务，通过 MySQL 行锁保证同一会话并发完成返回相同文件 ID。
- 实现 Session、CSRF、按所有者授权和可撤销限时分享。将文件名与磁盘键分离，校验静态根和私有目录，支持每次鉴权后的 HTTP Range 下载。
- 使用 MyBatis、Flyway 实现跨页文件名前缀搜索和联合索引。通过 Spring Cache 与 Caffeine 缓存用户文件统计，在事务提交后失效。真实 MySQL 验证同一用户连续三次统计请求只执行一次统计聚合。
- 使用有界线程池运行只读存储巡检，MySQL 保存领取、租约、重试及结果。用 MDC 和 Micrometer 跟踪传输与任务，在 Windows 原生环境完成 42 项后端测试、6 项前端测试和浏览器验收。

可用的实测补充为本机回环条件下完成 190 MiB 合成文件分片上传和完整下载摘要比对。该试次上传约 2.09 秒，单客户端，Java 最大堆 256 MiB，使用同机 MySQL。若简历篇幅有限，优先保留文件规模、完整性验证和测试数量。

[技能证据](skills-evidence.md) · [性能条件](../benchmark.md) · [面试指南](../interview/filevault-backend-guide.md)
