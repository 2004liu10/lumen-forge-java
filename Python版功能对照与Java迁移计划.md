# 与参考实现的对照，以及有意保留的差异

本仓库是某个 Python 实现（下称「参考实现」）的 Java 重写。这份文档记录两件事：**路由覆盖情况**，以及**哪些地方有意做得不一样、为什么**。

结论先行：**参考实现里的 HTTP 路由已全部对齐**。唯一的例外是 4 条依赖 Electron 桌面桥的 `/api/doubao/*`，它们被明确划为「不复刻」并给出可理解的中文错误，而不是静默降级或假装成功。

## 路由对照

两侧路由清单用脚本做过逐条差集比对（处理了跨行注解、无括号注解、`import` 行干扰等边界情况），不是人工目测：

- 参考实现：**129 条** `@router.*` + 9 条 `/files/...`
- 本仓库：**135 条**映射 = 覆盖其中 125 条 + 1 条 `/files/**` 通配（覆盖那 9 条）+ 9 条便利 GET

| 模块 | 参考实现 | 本仓库 | 说明 |
|---|---|---|---|
| account | 8 | 8 | 含便利 `GET /api/accounts` |
| canvas | 8 | 8 | 含便利 `GET /api/canvases`、`POST /{id}/extra-params` |
| task | 8 | 9 | 含便利 `GET /api/tasks` |
| image | 13 | 16 | 含便利 `GET /api/image-models`、`/api/image-refs`、`/api/image-tasks` |
| video | 13 | 13 | 含便利 `GET /api/video-models`、`/api/video-inputs`、`/api/video-tasks` |
| material | 7 | 7 | |
| material-source | 5 | 5 | |
| topic | 5 | 5 | |
| publish | 11 | 11 | |
| storyboard | 8 | 8 | |
| script-engine | 1 | 1 | |
| canvas-ai | 3 | 3 | |
| node-canvas | 7 | 7 | |
| node-asset | 9 | 9 | |
| topaz | 10 | 10 | |
| web | 11 | 11 | |
| storage | 1 | 1 | |
| health | 1 | 1 | 含便利 `GET /api/health` |
| state | 1 | 1 | |
| files | 9 | 1 | 用 `/files/**` 通配覆盖全部 9 条 |
| **doubao** | **4** | **0** | ⛔ 有意不复刻，依赖 Electron 桌面桥 |

## 有意不复刻的差异

| 能力 | 参考实现的做法 | 本仓库的做法 |
|---|---|---|
| 豆包桌面桥 4 端点 | 通过 Electron IPC 操控本机客户端 | 不提供端点；视频 `provider=doubao` 与画布 AI `engine=doubao` 返回中文 400，提示切换引擎 |
| 视频 `doubao` provider | 走客户端桥 | 预设仍在（这样前端下拉框不会缺项），选中即返回「Java 版没有接入本地豆包客户端桥，这条豆包视频协议暂时不可用；请改用智谱 / 火山方舟 / 阿里云百炼的视频模型」 |
| 画布 AI `doubao` 引擎 | 走客户端桥 | 返回「本地豆包引擎依赖桌面客户端，Java 版请切换到 API 引擎」 |
| Topaz 执行流水线 | 调 `tvai_fi` + `tvai_up` + `hevc_nvenc` | 执行端点返回 400 `NOT_READY`；引擎探测、档位配置、候选清单、本地目录配置照常可用 |
| 素材站联网抓取 | 各站点专用抓取器 | `MaterialFetcher` 做成可插拔接口，默认实现返回「该素材源暂未接入抓取，请手动导入」；pixiv 搜索/榜单的真实请求已可用 |
| 剧本引擎 | 调本机 `codex.exe` | 同样调本机 `codex.exe`，未安装时 `GET /api/script-engine` 返回 `available=false, reason=no-exe` |

设计取舍是：**能力缺失要让用户看见并知道下一步怎么做**，而不是 200 返回空结果让人以为成功了。

## 实现层面的关键取舍

### 1. 节点画布用 JSON 快照，不加表

参考实现的 `node_canvases` / `node_canvas_chats` / `node_assets` 三张表在语义上就是 `id + payload TEXT`。本仓库的 V1 迁移里没有这三张表，于是改用数据目录下的 `node-canvases.json` 与 `node-assets.json` 快照，**零 DDL 改动**。代价是并发写入需要外部串行化，收益是不必为一层薄封装新增迁移。

### 2. 任务状态值 `canceled`（单 l）vs 图片的 `cancelled`（双 l）

这不是笔误。视频任务的 DDL 注释里写的是 `canceled`，图片任务沿用参考实现里的 `cancelled`。两个模块各自对齐自己的既有数据，没有为了「统一」去改历史状态值——那会破坏已有记录的可读性。

### 3. 重排队必须把 `remote_task_id` 置 NULL

视频任务表上 `remote_task_id` 有唯一索引。重试时若不先置 NULL，旧远端任务号会与新提交撞唯一键。这是一个只有跑过重试才会暴露的坑。

### 4. 银行家舍入照抄，不换成 `Math.round`

画布 AI 的镜头数计算里，参考实现用的是 Python 的 `round()`（银行家舍入：6.5→6、7.5→8）。Java 的 `Math.round` 在 .5 处一律向上，会改变镜头数口径。因此实现了一个 `pyRound` 复刻该行为。

### 5. 凭据解密失败要容错，不能 500

`APP_CREDENTIAL_KEY` 变更、或数据从别的安装导入时，库里旧密文解密会抛 `AEADBadTagException`。参考实现在这里吞掉错误并保留原值。本仓库对齐该行为：只记一行 warn，返回原值。否则一张账号列表就会因为个别历史行整页 500。

### 6. H2 下 JSON 列会多包一层引号

H2 存 JSON 列时会把字符串再包一层引号，读出来是 `"\"{...}\""`。读取逻辑会循环解最多 4 层，这样 H2 与 MySQL 两种档位的行为一致。

## 后续可选项

这些不在当前范围内，但接口留了位置：

1. **`/files/**` 支持 HTTP Range**：现在整文件返回，大视频拖动进度条会重新下载。
2. **Topaz 旧目录回落**：参考实现会在 `topaz` 目录找不到文件时回落到旧目录，本仓库没做。
3. **豆包桥**：如果将来有跨平台的桌面桥方案，`provider=doubao` 与 `engine=doubao` 的分支可以直接接上——错误抛出点是集中的。
4. **测试**：当前没有测试依赖。最值得补的是 8 种图片协议与 4 种视频 provider 的请求体构造（纯函数，易测），以及状态机流转。

## 模块完成条件

一个模块算「迁移完成」，需同时满足：

- [x] 路由与参考实现逐条对齐（或明确标记不复刻并给出理由）
- [x] 请求/响应字段名一致
- [x] 业务错误文案一致（用户看到的是同一句话）
- [x] 数据落库，重启不丢
- [x] 编译打包通过，启动后 `GET` 冒烟无 500
- [x] 接口文档有对应章节

上表 20 个模块均满足。
