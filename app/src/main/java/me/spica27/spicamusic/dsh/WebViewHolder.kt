package me.spica27.spicamusic.dsh

import android.webkit.WebView

/**
 * 持有对话页的 WebView 实例，供全局发送通道（[DshMessenger]）注入消息。
 * 页面创建时注册、销毁时清空，避免泄漏。
 */
object WebViewHolder {
    @Volatile
    var webView: WebView? = null

    fun register(view: WebView) {
        webView = view
    }

    fun unregister(view: WebView) {
        if (webView === view) webView = null
    }
}
