package com.example.deepseekvoice

/**
 * 注入到 DeepSeek 网页中的 JS 脚本（增强层）。
 *
 * 作用：
 * 1. window.VoiceFill(text) —— 把语音识别结果填进网页输入框（textarea 或 contenteditable）。
 * 2. MutationObserver 监听对话区，提取最新回复文本，回调 AndroidBridge.onReply(text)。
 *
 * 注意：DeepSeek 网页是第三方页面，类名/结构可能随版本变化，这里的类名启发式匹配
 * （message / bubble / reply / answer / assistant）是“尽力而为”的增强，不是官方接口。
 */
object VoiceInjector {

    val script: String
        get() = """
        (function () {
          if (window.__dsvInstalled) return;
          window.__dsvInstalled = true;

          // 把文本填入网页输入框
          window.VoiceFill = function (text) {
            try {
              var el = document.querySelector('textarea');
              if (!el) { el = document.querySelector('[contenteditable="true"]'); }
              if (!el) { return false; }
              if (el.tagName === 'TEXTAREA') {
                var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                setter.call(el, (el.value || '') + text);
              } else {
                el.focus();
                document.execCommand('insertText', false, text);
              }
              el.dispatchEvent(new Event('input', { bubbles: true }));
              return true;
            } catch (e) { return false; }
          };

          // 监听对话区新增回复
          function pickReplyNodes() {
            return document.querySelectorAll(
              '[class*="message"], [class*="bubble"], [class*="reply"], [class*="answer"], [class*="assistant"]'
            );
          }

          var lastText = '';
          var obs = new MutationObserver(function () {
            try {
              var nodes = pickReplyNodes();
              if (nodes && nodes.length) {
                var el = nodes[nodes.length - 1];
                var txt = (el.innerText || el.textContent || '').trim();
                if (txt && txt !== lastText) {
                  lastText = txt;
                  if (window.AndroidBridge && window.AndroidBridge.onReply) {
                    window.AndroidBridge.onReply(txt);
                  }
                }
              }
            } catch (e) { /* ignore */ }
          });
          obs.observe(document.body, { childList: true, subtree: true });
        })();
        """.trimIndent()
}
