# 设计文档：Only 客户端

> 依赖：docs/SPEC.md v0.3
> 创建：2026-08-22
> 设计语言：深层模块（小接口 + 大实现），接缝可测试

## 模块地图

```
app（Compose UI 薄层：页面 + 底栏 + DI 装配）
 ├─ ui/home       底栏（推荐/全部/搜索/设置）+ 页面容器
 ├─ ui/discover   推荐页（占位，二期做卡片流）
 ├─ ui/library    全部页（应用列表 + 顶部搜索框入口）
 ├─ ui/search     搜索页
 ├─ ui/settings   设置页（沿用现行结构）
 ├─ ui/detail     详情页（新建）
 └─ ui/about      关于页（沿用）

store-model（纯计算深模块，放 common 内 appstore 包）
 ├─ AppMeta / AppIndex / PatchManifest / Card 实体
 ├─ AppIndexParser / PatchManifestParser（JSON 解析）
 └─ ChangeDetector（聚合包变更判定，供测试与工作流 4 参照）

store-sync（深模块：市场同步引擎）★核心
 ├─ SyncEngine.sync(channel, mode): SyncResult  ← 唯一入口
 ├─ 内部：Release 检查 → 解析清单 → 增量/全量下载 → 补丁应用 → 校验 → 缓存
 ├─ adapter: GitHubClient（注入；测试用 in-memory fake）
 └─ adapter: Downloader（注入；测试用 fake）

store-download（深模块：下载底座，GitLink 底层化）
 ├─ Downloader.download(request, onProgress): DownloadResult
 └─ 内部：GitLink 分块加速 / 断点续传 / 镜像切换 / SHA-256 校验

self-update（小模块：客户端自身更新，独立于商店收录）
 └─ SelfUpdater.check(): UpdateInfo?
```

## 保留 / 移除

保留：navkit（导航）、core-preferences（偏好存储）、feature-settings-domain（设置读写 facade）、theme（SPICaMusicTheme、液态玻璃）、baselineprofile、common、app 的 dsh/terminal/chat 代码（减少破坏，不在本期动）。

移除：feature-library-data/domain、feature-player-data/domain、feature-lyrics-data/domain；音乐 UI（player、album、artist、playlist、favorite、allsong、albumdetail、artistdetail、playlistdetail、mostedplayed、listeningstats、ignoredsongs、scan、widget、audioeffects、lyrics）。

## 接口契约

### SyncEngine（store-sync）

```kotlin
enum class SyncChannel { AppIndex, Discover }   // 应用列表聚合包 / 推荐包
enum class SyncMode { Auto, Full }               // Auto=开屏静默增量；Full=前台全量（首启/损坏兜底）

data class SyncResult(
    val changed: Boolean,            // 是否有数据更新
    val applied: PackageKind?,       // Full / Incremental / None
    val error: SyncError?,           // 失败原因（可读）
)

interface SyncEngine {
    suspend fun sync(channel: SyncChannel, mode: SyncMode = SyncMode.Auto): SyncResult
}
```

不变量：Auto 模式绝不阻塞 UI；Full 模式返回前数据已落盘；重复调用幂等（无变更返回 changed=false）。错误模式：网络失败、校验和不一致、解析失败均以 SyncError 携带可读信息返回，不抛未捕获异常。

### Downloader（store-download，移植 GitLink）

```kotlin
data class DownloadRequest(
    val url: String,          // GitHub Release 资产直链（固化链接）
    val dest: File,
    val sha256: String?,      // 校验和，空则不校验
    val expectedSize: Long?,  // 已知则做空文件/短读防护
)

data class DownloadResult(val file: File, val bytes: Long)

interface Downloader {
    suspend fun download(
        request: DownloadRequest,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult
}
```

内部隐藏：分块并行、断点续传、镜像切换、<10KB 空文件防护重试（最多 3 轮，沿用 GitLink 策略）。

### SelfUpdater（self-update）

```kotlin
data class UpdateInfo(val versionName: String, val downloadUrl: String, val releaseUrl: String)

interface SelfUpdater {
    suspend fun check(): UpdateInfo?   // 内置自身仓库地址，静默比较最新 Release 与本地版本
}
```

## 接缝与测试

- SyncEngine 的测试面 = sync() 的返回值。给定 fake GitHubClient（预置 Release/资产状态）+ fake Downloader（预置下载物）+ 临时目录，断言 SyncResult。
- Downloader 测试面 = download() 结果；用本地 HTTP 测试服务器验证分块与断点。
- ChangeDetector 为纯函数，直接单测。
- UI 测试覆盖页面流转（底栏切换、点击搜索框进搜索页、详情页字段展示）。

## 待实施次序（供实施技能拆工单）

1. 仓库创建与项目初始化（三个 GitHub 仓库、git init/commit/push）
2. 裁音乐（模块与 UI 移除，App.kt 装配调整）
3. store-model 实体与解析器
4. store-download（GitLink 移植）
5. store-sync 同步引擎
6. UI 改造：底栏四 tab + 搜索页 + 全部页 + 详情页（推荐页占位）
7. self-update + 设置页接入