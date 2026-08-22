package me.spica27.spicamusic.dsh

import timber.log.Timber

/**
 * 发送通道：把底部输入框的文字送进 dsh 当前会话。
 *
 * 第一版采用轻量方案：通过对话页 WebView 注入 JS，找到 dsh Web UI 的
 * 输入框（contenteditable / textarea / input），填入文本并触发回车。
 * 后续可升级为直接对接 dsh 前后端协议（WebSocket/HTTP）。
 */
object DshMessenger {
    private const val JS_INJECT =
        """
        (function () {
          try {
            var el = document.querySelector('[contenteditable="true"]')
              || document.querySelector('textarea')
              || document.querySelector('input[type="text"]')
              || document.querySelector('input');
            if (!el) return false;
            var text = %TEXT%;
            if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
              el.value = text;
            } else {
              el.textContent = text;
            }
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            var key = new KeyboardEvent('keydown', {
              key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
            });
            el.dispatchEvent(key);
            var keyup = new KeyboardEvent('keyup', {
              key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
            });
            el.dispatchEvent(keyup);
            return true;
          } catch (e) {
            return false;
          }
        })();
        """

    /**
     * 发送文字到 dsh 当前会话。
     * @return true 表示已注入（不代表 dsh 一定收到）
     */
    fun sendToDsh(text: String): Boolean {
        val webView =
            WebViewHolder.webView ?: run {
                Timber.w("WebView 未就绪，无法发送到 dsh 会话")
                return false
            }
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        val script = JS_INJECT.replace("%TEXT%", "'$escaped'")
        webView.post {
            runCatching {
                webView.evaluateJavascript(script, null)
            }.onFailure {
                Timber.w(it, "注入 dsh 输入框失败")
            }
        }
        return true
    }
}
