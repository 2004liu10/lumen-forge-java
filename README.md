# Lumen Forge · Java 后端

**一个可自托管的 AI 创作工作台后端：账号池调度、多厂商图片/视频生成、无限画布、分镜剧本、素材库与发布记录。**

[![Java](https://img.shields.io/badge/Java-8-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1.svg)](https://www.mysql.com/)

前端工作台（HTML + 原生 JavaScript）随 Spring Boot 一起打包托管，启动即是完整可用的全栈应用，无需额外部署。

---

## 目录

- [这是什么](#这是什么)
- [功能](#功能)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [存储路径](#存储路径)
- [架构](#架构)
- [接口文档](#接口文档)
- [已知限制](#已知限制)
- [贡献](#贡献)
- [许可证](#许可证)

## 这是什么

一个把「AI 图片/视频生成」从零散脚本变成可用工作台的后端。它解决的核心问题不是「调一次 API」，而是围绕生成任务的工程问题：

- **账号池**：多个 RunningHub / 网页平台账号轮转、并发挑选、额度刷新与失败回退。
- **持久化队列**：任务先落库再发请求，重启不丢排队工作与远端任务号；重启后继续轮询而不是重复提交。
- **多厂商适配**：图片 8 种协议、视频 4 种 provider，各自不同的鉴权、签名、轮询与结果格式收敛到一套内部模型。
- **本地资产**：生成结果、参考图、素材、视频输入统一由一套存储路径管理器落盘，并由 `/files/**` 提供服务。

数据落 MySQL，表结构由 Flyway 管理，Hibernate 只做实体校验（`ddl-auto=validate`），不会在启动时偷偷改表。

## 功能

| 模块 | 能力 |
|---|---|
| **account** | 账号 CRUD、脱敏凭据快照、加密凭据读取、RunningHub Key 校验与余额刷新 |
| **canvas** | 画布设置、AI 应用参数读取、工作流 JSON 解析、值编辑、本机素材上传/移除、`extraParams` |
| **provider** | RunningHub 应用/工作流提交、multipart 上传、任务轮询/取消、响应归一化；图片与视频厂商客户端 |
| **task** | 持久化队列、账号并发挑选、重试/取消、轮询、重启恢复、历史导入、结果补取 |
| **image** | 图片模型注册表（密钥加密）+ 串行生成队列；`ark`/`openai`/`zhipu`/`jimeng`/`wanxiang`/`hunyuan`/`minimax`/`cloudflare` 八协议；图生图参考图库、参考图快照、复用/转参考图/送入画布/网页导入 |
| **video** | 视频模型、首尾帧输入图、任务队列与轮询；`zhipu`/`ark`/`dashscope`/`doubao` 四种 provider，各带默认 base URL、模型列表、时长与分辨率预设 |
| **material** | 素材来源与检索缓存、图片搜索/榜单、下载、素材转画布素材、素材转参考图 |
| **ncanvas** | 无限画布（节点/连线/对话）与节点资产库（上传、回收站、还原、彻底删除、批量清空、转参考图、导出/导入 JSON） |
| **canvasai** | 画布 AI 导演（按剧本拆镜、风格预设）与画布对话（意图识别、镜头编辑建议） |
| **story** | 剧本模型配置、分镜 CRUD、角色分配、出图、剧本引擎状态 |
| **topic / publish** | 选题库与使用统计；发布草稿、作品、可删性判定、标记删除 |
| **topaz / web** | Topaz 增强队列与本地目录配置；网页平台 provider 与账号额度池 |
| **storage** | 8 类存储路径配置与可迁移目录、`/files/**` 本地文件服务 |
| **common** | 健康检查、`GET /api/state` 聚合快照、统一异常映射 |

**20 个控制器、135 条路由映射**，逐条对齐参考实现的路由契约，含 9 条 `/files/...` 静态文件路由与 9 条便利 GET。

## 快速开始

### 前置

- **JDK 8**（本仓库在 JDK 8u111 上验证；不需要 JDK 11+，代码刻意避开了 `java.net.http.*` 等新 API）
- **Maven 3.6+**
- **MySQL 8.0**

### 1. 准备数据库

```sql
CREATE DATABASE lumen_forge
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

表由 Flyway 在首次启动时自动建（`src/main/resources/db/migration`），不需要手工导入。

也可以用仓库里的 compose 文件起一个：

```bash
docker compose up -d mysql
```

### 2. 设置环境变量

**`APP_CREDENTIAL_KEY` 必须设置且必须稳定**——账号密钥与网页登录凭据用它做 AES-GCM 加密。之后改这个值会让已保存的凭据无法解密（服务会保留原值并记 warn，不会崩，但那些账号等于失效）。

```bash
export APP_CREDENTIAL_KEY="$(openssl rand -base64 32)"
export DB_URL="jdbc:mysql://127.0.0.1:3306/lumen_forge?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
export DB_USERNAME="root"
export DB_PASSWORD="your-password"
```

PowerShell：

```powershell
$env:APP_CREDENTIAL_KEY = "replace-with-a-long-random-secret"
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/lumen_forge?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your-password"
```

> **JDK 8u111 用户注意**：该版本自带 CA 列表过旧，Maven 下载依赖会报 `PKIX path building failed`。用 Windows 根证书库绕过：
> ```powershell
> $env:MAVEN_OPTS = "-Djavax.net.ssl.trustStore=NONE -Djavax.net.ssl.trustStoreType=Windows-ROOT"
> ```

### 3. 启动

```bash
mvn spring-boot:run
```

打开 <http://localhost:8080/> 即是工作台。健康检查：

```bash
curl http://localhost:8080/api/health
```

从 IntelliJ IDEA 启动时，在 **Run → Edit Configurations → Environment variables** 里设置 `APP_CREDENTIAL_KEY`。

### 4. 打包

```bash
mvn -q -DskipTests clean package
java -jar target/lumen-forge-server-1.0.0-SNAPSHOT.jar --server.port=8080
```

> `clean` 是必需的：增量 `package` 不会清掉 `target/classes/static` 里的旧前端资源，会让旧页面覆盖新版。打包前先停掉正在运行的 java 进程，否则 Spring Boot repackage 会因无法重命名 jar 而失败。

### 5. 不想装 MySQL？

用 H2 档快速跑起来（文件库落在 `./data`，关闭 Flyway、改用 `ddl-auto=update`）：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

H2 只适合本地试玩与演示；正式使用请走 MySQL。

## 配置项

全部走环境变量，无默认凭据（数据库密码默认为空，必须显式提供）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_CREDENTIAL_KEY` | 空 | **必填**。凭据加密主密钥，必须稳定。缺失时创建账号返回 503 并给出配置提示 |
| `DB_URL` | `jdbc:mysql://127.0.0.1:3306/lumen_forge?...` | JDBC 连接串 |
| `DB_USERNAME` | `root` | 数据库用户 |
| `DB_PASSWORD` | 空 | 数据库密码 |
| `SERVER_PORT` | `8080` | 也可用 `--server.port=` |
| `app.storage.root` | `./data` | 本地文件根目录，见下节 |

服务默认绑定 `127.0.0.1:8080`（`server.address` 写死为回环地址，避免误暴露到公网）。需要对外提供服务时，请自行加反向代理与鉴权——**本项目不内置任何登录鉴权**。

## 存储路径

本机文件统一由 `StoragePaths` 管理，共 8 类，默认都在 `./data` 下：

| key | 标签 | 默认子目录 |
|---|---|---|
| `uploads` | 画布素材 | `uploads` |
| `outputs` | 自用模块生成结果 | `outputs` |
| `topaz` | Topaz 增强结果 | `topaz` |
| `generated` | 图片生成结果 | `generated` |
| `refs` | 参考图 | `generated/refs` |
| `videos` | 视频生成结果 | `videos` |
| `videoInputs` | 视频输入图 | `video-inputs` |
| `materials` | 素材下载 | `materials` |

自定义目录写在 `<root>/storage-paths.json`，重启后保留。`POST /api/storage` 可改单个目录；`topaz` 目录不支持自动搬移已有文件（返回 `noMove: true`）。

## 架构

包结构先按技术分层，再按业务模块划分：

```text
com.lumenforge
├── controller            20 个控制器 + 1 个异常支持基类
│   ├── account/ canvas/ task/ image/ video/ material/
│   ├── ncanvas/ canvasai/ story/ topic/ publish/
│   └── topaz/ web/ common/ storage/
├── service               业务规则与事务边界（*Service + *ServiceImpl）
├── mapper                Spring Data JPA 仓储接口
├── entity                表映射实体（按模块分包）
├── provider              外部厂商客户端
│   ├── RunningHubClient / ImageGenerationClient / VideoGenerationClient
│   └── ImageProtocols / ImageRequest / VolcSigner / DownloadedImage
├── storage               StoragePaths（8 类目录与迁移状态）
├── security              CredentialCipher（AES-GCM 版本化加密）
└── common                util(Ids/Texts) / api(ApiResponse/GlobalExceptionHandler) / config
```

依赖方向 `controller → service → mapper`。控制器只绑定 HTTP 输入并返回响应；`*Service` 接口定义业务用例，`*ServiceImpl` 承载规则与事务；Mapper 隔离持久化。画布参数读取与工作流 JSON 解析放在 canvas 的 Service 里，不把 provider 编排塞进 HTTP 层。

### 几个关键设计

- **先落库再发请求**。调度器在发起厂商请求前先把任务存成 `preparing`，拿到远端 ID 后立即落库再轮询；重启后轮询已有远端任务而不是重复提交。
- **凭据加密**。账号密钥与网页登录凭据使用版本化 AES-GCM（`APP_CREDENTIAL_KEY`）；v2 用 128 位密钥以便在 JDK 8u111 默认加密策略下运行。列表响应只暴露脱敏后的 API Key。解密失败（例如换了密钥）只记 warn 并返回原值，避免整张账号列表 500。
- **实体逐列对齐 DDL**。`ddl-auto=validate` 下，DDL 里的 `CHAR(n)` / `BIT(1)` / `JSON` / `DATETIME(6)` / `TEXT` / `LONGTEXT` 都必须在实体上写 `columnDefinition`，否则启动即校验失败。
- **迁移只增不改**。`V1__initial_schema.sql` 已被应用过，改动会导致 Flyway checksum 校验失败；加列请新建 `V2__xxx.sql`。

表关系、索引与删除策略见 [数据库设计.md](数据库设计.md)。

## 接口文档

完整 REST 契约（135 条路由、请求/响应字段、业务错误文案、各厂商默认 base URL）见 **[接口文档.md](接口文档.md)**。

其他文档：

| 文档 | 内容 |
|---|---|
| [需求说明.md](需求说明.md) | 项目目标、角色场景、功能需求、非功能约束、验收标准 |
| [数据库设计.md](数据库设计.md) | 20 张表职责、ER 关系、索引与删除策略、迁移说明 |
| [Python版功能对照与Java迁移计划.md](Python版功能对照与Java迁移计划.md) | 与参考实现的路由对照、有意差异与取舍 |

## 已知限制

以下能力依赖桌面环境，本仓库有意不复刻，行为是返回明确的中文错误而不是静默降级：

- **豆包（Doubao）**：`/api/doubao/status|restart|accounts|switch-account` 四个端点、视频 `provider=doubao`、画布 AI 的 `engine=doubao` 都依赖 Electron 桌面桥。视频与画布 AI 的 doubao 分支返回中文 400 提示切换引擎。
- **节点画布存储**：迁移里没有 `node_canvases` / `node_canvas_chats` / `node_assets` 三张表，改用数据目录下的 `node-canvases.json` 与 `node-assets.json` 快照，零 DDL 改动。
- **素材联网抓取**：`MaterialFetcher` 是可插拔接口，默认实现返回「该素材源暂未接入抓取，请手动导入」；图片搜索/榜单的真实 pixiv 请求已可用。
- **Topaz 执行**：`/api/topaz/enhance` 等执行端点按空壳语义返回 400 `NOT_READY`；引擎探测、档位配置、候选清单与本地目录配置端点可用。
- **剧本引擎**：`GET /api/script-engine` 依赖本机 `codex.exe`，未安装时返回 `available=false, reason=no-exe`。
- **文件服务**：`/files/**` 未实现 HTTP Range，也不做 Topaz 旧目录回落。
- **无鉴权**：不内置登录与会话，默认只监听回环地址。

**测试**：项目当前没有测试依赖，未编写单元测试。CI 只做编译与打包验证。

## 贡献

欢迎 issue 与 PR。提交前请确认：

1. `mvn -q -DskipTests clean package` 通过；
2. 新代码保持 **Java 8 兼容**（不要引入 `java.net.http.*`、`var`、`List.of` 等 JDK 9+ API）；
3. 改动接口时同步更新 `接口文档.md`；
4. 涉及表结构时新建 `V2__xxx.sql`，不要改已应用的迁移文件。

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[MIT](LICENSE) © 2026 Lumen Forge contributors
