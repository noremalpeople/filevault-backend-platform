# 测试

在 backend 执行 `mvnw.cmd clean verify`。F0 的 MockMvc 测试检查服务健康响应。Windows 原生 MySQL 运行时，用 `scripts/smoke-test.ps1` 探测真实进程和数据源。

F0 实测 `mvnw.cmd clean verify` 共 1 项测试，失败、错误与跳过均为 0，打包成功。启动实际 JAR 后，两条健康接口均返回 UP。通过应用账号在 MySQL 8.4.13 的 `filevault_dev` 库确认 Flyway V1 成功，并在事务中插入探针用户、读取该记录及回滚后确认记录消失。授权范围核对为 `filevault_dev.*`。
