# 架构

FileVault 在 Windows 本机运行。Spring MVC 接收 REST 请求，MyBatis 处理数据库访问，Flyway 管理独立 MySQL 库的模式。健康检查区分进程存活与依赖可用。

认证模块管理 Spring Security 同源会话和 CSRF。文件模块管理逻辑文件记录及其对象引用。上传模块持久化会话与已接收索引，合并验证后由文件模块登记对象。分享模块独立管理令牌摘要、过期时间和撤销状态。

对象、分片和临时文件位于应用私有目录。静态资源仅映射 Vue 入口和 `/assets/**`，启用这些路由前，应用检查实际 web 根与私有根的真实路径。计划任务回收已关闭会话的分片。

Vue 生产构建由 Spring MVC 提供静态资源，同源访问 REST API。浏览器分段读取文件计算 SHA-256，以账号、文件名、大小和摘要定位本地保存的会话 ID，再向后端查询已接收的索引。进度按服务端确认的字节数更新。

```mermaid
sequenceDiagram
    participant B as Vue 浏览器
    participant A as Spring MVC 与上传服务
    participant M as MySQL
    participant D as 私有磁盘
    B->>A: 查询上传会话
    A->>M: 按所有者查询会话和分片摘要
    A->>D: 校验已登记分片的长度与 SHA-256
    A-->>B: 返回已验证索引和待修复索引
    B->>A: 补传原始分片
    A->>D: 在事务外暂存请求体
    A->>M: 加会话行锁并复核状态
    A->>D: 移动新分片或修复同摘要分片
    A->>M: 登记分片并提交
    B->>A: 完成上传
    A->>D: 在事务外逐片合并并校验摘要
    A->>M: 加会话及用户行锁
    A->>D: 按需移动合并对象
    A->>M: 登记对象与逻辑文件，更新会话
    alt 提交成功
        A-->>B: 返回文件 ID，重复完成返回同一 ID
    else 明确回滚
        A->>D: 尝试清除本次新建对象
    end
```

文件系统和 MySQL 各自提交。进程退出或提交结果未知时，可用只读对账定位异常候选。详细顺序、资源限制和恢复边界见 [可靠性与对账](reliability.md)。
