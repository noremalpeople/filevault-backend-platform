# 测试

在 backend 执行 `mvnw.cmd clean verify`。F0 的 MockMvc 测试检查服务健康响应。Windows 原生 MySQL 运行时，用 `scripts/smoke-test.ps1` 探测真实进程和数据源。

F0 实测 `mvnw.cmd clean verify` 共 1 项测试，失败、错误与跳过均为 0，打包成功。启动实际 JAR 后，两条健康接口均返回 UP。通过应用账号在 MySQL 8.4.13 的 `filevault_dev` 库确认 Flyway V1 成功，并在事务中插入探针用户、读取该记录及回滚后确认记录消失。授权范围核对为 `filevault_dev.*`。

F1 实测 `mvnw.cmd clean verify` 共 9 项测试，失败、错误和跳过均为 0。首次测试遇到当前沙盒的系统临时路径解析失败，将该次 JVM 的临时目录设置到可写工作区后通过。正常运行可使用 Windows 默认临时目录。

F1 另启动实际 JAR 和原生 MySQL，验证注册、重名拒绝、错误密码、会话 Cookie、CSRF、注销、持久化 BCrypt 哈希、两用户越权拒绝、文件名及内容不符拒绝、10 MiB 超限拒绝、分页、详情、下载字节及 SHA-256、重命名和软删除。确认 Flyway V1、V2 均成功。

F2 的 `mvnw.cmd clean verify` 共 11 项测试，失败、错误和跳过均为 0。原生 MySQL 配合真实 JAR 验证 14,541,840 字节 CSV 断点续传、连续两次应用启动、分片越权、错误尺寸和索引、相同内容重传、不同内容冲突、缺片、并发完成、错误 SHA-256、重复取消和到期回收。核对下载数据与原始字节相同，Flyway V3 成功。

F3 的 `mvnw.cmd clean verify` 共 13 项测试，失败、错误和跳过均为 0。真实 HTTP 和 MySQL 验证同用户跨扩展名去重、跨用户对象分离、软删除后剩余引用可下载、分享摘要与附件下载、非所有者拒绝、有效期边界、数据库仅存摘要、撤销幂等、过期与软删除失效，Flyway V4 成功。
