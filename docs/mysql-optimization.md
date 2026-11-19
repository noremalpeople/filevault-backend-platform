# MySQL 查询计划实测

在现有 Windows 原生 MySQL 8.4 的 `filevault_dev` 上执行。使用随机测试账号、一个对象和 24,001 条逻辑文件行，其中 6 条匹配 `needle-%`。测试结束仅清理该测试账号的数据。搜索按所有者、未删除状态和文件名前缀过滤，按创建时间与 ID 降序分页。

## 前缀搜索

| 版本 | 计划形态 | 文件行访问量 | 根节点结束时刻 |
|---|---|---:|---:|
| 对照 | 普通 JOIN，排除新索引。按对象查文件、过滤名称后排序 | 24,001 | 17.2 ms |
| 当前 | `STRAIGHT_JOIN` 从文件侧驱动，使用 `ix_files_owner_name` 前缀范围访问，再查对象主键 | 6 | 0.0655 ms |

以上为 F6 运行中各一次 `EXPLAIN ANALYZE` 的 MySQL 执行器时刻，非端到端延迟。对照通过 `IGNORE INDEX` 在同一批数据上还原候选索引条件。试验中仅添加索引而不调整驱动顺序，优化器仍选择旧路径。V6 将索引列定义为 `(owner_id, deleted_at, display_name)`。范围扫描仍需对匹配结果按时间和 ID 排序。宽泛前缀和深度 OFFSET 的收益尚未验证。

## 其他路径

| 查询 | 观察及决定 |
|---|---|
| 普通文件列表 | 测试租户仅有一个关联对象，计划由对象索引驱动，再用 `ix_files_object` 和排序。已有 `ix_files_list`，保留现有 SQL。更广的对象分布与深页未经本轮测量。 |
| 会话查询 | 会话 UUID 唯一，按 ID 命中主键并校验所有者。 |
| 去重 | 测试租户仅一条对象，优化器采用按所有者查找。现有 `ix_objects_owner_content` 提供所有者、摘要和大小索引，未再加索引。 |
| 有效分享 | 唯一令牌摘要与主键连接，测试单行查询被优化为常量访问。 |
| 到期扫描 | 使用 `ix_upload_expiry` 的状态和时间范围。 |
| 临时分片清理 | 小规模样本用分片主键顺序扫描并对会话主键做单行查询。仍保留分页上限与退避，尚无足够证据为该路径加索引。 |

通过 `FILEVAULT_IT=true` 启用真实数据库测试，在 Maven 目录运行 `./mvnw.cmd clean verify`。测试 `MysqlReliabilityTest.measuresPrefixSearchPlansWithSyntheticTenantAndVerifiesSameRows` 输出计划并检查两个查询结果及顺序一致。独立权限与分页测试覆盖其他用户、通配符转义以及逐页顺序。
