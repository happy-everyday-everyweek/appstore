package me.spica27.spicamusic.store

/** 同步渠道：聚合包 / 推荐包，两通道并行独立 */
enum class SyncChannel(
    val repo: String,
    val cacheFileName: String,
) {
    AppIndex("happy-everyday-everyweek/appstore-index", "app-index.json"),
    Discover("happy-everyday-everyweek/appstore-discover", "discover-index.json"),
}

/** 同步模式：Auto=开屏静默；Full=前台全量（首启/损坏兜底） */
enum class SyncMode { Auto, Full }

/** 本次同步应用了哪种包 */
enum class PackageKind { Full, Incremental, None }

data class SyncResult(
    val changed: Boolean,
    val applied: PackageKind?,
    val error: SyncError? = null,
    val errorMessage: String? = null,
) {
    val isOk: Boolean get() = error == null
}

/** 可读的同步错误（不抛未捕获异常） */
enum class SyncError {
    Network, // 网络/API 失败
    ManifestInvalid, // patch.json 解析失败
    ChecksumMismatch, // SHA-256 校验不一致
    PackageInvalid, // 包内容缺失/解压失败
    Storage, // 本地读写失败
    ;

    fun describe(): String =
        when (this) {
            Network -> "网络连接失败（GitHub 直链与全部镜像均不可达，具体原因见日志）"
            ManifestInvalid -> "更新解析清单缺失或无效（patch.json 不存在或内容无法解析）"
            ChecksumMismatch -> "下载包 SHA-256 校验不一致（数据可能被篡改或镜像缓存异常）"
            PackageInvalid -> "同步包内容缺失、解压失败或结构不正确（见日志详情）"
            Storage -> "本地存储读写失败"
        }
}

/** GitHub Release 资产描述（REST /releases/latest 资产项） */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

data class ReleaseInfo(
    val tag: String,
    val assets: List<ReleaseAsset>,
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}
