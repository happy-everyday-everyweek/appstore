package me.spica27.spicamusic.store.gitlink

import org.json.JSONObject

/**
 * 镜像站点定义。
 * prefix 表示 URL 前缀（最终链接 = prefix + 原始 GitHub 链接）。
 */
data class Mirror(
    val id: String,
    val name: String,
    val prefix: String,
    val note: String = "",
    val builtin: Boolean = true,
)

/**
 * 内置镜像列表。
 *
 * 域名来源：参考 Operit（AAswordman/Operit，GitHub 更新模块 GithubReleaseUtil.kt）
 * 内置的 33 个 GitHub 加速镜像，并结合用户提供的测速清单标注观测状态。
 */
object Mirrors {
    /** 生成前缀 URL：host 需要以 / 结尾 */
    private fun p(host: String): String = if (host.endsWith("/")) host else "$host/"

    val DEFAULT: List<Mirror> =
        listOf(
            // ---- 用户测速清单中可用的镜像（按延迟排序）----
            Mirror("cxkpro", "Cxkpro", p("https://ghproxy.cxkpro.top"), "镜像 455ms"),
            Mirror("gh-proxy", "Gh-Proxy", p("https://gh-proxy.com"), "镜像 635ms"),
            Mirror("geekertao", "GeekerTao", p("https://ghfile.geekertao.top"), "镜像 1787ms"),
            Mirror("nxnow", "Nxnow", p("https://gh.nxnow.top"), "镜像 1957ms"),
            Mirror("ghfast", "Ghfast", p("https://ghfast.top"), "镜像 2215ms"),
            Mirror("npee", "Npee", p("https://down.npee.cn/?"), "镜像 2551ms"),
            Mirror("jasonzeng", "JasonZeng", p("https://gh.jasonzeng.dev"), "镜像 2876ms"),
            Mirror("chjina", "Chjina", p("https://gh.chjina.com"), "镜像 2999ms"),
            Mirror("monlor", "Monlor", p("https://gh.monlor.com"), "镜像 3720ms"),
            Mirror("ghproxynethyph", "GhProxyNetHyph", p("https://gh-proxy.net"), "镜像 3583ms"),
            Mirror("ednovas", "Ednovas", p("https://github.ednovas.xyz"), "镜像 4060ms"),
            Mirror("ghproxynet", "GhProxyNet", p("https://ghproxy.net"), "镜像 4418ms"),
            Mirror("github", "GitHub原始链接", p("https://github.com"), "官方直连 4683ms"),
            Mirror("zwy", "Zwy", p("https://gh.zwy.one"), "镜像 4911ms"),
            Mirror("mrhjx", "Mrhjx", p("https://gitproxy.mrhjx.cn"), "镜像 5005ms"),
            Mirror("bokimoe", "BokiMoe", p("https://github.boki.moe"), "镜像 5301ms"),
            Mirror("fastgitcc", "FastGitCc", p("https://fastgit.cc"), "镜像 5374ms"),
            Mirror("crashmc", "CrashMc", p("https://cdn.crashmc.com"), "镜像 6065ms"),
            Mirror("monkeyray", "Monkeyray", p("https://ghproxy.monkeyray.net"), "镜像 6085ms"),
            // ---- 用户测速清单中不可用/异常的镜像（保留供后续重试）----
            Mirror("firewall", "Firewall", p("https://firewall.lxstd.org"), "UnknownHost"),
            Mirror("gh188", "Gh188", p("https://ghproxy.1888866.xyz"), "SocketTimeout"),
            Mirror("ghproxycfd", "GhPro", p("https://ghproxy.cfd"), "SocketTimeout"),
            Mirror("ghprox", "GhProx", p("https://ghproxy.com"), "UnknownHost"),
            Mirror("gitmirror", "GitMirr", p("https://hub.gitmirror.com"), "UnknownHost"),
            Mirror("h233", "H233", p("https://gh.h233.eu.org"), "HTTP 403"),
            Mirror("hwinzniej", "Hwinzniej", p("https://ghpxy.hwinzniej.top"), "HTTP 403"),
            Mirror("limoru", "Limoru", p("https://github.limoruirui.com"), "SocketTimeout"),
            Mirror("likk", "LIkk", p("https://gh.llkk.cc"), "SocketTimeout"),
            Mirror("moeyy", "Moeyy", p("https://github.moeyy.xyz"), "SocketTimeout"),
            // ---- 补充镜像 ----
            Mirror("ghproxy", "GhProxy", p("https://ghproxy.com"), "镜像"),
            Mirror("ghproxymirror", "GhProxyMirror", p("https://mirror.ghproxy.com"), "镜像"),
            Mirror("workers", "Workers", p("https://github.abskoop.workers.dev"), "镜像"),
            Mirror("tbedu", "Tbedu", p("https://github.tbedu.top"), "镜像"),
            Mirror("yylx", "Yylx", p("https://git.yylx.win"), "镜像"),
            Mirror("xxooo", "Xxooo", p("https://gh.xxooo.cf"), "镜像"),
            Mirror("xx9527", "Xx9527", p("https://gh.xx9527.cn"), "镜像"),
        )

    /** 由用户 JSON 恢复镜像列表 */
    fun fromJson(json: String): List<Mirror> {
        if (json.isBlank()) return DEFAULT
        return runCatching {
            val arr = JSONObject(json).getJSONArray("list")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Mirror(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    prefix = o.getString("prefix"),
                    note = o.optString("note", ""),
                    builtin = o.optBoolean("builtin", false),
                )
            }
        }.getOrDefault(DEFAULT)
    }

    fun toJson(list: List<Mirror>): String {
        val arr = org.json.JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("prefix", m.prefix)
                    .put("note", m.note)
                    .put("builtin", m.builtin),
            )
        }
        return JSONObject().put("list", arr).toString()
    }
}
