# 架构

FileVault 在 Windows 本机运行。Spring MVC 接收 REST 请求，MyBatis 处理数据库访问，Flyway 管理独立 MySQL 库的模式。健康检查区分进程存活与依赖可用。

计划由认证、文件、上传、分享和本地存储模块组成。对象存储和分片目录将位于私有 NTFS 路径。磁盘操作与 MySQL 事务分别执行，后续阶段通过状态和补偿处理失败。
