# FileVault

基于 Spring Boot 的大文件分片上传与安全共享管理平台，面向个人和小团队的研发材料管理。当前阶段进度见 [里程碑](docs/milestones.md)。

当前可用功能包括注册、登录和注销、账户隔离、10 MiB 以内的文件上传、分页列表、详情、流式下载、重命名和软删除。API 使用同源会话，调用方式见 [接口文档](docs/api.md)。

大文件可使用 4 MiB 分片、断点续传和 SHA-256 校验，默认上限 200 MiB。需要调整时，在启动前设置进程环境变量 `FILEVAULT_MAX_UPLOAD_BYTES`，允许值为 1 至 512 MiB 对应的字节数。

同一账户的已验证内容可复用物理对象。文件可创建限时分享，创建者可以撤销，接收方无需登录即可使用尚有效的链接。

## Windows 原生运行

准备 JDK 21、MySQL 8.4、Git 和 PowerShell。构建使用 Maven Wrapper 3.9.11，前端阶段使用 Node 和 npm。Java 没有加入 PATH 时，可在当前终端设置 `FILEVAULT_JAVA_HOME` 指向现有 JDK 根目录。

准备只绑定回环接口的 MySQL 实例。项目使用 `127.0.0.1:3308` 上的独立 `filevault_dev` 库和 `filevault_app` 账号。不要使用其他项目的库或管理员账号运行应用。参考 [数据库初始化](docs/database.md)。

在仓库根目录执行以下命令，并在本地配置文件中填写应用账号的生成密码。

```powershell
.\scripts\check-env.ps1
if (-not (Test-Path .env.local)) { Copy-Item .env.example .env.local }
notepad .env.local
.\scripts\start-backend.ps1
```

在另一个终端执行：

```powershell
.\scripts\smoke-test.ps1
Push-Location backend
.\mvnw.cmd clean verify
Pop-Location
```

应用默认监听 `127.0.0.1:8082`。首次构建需要访问 Maven Central，或配置经过校验的 Maven 分发镜像及依赖访问代理。

## 开发文档

[架构](docs/architecture.md) · [数据库](docs/database.md) · [API](docs/api.md) · [安全](docs/security.md) · [测试](docs/tests.md) · [性能记录](docs/benchmark.md)
