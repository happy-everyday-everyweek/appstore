package me.spica27.spicamusic.dsh
import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.spica27.spicamusic.terminal.TerminalSessionManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * DeepSeek Harness 自动部署与生命周期管理。
 *
 * 职责边界（与终端环境分工）：
 * - Kotlin 侧只做「环境装配」里不适合在终端内做的部分：下载 bootstrap、解压、
 *   权限/软链处理（照搬 TermuxInstaller 的逻辑）。
 * - 其余一切（apt 装依赖、安装 dsh、启停、更新、插件）都在终端环境内的
 *   bash 脚本中完成，App 只负责把命令写进 PTY 会话。
 * - 服务未启动时自动启动，不需要用户确认。
 */
object DshManager {
    const val DSH_PORT = 3080
    const val DSH_URL = "http://127.0.0.1:$DSH_PORT"

    private val okHttp by lazy { OkHttpClient.Builder().build() }

    // 国内可达性考虑：按序尝试镜像，全部失败再报错
    private val bootstrapUrls =
        listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/bootstraps/bootstrap-%ARCH%.zip",
            "https://mirrors.aliyun.com/termux/apt/termux-main/bootstraps/bootstrap-%ARCH%.zip",
            "https://packages.termux.dev/apt/termux-main/bootstraps/bootstrap-%ARCH%.zip",
        )

    sealed class EnvState {
        data object NotInstalled : EnvState()

        data class Downloading(
            val percent: Int,
        ) : EnvState()

        data object Extracting : EnvState()

        data object Installing : EnvState()

        data object Ready : EnvState()

        data class Error(
            val message: String,
        ) : EnvState()
    }

    sealed class ServiceState {
        data object Stopped : ServiceState()

        data object Starting : ServiceState()

        data object Running : ServiceState()

        data class Error(
            val message: String,
        ) : ServiceState()
    }

    private val _envState = MutableStateFlow<EnvState>(EnvState.NotInstalled)
    val envState: StateFlow<EnvState> = _envState.asStateFlow()

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private var ensureJob: kotlinx.coroutines.Job? = null

    // ---------- 路径 ----------

    fun dshDir(context: Context): File = File(context.filesDir, "dsh")

    fun scriptsDir(context: Context): File = File(dshDir(context), "scripts")

    fun prefixDir(context: Context): File = TerminalSessionManager.prefixDir(context)

    fun bootstrapZipFile(context: Context): File = File(context.filesDir, "bootstrap.zip")

    fun stagingPrefixDir(context: Context): File = File(context.filesDir, "usr-staging")

    /** 工作区（dsh 项目目录），放 /sdcard 便于手机文件管理。 */
    fun workspaceDir(): File = File("/storage/emulated/0/dsh-workspace")

    // ---------- 查询 ----------

    fun isEnvironmentInstalled(context: Context): Boolean = TerminalSessionManager.isEnvironmentInstalled(context)

    fun isServiceRunning(): Boolean = probePort(DSH_PORT, timeoutMs = 800)

    fun currentArch(): String =
        when (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a") {
            "arm64-v8a", "arm64" -> "aarch64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> "aarch64"
        }

    // ---------- 一键就绪（幂等，自动执行，无需用户确认） ----------

    fun ensureReady(context: Context) {
        if (ensureJob?.isActive == true) return
        ensureJob =
            GlobalScope.launch {
                // 1. 环境
                if (!isEnvironmentInstalled(context)) {
                    val ok = installEnvironment(context)
                    if (!ok) return@launch
                }
                _envState.value = EnvState.Ready
                // 2. 部署 dsh（幂等：已装则跳过）
                if (!isDshInstalled(context)) {
                    _envState.value = EnvState.Installing
                    runInTerminal(context, buildScriptCommand(context, "dsh-install.sh", "npm"))
                    waitForDshInstalled(context)
                }
                // 3. 服务
                if (!isServiceRunning()) {
                    runInTerminal(context, buildScriptCommand(context, "dsh-ctl.sh", "start"))
                    waitForServiceUp()
                }
                _initialized.value = true
            }
    }

    // ---------- 环境装配（下载 + 解压 + 权限 + 软链） ----------

    private suspend fun installEnvironment(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val zip = bootstrapZipFile(context)
                // 1. 下载（带进度）
                var downloaded = false
                for (template in bootstrapUrls) {
                    val url = template.replace("%ARCH%", currentArch())
                    try {
                        downloadWithProgress(context, url, zip) ?: continue
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        zip.delete()
                    }
                }
                if (!downloaded) {
                    _envState.value =
                        EnvState.Error("bootstrap 下载失败：请检查网络（需要能访问 Termux 镜像）")
                    return@withContext false
                }

                // 2. 解压（照搬 TermuxInstaller：staging → 软链 → chmod → rename）
                _envState.value = EnvState.Extracting
                extractBootstrap(context, zip)

                // 3. 终端内安装基础包（bash 在 Kotlin 侧装配完成后即可用）
                _envState.value = EnvState.Installing
                runInTerminal(
                    context,
                    "${prefixBash(context)} -c 'cd ~ && apt update -y && pkg install -y nodejs git pnpm curl || true'",
                )
                // 环境已更换（Android sh → Termux bash），销毁旧会话，下次进入终端自动用 bash
                TerminalSessionManager.destroySession()
                true
            } catch (e: Exception) {
                _envState.value = EnvState.Error(e.message ?: e.toString())
                false
            }
        }

    private fun downloadWithProgress(
        context: Context,
        url: String,
        target: File,
    ): File? {
        val request = Request.Builder().url(url).build()
        okHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw RuntimeException("empty body")
            val total = body.contentLength()
            var read = 0L
            val buf = ByteArray(64 * 1024)
            FileOutputStream(target).use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            _envState.value =
                                EnvState.Downloading((read * 100 / total).toInt())
                        }
                    }
                }
            }
        }
        return target
    }

    private fun extractBootstrap(
        context: Context,
        zip: File,
    ) {
        val staging = stagingPrefixDir(context)
        val prefix = prefixDir(context)
        staging.deleteRecursively()
        prefix.deleteRecursively()
        staging.mkdirs()

        val symlinks = mutableListOf<Pair<String, String>>()
        val buffer = ByteArray(8192)

        ZipInputStream(zip.inputStream().buffered()).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    BufferedReader(InputStreamReader(zipInput)).useLines { lines ->
                        for (line in lines) {
                            val parts = line.split("←")
                            if (parts.size != 2) throw RuntimeException("Malformed symlink line: $line")
                            symlinks.add(parts[0] to File(staging, parts[1]).absolutePath)
                        }
                    }
                } else {
                    val target = File(staging, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val n = zipInput.read(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                        if (name.startsWith("bin/") ||
                            name.startsWith("libexec") ||
                            name.startsWith("lib/apt/apt-helper") ||
                            name.startsWith("lib/apt/methods")
                        ) {
                            Os.chmod(target.absolutePath, 0x1C0) // 0700
                        }
                    }
                }
                entry = zipInput.nextEntry
            }
        }

        if (symlinks.isEmpty()) throw RuntimeException("No SYMLINKS.txt encountered")
        for ((old, new) in symlinks) {
            File(new).parentFile?.mkdirs()
            Os.symlink(old, new)
        }

        if (!staging.renameTo(prefix)) {
            throw RuntimeException("Moving staging to prefix failed")
        }
        zip.delete()
    }

    // ---------- 终端内命令 ----------

    private fun prefixBash(context: Context): String = "${TerminalSessionManager.prefixDir(context)}/bin/bash"

    private fun ensureAssetsCopied(context: Context) {
        // scripts -> files/dsh/scripts/
        val scriptsDir = scriptsDir(context)
        listOf("dsh-install.sh", "dsh-ctl.sh").forEach { script ->
            if (!File(scriptsDir, script).exists()) {
                runCatching {
                    context.assets.open("dsh/scripts/$script").use { input ->
                        File(scriptsDir, script).apply { parentFile?.mkdirs() }.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
        // vendor（内置 dsh tgz）-> files/home/.dsh/vendor/（终端 $HOME 下）
        val vendorTarget = File(File(context.filesDir, "home/.dsh/vendor"), "dsh.tgz")
        if (!vendorTarget.exists()) {
            runCatching {
                context.assets.open("dsh/vendor/dsh.tgz").use { input ->
                    vendorTarget.apply { parentFile?.mkdirs() }.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun buildScriptCommand(
        context: Context,
        script: String,
        vararg args: String,
    ): String {
        ensureAssetsCopied(context)
        val scripts = scriptsDir(context)
        val argsStr = args.joinToString(" ")
        return "\$PREFIX/bin/bash '$scripts/$script' $argsStr"
    }

    private fun runInTerminal(
        context: Context,
        command: String,
    ) {
        TerminalSessionManager.writeCommand(context, command)
    }

    private fun isDshInstalled(context: Context): Boolean {
        val engineFile = File(context.filesDir, "home/.dsh/engine.env")
        return engineFile.exists()
    }

    private suspend fun waitForDshInstalled(context: Context) {
        repeat(600) {
            if (isDshInstalled(context)) return
            kotlinx.coroutines.delay(1000)
        }
    }

    private suspend fun waitForServiceUp() {
        repeat(60) {
            if (isServiceRunning()) {
                _serviceState.value = ServiceState.Running
                return
            }
            kotlinx.coroutines.delay(1000)
        }
        _serviceState.value = ServiceState.Error("dsh 服务启动超时")
    }

    private fun probePort(
        port: Int,
        timeoutMs: Int = 800,
    ): Boolean =
        try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..599
        } catch (_: Exception) {
            false
        }

    /** 供设置页/终端页展示状态时手动刷新。 */
    fun refreshServiceState() {
        _serviceState.value =
            if (isServiceRunning()) {
                ServiceState.Running
            } else {
                ServiceState.Stopped
            }
    }
}
