# 贡献指南

感谢你愿意花时间改进这个项目。下面几条约定能省掉双方很多来回。

## 开发环境

| 项 | 版本 |
|---|---|
| JDK | 8（在 8u111 上验证） |
| Maven | 3.6+（在 3.10.0 上验证） |
| MySQL | 8.0 |

JDK 8u111 的 CA 列表过旧，Maven 下载依赖可能报 `PKIX path building failed`，用 Windows 根证书库绕过：

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStore=NONE -Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

## 提交前自检

```bash
mvn -q -DskipTests clean package
```

`clean` 是必需的——增量 `package` 不会清掉 `target/classes/static` 里的旧前端资源，会让旧页面覆盖新版。打包前先停掉正在运行的 java 进程，否则 repackage 会因无法重命名 jar 而失败。

## 编码约束

### 1. 必须保持 Java 8 兼容

代码库刻意避开 JDK 9+ API，以便在旧环境运行。不要引入：

- `java.net.http.*`（HttpClient、HttpTimeoutException 都是 JDK 11+）
- `var`、`List.of` / `Map.of` / `Set.of`
- `String.isBlank()` / `String.strip()` / `String.repeat()`
- `Optional.isEmpty()`
- 新 `switch` 表达式、record、sealed、text block

需要 HTTP 客户端请用 Spring 的 `RestTemplate`；需要超时异常请用 `java.io.InterruptedIOException` 或 `java.net.SocketTimeoutException`。

### 2. 实体必须逐列对齐 DDL

`ddl-auto=validate` 会在启动时逐列校验，任何不匹配都会让应用起不来。DDL 里的 `CHAR(n)` / `BIT(1)` / `JSON` / `DATETIME(6)` / `TEXT` / `LONGTEXT` 都要在实体字段上写 `columnDefinition`，例如：

```java
@Column(name = "sha256", nullable = false, columnDefinition = "char(64)")
private String sha256;
```

### 3. 迁移只增不改

`V1__initial_schema.sql` 已在既有部署里应用过，改动它会让 Flyway checksum 校验失败、启动直接报错。加列或加表请新建 `V2__xxx.sql`。

### 4. 接口改动要同步文档

改路由、字段或错误文案时，请一并更新 `接口文档.md`。这个仓库的文档是契约的一部分，不是事后补的说明。

### 5. 错误文案保持中文且可操作

业务错误统一抛 `IllegalArgumentException`（由 `GlobalExceptionHandler` 转成 400 `{"error": "..."}`）。文案要说清「哪里错了」和「怎么改」，例如：

- 好：`图片模型「X」未开启图生图，请在模型管理里勾选「支持图生图」或换一个模型`
- 差：`invalid model config`

### 6. 不要在日志里打敏感信息

凭据、API Key、完整请求体都不要进日志。凭据解密失败是可预期分支，记一行 warn 即可，不要打堆栈。

## 代码风格

- 包结构：先技术分层（`controller` / `service` / `mapper` / `entity` / `provider`），再按业务模块分包。
- 依赖方向 `controller → service → mapper`，不要在控制器里写业务规则，也不要让 `provider` 反向依赖 `service`。
- 服务接口用 `*Service`，实现用 `*ServiceImpl`；跨模块复用走接口而不是直接 new 实现类。
- 注释用中文，写「为什么」而不是复述代码在做什么。

## 提交信息

用中文或英文都行，建议一句话说清「改了什么 + 为什么」：

```
修 /files/** 目录解析：原来要求 isRegularFile，目录请求恒 404
```

## 分支与 PR

1. 从 `main` 切分支：`git checkout -b fix/xxx` 或 `feat/xxx`；
2. 一个 PR 做一件事，避免把无关格式化混进来；
3. PR 描述里写清动机、改动范围、验证方式；
4. 如果改动有行为差异（例如新增的不复刻能力），在 `Python版功能对照与Java迁移计划.md` 里同步记录。

## 报 issue

请带上：操作系统与 JDK 版本、复现步骤、期望结果、实际结果（含完整错误响应与相关日志片段）。如果是厂商 API 的问题，请注明协议名与 base URL，但**不要贴出真实密钥**。
