package com.example.deepseekvoice

import android.webkit.JavascriptInterface

/**
 * 网页 JS 调用原生层的桥接对象（通过 addJavascriptInterface 暴露为 window.AndroidBridge）。
 */
class WebAppBridge(private val onReply: (String) -> Unit) {

    @JavascriptInterface
    fun onReply(text: String) {
        onReply.invoke(text)
    }
}
