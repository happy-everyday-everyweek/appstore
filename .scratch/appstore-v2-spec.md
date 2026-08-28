# AppStore 同步机制 v2 设计规格

> 版本：draft-1 ｜ 日期：2026-08-27
> 范围：`appstore-index`（承载仓库，构建/分发侧）+ `appstore`（Android 客户端，同步/下载侧）
> 性质：设计规格，不含实现代码

---

## 0. 摘要

当前同步机制的根本问题不是"增量算法不够聪明"，而是**资产与索引耦合分发**：131MB 的聚合包中约 99% 是 README 图片资产，且每个增量包都携带全量资产（`aggregate.py` 中 `inc_payload.update(assets)`），导致增量包 ≈ 全量包，链式增量在实践中必然退化为全量下载。

v2 的核心设计：**git 仓库为唯一数据真相，清单（manifest）驱动同步，资产按需懒加载，镜像测速从"每文件全量测"退化为"会话级缓存 + 延迟竞速"**。全部基于 GitHub 免费能力（仓库本身 + Releases + 现有公益镜像），零新增服务器、零新增费用、零新增日常维护。

**典型场景收益预估**（按当前 89 个 owner 目录 / ~100 应用规模）：

| 指标 | v1 现状 | v2 目标 |
|---|---|---|
| 常规更新下载量（落后 12~18 版） | ~131MB（全量） | < 100KB（清单 + 变化条目） |
| 首次安装同步下载量 | ~131MB | ~1.1MB（列表索引 + 全部图标；README 详情页懒加载） |
| 每次同步的镜像测速开销 | 每个文件 33 镜像 × 6s 窗口探测 | 每会话 ≤1 次，命中缓存为 0 |
| 客户端需解析的 JSON 数 | 1~19 个（链式回溯） | 恒定 1~2 个 |
| CI 每次发布的网络开销 | 下载上期 full.zip ~131MB 做 diff | 0（git 即真相，无需 diff） |

---

## 1. 现状调研（基于真实代码）

### 1.1 分发链路（appstore-index 侧）

**收录结构**：`apps/<owner>/<repo>/` 下包含 `app.json`（手写）、`app-info.json`（自动生成元数据）、`README.md`、`icon.png`、`readme-assets/**`（README 引用的图片等资源，整个目录入库）。

**发布节奏**：WF4（`wf4-aggregate.yml`）cron `17 */4 * * *`，每天 5 次。变更判定：当天首跑有变更 → 全量包；后续跑新增 1 个应用或变更 ≥2 个 → 增量包；无变更不发布。

**发布物三件套**（`aggregate.py`）：

| 资产 | 实测体积（tag `aggregate-20260826130625`） | 内容 |
|---|---|---|
| `full.zip` | 130,870,702 B（~125 MiB） | `index.json`（全应用元数据）+ 全部图标 + 全部 README + **全部 readme-assets** |
| `incremental.zip` | 130,847,353 B（~125 MiB） | `incremental.json`（新增/变更条目 + 移除 id）+ **同样携带全部资产** |
| `patch.json` | 317 B | `{base, target, algorithm: "structured-json-v1", incrementalSha256, fullSha256, fullSize}` |

关键代码事实：

```python
# aggregate.py —— 增量包携带全部资产
inc_payload = {"incremental.json": ...}
inc_payload.update(assets)   # ← 增量包 = 差异 JSON + 131MB 全量资产
```

CI 侧每次发布还需下载上一期 `full.zip`（~131MB）解析 `index.json` 后做 diff。旧 Release 永不删除，无限增长。另有 WF6 产出 `embeddings.json`（856KB，语义向量；客户端代码无消费方，本规格不涉及，建议另行评估）。

### 1.2 客户端（appstore 侧）

**双通道**（`StoreTypes.kt`）：`AppIndex`（本规格主体）与 `Discover`（推荐内容，full.zip 仅 ~1.6KB，非痛点，但机制可复用 v2）。

**同步引擎**（`SyncEngine.kt`）：
1. 经 33 镜像测速下载 `releases/latest/download/patch.json`；
2. `target == 本地版本` 且资产齐全 → 无更新；
3. `patch.base == 本地版本` → 单级增量（下载 incremental.zip 应用）；
4. 否则链式回溯：逐级拉历史 patch.json + HEAD 探测各级 incremental.zip 体积，**累计增量体积 ≥ 全量体积立即转全量**，大小未知级数超限转全量，链断转全量；
5. 按链逐级下载应用增量包，任一级失败回退最新全量。

**下载底座**（`GitLinkDownloader.kt` + `SpeedTester.kt` + `Mirrors.kt`）：
- 内置 33 个 GitHub 加速镜像（prefix 代理型：`prefix + 原始 GitHub URL`）；
- **每次 `download()` 调用都先对全部 33 镜像并发测速**（每镜像最多拉 12MB、6 秒窗口、4 并发采样、持续速度 ≥2MB/s 达标即停），按评分排序取前 8 逐个尝试；
- 已具备：Range 断点续传、SHA-256 强校验、空文件防护、HTML 挑战页嗅探（gh-proxy 系风控返回 200+HTML）。

**本地存储**（`SyncStore.kt`）：`store/<channel>.json`（索引全文）+ `store/<channel>.version`（版本 tag）+ `store/assets/**`（解包后的资产散文件，UI 直接读）。

### 1.3 定量诊断

1. **增量机制已实质失效**：incremental.zip（130,847,353B）≈ full.zip（130,870,702B）。用户 2~3 天打开一次应用，落后 12~18 个版本，链式回溯在第 2 级就触发"累计 ≥ 全量"转全量——**无论走哪条路，每次更新都是 ~131MB 下载**。
2. **131MB 中 ~99% 是 readme-assets 图片**：仅 `AAswordman/Operit` 一个应用的 readme-assets 就有 13MB；而列表页真正需要的元数据（index.json）压缩前仅数百 KB。
3. **测速开销与文件数成正比**：`patch.json` 仅 317B，也要先跑一轮 33 镜像 6 秒窗口测速；一次同步含多个下载 → 多轮测速。测速本身下载的探测流量（每镜像最多 12MB）可能远超业务数据。
4. **CI 侧成本**：每次发布下载上期 full.zip 131MB 做内存 diff。
5. **Release 无限增长**：每天最多 5 个 release，永不清理。
6. **zip 包形态的天然缺陷**：无法按文件级缓存、无法 304 协商、无法部分重试（任何一环失败整包重来）。

---

## 2. 设计目标与非目标

**目标**：
- G1 更新成本与"落后多少个版本"解耦：恒定为 清单 + 实际变化量。
- G2 列表页数据与详情页数据分离：首次可用 < 1.5MB。
- G3 零新增基础设施：不引入任何服务器、CDN、数据库；构建仍在 GitHub Actions 免费额度内。
- G4 零日常维护：发布全自动，失败自动跳过，无需人工干预。
- G5 客户端逻辑简化：删除链式回溯/字节决策/多包回退状态机，替换为恒定 3 步流程。
- G6 现有镜像体系保留且效率提升（URL 内容稳定 → 镜像缓存命中率上升）。

**非目标**：
- 不做服务端调度/GSLB（明确排除）。
- 不改变收录流程（PR 校验、元数据采集、投票等工作流不动）。
- 不处理 APK 分发（APK 始终留在开发者自己的 Release，客户端直链下载，现有逻辑保留）。
- 不追求弱网下的实时性（4 小时发布粒度不变，raw CDN 数分钟缓存延迟可接受）。

---

## 3. 总体设计

### 3.1 核心思想

**v1 的模型**：把"仓库状态"打包成快照 zip 反复分发（全量），再用 zip 间 diff 做增量。zip 是黑盒，只能整包下载 → 资产越大增量越死。

**v2 的模型**：
1. **git 仓库即数据库**：`apps/` 目录本来就是唯一真相，资产（图标/README/图片）不需要"再分发一遍"——客户端按路径直接拉取 raw 文件。
2. **manifest 是唯一需要"发现"的入口**：一个小 JSON 声明"当前快照里每个逻辑对象的 SHA-256 与路径"。客户端对比本地清单，差什么下什么。
3. **增量 = 清单对比的结果，而非构建产物**：不再生成 incremental.zip，不再有 base/target 链。落后 1 版和落后 100 版，客户端流程完全相同。
4. **列表数据与详情数据分层**：列表索引（含 blurhash 占位）保证秒开；重资产（README 及其图片）按应用粒度打包，点开详情才下载。

### 3.2 发布物形态 v2

```
appstore-index 仓库（main 分支，均为普通文本/图片文件，由 CI 生成提交）
├── apps/…                        # 现状不动：唯一数据真相
└── dist/                         # CI 生成，机器人提交
    ├── manifest.v2.json          # 同步入口清单（~10KB gzip 前 ~25KB）
    ├── index.v2.json             # 列表索引（全应用轻量元数据 + 图标 blurhash）
    └── bundles/                  # 详情包清单（仅引用，不落文件）
        （bundle 实体不进仓库，见 3.3）

Release（每有变更创建一个，轻量）
└── tag: dist-<timestamp>
    ├── manifest.v2.json          # 与 main 分支内容一致（双通道兜底）
    ├── index.v2.json
    └── bundles/<id>.bundle.zst   # 每应用一个详情包（懒加载对象）
```

> 说明：bundle（README + readme-assets 打包，zstd 压缩）作为 Release 资产而非仓库文件，原因是体积占比大（~100MB 级）且希望保持 git 仓库轻量；Release 资产恰好满足"内容不变、URL 永久有效"（`releases/download/<tag>/<file>` 按 tag 寻址，天然稳定）。`latest/download/bundles/<id>.bundle.zst` 仅指向最新版 bundle，配合 manifest 中的完整 tag URL 与 SHA-256 校验使用。

### 3.3 对象与寻址规范

| 对象 | 获取 URL（原始） | 命名/寻址 | SHA-256 来源 |
|---|---|---|---|
| manifest | `…/appstore-index/raw/main/dist/manifest.v2.json` 或 `…/releases/latest/download/manifest.v2.json` | 固定路径 | manifest 自身可 `ETag`/内容哈希协商 |
| 列表索引 | 同上双通道 | 固定路径 | manifest.index.sha256 |
| 图标 | `…/raw/main/apps/<owner>/<repo>/icon.png` | 应用目录路径 | manifest.icons[].sha256 |
| 详情包 | `…/releases/download/dist-<ts>/bundles/<id>.bundle.zst` | tag + 应用 id | manifest.bundles[].sha256 |
| APK | 开发者仓库 Release（不动） | 现状 | app-info.source.sha256 |

所有 URL 经过镜像时遵循现有规则：`mirror.prefix + <原始完整URL>`。**资产 URL 内容稳定性是本设计对镜像的最大杠杆**：图标/详情包的 URL 在内容不变时永不变化，公益镜像的 CDN 缓存可长期命中，第二个用户起基本零回源。

---

## 4. 数据规范

### 4.1 `manifest.v2.json`（同步入口）

```jsonc
{
  "version": 2,                          // 规范版本
  "channel": "app-index",                // 同步通道标识（复用 SyncChannel 语义）
  "generatedAt": "2026-08-27T04:00:17Z",
  "commit": "0f3a…",                     // 生成时 main HEAD（审计用）
  "releaseTag": "dist-20260827040017",   // 本期 Release tag（bundle 下载基址）

  "index": {
    "sha256": "…",                       // index.v2.json 的 SHA-256
    "size": 48213,
    "count": 104                         // 应用条数（快速一致性校验）
  },

  "icons": [                             // 列表页必需资产（独立于 bundle，进列表同步范围）
    { "id": "1048", "path": "apps/AAswordman/Operit/icon.png",
      "sha256": "…", "size": 16384 }
    // ~100 条，每条 ~100B
  ],

  "bundles": [                           // 详情页懒加载资产（应用粒度）
    { "id": "1048",
      "url": "bundles/1048.bundle.zst",  // 相对 releaseTag 资产基址
      "sha256": "…", "size": 13173000 }
    // ~100 条
  ]
}
```

体积估算：icons + bundles ≈ 200 条 × ~110B ≈ 25KB（gzip 后 ~6KB）。

**设计约束**：
- manifest 是唯一"版本真相"。客户端不比对时间戳，只比对 SHA-256。
- `removed` 不需要显式字段：本地清单与 manifest 的差集即删除集。
- `index.sha256` 不匹配 → 重新拉 index；条目缺失 → 补拉对应对象。全部动作由对比驱动。

### 4.2 `index.v2.json`（列表索引）

保留 v1 `index.json` 的字段，做两层裁剪：

```jsonc
{
  "1048": {
    // —— 列表层（列表页渲染所需，必须轻）——
    "id": "1048", "repo": "AAswordman/Operit",
    "name": "Operit AI", "packageName": "com.ai.assistance.operit",
    "summary": "…", "openSource": true, "grade": "D",
    "specialPermissions": ["none"],
    "version": { "versionName": "1.12.1", "versionCode": 121 },
    "icon": "1048",                      // 指向 manifest.icons[].id
    "iconBlurhash": "UBIAXt…",           // 图标 blurhash 占位（复用客户端已有 BlurHash 能力）

    // —— 详情层（点开详情时从 bundle 取，不进 index）——
    // permissions / readme / upstream / source{license, apkUrl, sha256, openSourceVerified}
    // → 全部移入 bundle 内 detail.json
  }
}
```

体积估算：~100 应用 × ~450B ≈ 48KB（gzip 后 ~12KB）。

**分桶预留（非本期）**：当应用规模 > 2000、单索引 gzip 后 > 100KB 时，将 index 按 `appId % 16` 分桶，manifest 列每桶 SHA，客户端只拉变化桶。规范预留 `index.shards` 字段扩展位，本期固定单文件。

### 4.3 `bundles/<id>.bundle.zst`（详情包，懒加载）

```
bundle.zst（zstd 压缩的 tar 或 zip）
├── detail.json        # permissions / readme 元信息 / upstream / source{…}
├── README.md          # 已完成 readme-assets/ → <id>_files/ 前缀改写（沿用 v1 约定）
└── <id>_files/**      # readme-assets 内容
```

- 每应用一个包，未变化应用跨版本 URL/SHA 不变 → 镜像缓存直接命中，同一应用的历史读者越多后续越快。
- zstd 压缩（Android API 29+ 系统支持 zstd 解码？——客户端用 bundled zstd-jni 或退化为 gzip，见 7.6 实现注意）。

### 4.4 算法标识

`manifest.v2.json` 中 `"version": 2` 即新协议标识。客户端兼容判定：

- `releases/latest/download/manifest.v2.json` HTTP 200 → v2 协议；
- 404 → 对方仍是 v1 形态 → 走旧 SyncEngine（保留一个版本周期后移除）。

---

## 5. 构建端规范（appstore-index CI）

### 5.1 WF4 改造要点

```text
输入：main 分支 apps/ 全量目录
步骤：
 1. 扫描 apps/**，构建 index.v2.json（含每应用图标 blurhash 计算）
 2. 对每个 icon.png 计算 SHA-256 → manifest.icons[]
 3. 对每个应用：README.md 前缀改写 + readme-assets 打包 → bundles/<id>.bundle.zst，
    计算 SHA-256 → manifest.bundles[]
 4. 与上一期已发布 manifest 对比（读取仓库 dist/manifest.v2.json 即可，~25KB）：
      - 无任何变化 → 结束（不提交、不发 Release）
      - 有变化 → 继续
 5. bot commit：更新 dist/manifest.v2.json + dist/index.v2.json
 6. 创建 Release（tag = dist-<ts>）并上传：manifest.v2.json、index.v2.json、
    以及【仅当期 SHA 变化的】bundles/<id>.bundle.zst
```

关键差异：
- **删除**：下载上期 full.zip（131MB）、diff 计算、incremental.zip 生成。
- bundle 只上传 SHA 变化的条目：未变化 bundle 复用上期 Release 的资产 URL。manifest.bundles[].url 从"相对当期 tag"改为携带完整 tag 限定（`dist-<ts>/bundles/<id>.bundle.zst`），确保历史 URL 永久有效。
- 发布频率维持每 4 小时检查一次；无变更零动作。

### 5.2 Release 治理

- tag 命名 `dist-<UTC timestamp>`，body 附变更摘要（新增/变更/移除应用数）。
- **清理策略**：保留最近 N=7 天的 dist-* Release；更早的仅在"其 bundle 仍被当前 manifest 引用"时保留，否则删除（GitHub Release 资产删除后 URL 失效——因此清理规则必须是：**被最新 manifest 引用的 tag 永不删除**）。当前 manifest 引用的 bundle 全部集中在最新 1~2 个 tag 中，清理是安全的。
- WF6（embeddings.json）：客户端无消费方，建议冻结现状，不纳入本规格。

### 5.3 机器人提交权限

CI 使用现有 `GITHUB_TOKEN` + `permissions: contents: write`，与现状一致，无新增 secrets。

---

## 6. 客户端规范（appstore）

### 6.1 新同步引擎（替换 `SyncEngine` 主流程）

```text
sync(channel):
 1. FETCH_MANIFEST
    url 优先序：镜像加速的 raw(main)/dist/manifest.v2.json
             → 镜像加速的 releases/latest/download/manifest.v2.json
    （~25KB；If-None-Match 协商可选）
    失败 → 返回 Network 错误（不清空本地数据）

 2. COMPARE
    本地状态 = {indexSha, icons: Map<id, sha>, bundles: Map<id, sha>}
    manifest.index.sha256 == 本地 && icons 差集为空 → 无更新，结束
    （bundle 不参与"是否有更新"判定——懒加载对象允许滞后）

 3. SYNC_INDEX_AND_ICONS
    a. index.sha256 变化 → 拉 index.v2.json（双通道）→ 校验 SHA → 原子替换本地索引
    b. icons 对比：新增/变化的图标（并发 ≤6，走镜像调度器）
       → 校验 SHA → 写入 store/assets/icons/<id>.png
       → 移除 manifest 中已不存在的图标文件
    c. 原子提交本地状态（indexSha + icons 快照）
    d. bundles 记录 = manifest.bundles 的 SHA 表（仅记录，不下载）；
       本地已缓存 bundle 的 SHA 不在新表中 → 删除本地缓存文件

 4. （异步/懒加载）FETCH_BUNDLE(id)
    用户进入详情页时：本地无缓存或 SHA 不匹配 → 走镜像调度器下载
    → 校验 SHA → 解包到 store/assets/bundles/<id>/ → 详情页渲染
    失败 → 详情页展示错误 + 重试按钮（不影响列表）

删除的 v1 概念：链式回溯、`resolveIncrementalChain`、字节累计决策、`MAX_CHAIN_LENGTH`/`UNKNOWN_SIZE_LIMIT`、多包回退链（`applyChain`/`applyIncremental` 的全量兜底交织逻辑）。v2 的回退只有一个维度：**对象级失败重试 + 换镜像**，包级回退不复存在（对象粒度足够小）。

### 6.2 本地存储布局 v2

```
store/
├── manifest.snapshot.json      # 上次成功同步的 manifest（对比基准）
├── index.v2.json               # 列表索引
├── assets/
│   ├── icons/<id>.png          # 列表图标
│   └── bundles/<id>/…          # 详情包解包内容（懒加载，可整目录清理）
```

沿用 `SyncStore` 的目录习惯，`StoreAssets` 访问入口不变，UI 层零改动。

### 6.3 镜像调度器（改造 `GitLinkDownloader` + `SpeedTester`）

**原则：测速从"文件级前置动作"退化为"会话级选择 + 下载中自适应"。**

```text
MirrorScheduler：
- 状态（DataStore 持久化）：
    perMirror: { lastLatencyMs, lastOkAt, consecutiveFails, ewmaBps }

- 会话首次请求（进程启动后首次网络同步）：
    并发对全部镜像发 HEAD（或 Range: bytes=0-0），500ms 超时
    记录可达性 + 首字节延迟；结果持久化，TTL 24h
  TTL 命中 → 直接用上次 top-3，跳过探测

- 对象下载（manifest / index / icon / bundle 通用）：
    1. 候选 = top-3 镜像 + GitHub 直连
    2. 竞速（hedged request）：首选源先发；400ms 内未出首字节 → 并发第二源；
       首字节到达即定胜者，取消其余
    3. 下载中失速检测：吞吐 < 32KB/s 持续 5s → 断点续传切下一候选源
       （Range 续传能力保留）
    4. 完成/失败回写 ewmaBps / consecutiveFails → 影响下次排序
    连续失败 ≥3 次的镜像在会话内降级到队尾，24h 后自动复活

- 保留 v1 的既有防护：SHA-256 校验、空文件检测、HTML 挑战页嗅探
```

删除的 v1 行为：每次 `download()` 前对 33 镜像的 12MB/6s 全量测速（`rankMirrors`）、测速达标即停阈值逻辑。测速探测流量从"每文件最多 33×12MB"降为"每会话 33×0B（HEAD）"。

### 6.4 UI 阶段文案（`onStage` 映射）

| 阶段 | 文案 |
|---|---|
| FETCH_MANIFEST | 正在检查更新… |
| SYNC_INDEX | 正在同步应用列表… |
| SYNC_ICONS | 正在补齐图标（x/y）… |
| FETCH_BUNDLE | 正在加载应用详情… |

### 6.5 降级与兼容

- **协议回退**：manifest.v2 404 → 旧 `SyncEngine`（v1 三件套）完整保留，入口处一次探测分支。v2 稳定一个版本周期后删除 v1 引擎与 `structured-json-v1` 算法支持。
- **Discover 通道**：体积极小（1.6KB），v1 引擎保留即可；若追求架构统一可套用 v2 manifest（bundles 语义映射为 articles/covers），列为 P2。
- **raw 通道缓存滞后**：镜像 CDN 对 raw URL 缓存约 5 分钟。若 manifest 拿到新 SHA 而资产 URL 返回旧内容 → SHA 校验失败 → 按 1s/5s/30s 退避重试 3 次 → 仍失败则切镜像。列表功能不受影响（index/icons 经 release 通道优先）。
- **镜像 URL 规范依赖**：部分 prefix 型镜像可能不支持 raw.githubusercontent.com。调度器维护两个可达性位（release 直链支持 / raw 直链支持），探测时分别记录；manifest 与 index 的 raw 通道仅路由到支持 raw 的镜像。

### 6.6 实现注意

- zstd：客户端引入 `com.github.luben:zstd-jni`（~1MB，纯 JNI）或 CI 改产出 `.zip`（DEFLATE）零依赖。建议 P0 先 zip、P1 换 zstd（bundle 平均收益约再降 30%）。
- blurhash：图标 blurhash 由 CI 计算（Python `blurhash` 库），客户端已有 `BlurHashDecoder` 直接复用；列表页图标加载态先用 blurhash 绘制，网络图标到达后替换。
- 并发与原子性：icons 批量下载中途失败 → 本次同步标记 partial，下次自动续（对比逻辑天然幂等，无需断点状态机）。

---

## 7. 边界情况

| 场景 | 处理 |
|---|---|
| 用户首次安装 | manifest + index + 全部 icons ≈ 25KB + 48KB + ~1MB；详情全部懒加载 |
| 应用被移除收录 | manifest 条目消失 → 客户端差集删除本地索引条目、图标、bundle 缓存 |
| CI 提交了 main 但 Release 创建失败（或反之） | 双通道内容一致才被客户端接受（SHA 校验兜底）；单侧滞后最多一个发布周期，无一致性问题 |
| 镜像返回挑战页 HTML | 沿用现有嗅探逻辑（≤512KB 文本资产检查 `<html` 前缀） |
| 极弱网 | 对象粒度小 + Range 续传 + 会话内换源；icons 支持逐个完成（partial 状态） |
| git 仓库膨胀（readme-assets 历史累积） | 监控点：仓库 > 1GB 时启用 dist orphan branch 方案（assets 移出 main 历史，force-push 重建）。本期不做，写入运维备注 |
| Release 资产被误删 | manifest.bundles[].url 含 tag，被当前 manifest 引用的 tag 受清理规则保护；误删 → 客户端 SHA/404 失败 → 详情页重试，列表不受影响 |

---

## 8. 迁移计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0（1 个 PR，index 仓库）** | WF4 改造：产出 dist/manifest.v2.json + index.v2.json + bundles 并发布轻量 Release；**v1 三件套并行继续发布**（双轨） | manifest 校验通过；bundle SHA 稳定性：无变更期两版 manifest 完全一致 |
| **P1（1 个 PR，客户端）** | 新 `ManifestSyncEngine` + `MirrorScheduler`；探测 manifest.v2 决定新旧引擎；v1 引擎代码保留 | 落后 18 版模拟：下载量 < 100KB；测速仅会话首次发生 |
| **P2（稳定一个版本周期后）** | index 仓库停发 v1 三件套；客户端删除 v1 引擎；discover 通道套用 v2；bundle 换 zstd | v1 资产 404 后客户端无感知（已全部走 v2） |

回滚方案：任一阶段异常，客户端入口探测自动落回 v1 引擎；index 仓库恢复 v1 发布即可。双轨期成本 = CI 多产出一份 25KB manifest + index，可忽略。

---

## 9. 验收标准（v2 全量上线后）

1. 模拟"落后 12~18 版"：同步总下载量 < 100KB（不含懒加载 bundle）。
2. 首次安装：列表页可交互前总下载量 < 1.5MB。
3. 一次同步会话内镜像探测 ≤ 1 轮（HEAD，总流量 ≈ 0）。
4. 无变更的 4 小时周期：CI 不产生 commit / Release。
5. 同一图标/详情包在两个连续版本间 SHA 不变（镜像缓存可命中）。
6. 客户端同步代码路径数：状态机 ≤ 5 个状态（v1 链式回溯相关代码全删）。

---

## 附录 A：v1 → v2 概念映射

| v1 | v2 |
|---|---|
| `patch.json`（base/target/algorithm/sha） | `manifest.v2.json`（对象级 SHA 清单） |
| `full.zip`（index + 全部资产） | `index.v2.json`（纯列表数据）+ `icons/*`（独立小文件） |
| `incremental.zip`（diff JSON + 全部资产） | 删除——增量 = manifest 对比结果 |
| 链式回溯 / 字节决策 / MAX_CHAIN_LENGTH | 删除——流程与落后版本数无关 |
| `structured-json-v1` | `manifest.v2`（version: 2） |
| 每文件 33 镜像 12MB 测速 | 会话级 HEAD 延迟探测 + 下载中竞速/换源 |
| `store/assets/` 解包散文件 | `store/assets/{icons,bundles}/`（结构不变，UI 无感） |

## 附录 B：镜像 URL 规范（调度器实现依据）

| 对象 | 原始 URL 模板 | 镜像路由 |
|---|---|---|
| manifest (raw) | `https://raw.githubusercontent.com/<repo>/main/dist/manifest.v2.json` | 仅支持 raw 的镜像 |
| manifest (release) | `https://github.com/<repo>/releases/latest/download/manifest.v2.json` | 全部镜像（现状能力） |
| index | 同 manifest 双通道 | 同上 |
| icon | `https://raw.githubusercontent.com/<repo>/main/apps/<o>/<r>/icon.png` | 仅支持 raw 的镜像 |
| bundle | `https://github.com/<repo>/releases/download/dist-<ts>/bundles/<id>.bundle.zst` | 全部镜像 |
| APK | 开发者仓库 release 直链 | 全部镜像（现状不动） |
