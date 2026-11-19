# Windows 原生 MySQL

在独立的 MySQL 8.4 实例中用管理员客户端交互执行，密码占位符应换成你的随机强密码。

```sql
CREATE DATABASE filevault_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'filevault_app'@'localhost' IDENTIFIED BY '<generated-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON filevault_dev.* TO 'filevault_app'@'localhost';
```

开发账号的 DDL 权限用于 Flyway 迁移，作用域仅为 `filevault_dev.*`。启动时执行 `V1__users.sql` 与后续增量迁移，Flyway 的 clean 功能关闭。用 `SELECT DATABASE(), CURRENT_USER();` 和 `SHOW GRANTS FOR CURRENT_USER;` 检查实际连接及权限。

`V2__files.sql` 增加 `file_objects` 和 `user_files`。复合外键确保文件及其物理对象属于同一用户。列表按所有者、软删除状态、创建时间和 ID 排序。
