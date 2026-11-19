# FileVault

基于 Spring Boot 的大文件分片上传与安全共享管理平台。FileVault — Resumable File Upload & Secure Sharing Platform。

支持会话认证、账户隔离、文件分页管理、流式下载、重命名和软删除。小文件可通过 multipart API 上传，上限为 10 MiB。

浏览器使用 4 MiB 分片上传文件，暂停或刷新后可重新选择同一文件继续。服务端核对已登记分片的长度和 SHA-256，缺失或损坏的分片可用原始内容补传。整文件声明验证失败后，浏览器可重新开始。默认上传上限为 200 MiB，`FILEVAULT_MAX_UPLOAD_BYTES` 可设为 1 字节至 512 MiB 对应的字节数。

同一账户的已验证内容可复用物理对象。所有者可创建、查看和撤销有效分享，匿名接收方可下载附件。链接令牌在创建时返回，数据库保存其 SHA-256 摘要。

## Windows 原生运行

准备 JDK 21、MySQL 8.4、Node.js 24、npm、Git 和 PowerShell。构建使用 Maven Wrapper 3.9.11。Java 没有加入 PATH 时，在当前终端设置 `FILEVAULT_JAVA_HOME` 指向现有 JDK 根目录。

准备只绑定回环接口的 MySQL 实例。项目使用 `127.0.0.1:3308` 上的独立 `filevault_dev` 库和 `filevault_app` 账号。不要使用其他项目的库或管理员账号运行应用。参考 [数据库初始化](docs/database.md)。

在仓库根目录执行以下命令，并在本地配置文件中填写应用账号的生成密码。

```powershell
.\scripts\check-env.ps1
if (-not (Test-Path .env.local)) { Copy-Item .env.example .env.local }
notepad .env.local
.\scripts\start-local.ps1
```

打开浏览器访问 [本机 FileVault](http://127.0.0.1:8082)。只运行 API 时使用 `.\scripts\start-backend.ps1`。首次构建需要访问 Maven Central 和 npm 的包仓库。静态资源与私有文件应位于分离的目录。配置、资源限制和只读对账命令见 [可靠性文档](docs/reliability.md)。

## 演示和验证

注册并登录，在上传区选择一个大于 8 MiB 的 CSV 文件。等待第一片完成后暂停并刷新，重新选择同一文件继续。下载完成的文件，然后创建一个限时分享，在无痕窗口打开。回到原窗口重新打开分享管理并撤销链接，刷新无痕窗口可观察到链接失效。

![FileVault 文件工作区](docs/screenshots/filevault-workspace.png)

在应用运行时另开一个 PowerShell 窗口执行健康检查。已安装 Chrome 时可运行完整浏览器验收。

```powershell
.\scripts\smoke-test.ps1
Push-Location frontend
npm.cmd test
npm.cmd run test:browser
Pop-Location
Push-Location backend
if ($env:FILEVAULT_JAVA_HOME) { $env:JAVA_HOME = $env:FILEVAULT_JAVA_HOME }
.\mvnw.cmd clean verify
Pop-Location
```

完整后端验收包含真实 MySQL 集成测试。环境变量与命令见 [测试文档](docs/tests.md)。

## 开发文档

[架构](docs/architecture.md) · [数据库](docs/database.md) · [API](docs/api.md) · [安全](docs/security.md) · [测试](docs/tests.md) · [性能记录](docs/benchmark.md) · [里程碑](docs/milestones.md)

[可靠性与对账](docs/reliability.md) · [F5 安全审查](docs/security-audit.md) · [GitHub 发布清单](docs/github-release-checklist.md)
