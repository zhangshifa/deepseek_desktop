"""
web_client.py — 控制 WebView 里的 DeepSeek 网页
负责：注入辅助 JS（找输入框 / 填字 / 发送 / 取最新回复），
以及轮询最新助手回复，稳定后通过 replyReady 信号抛出（供 TTS 播报）。
"""
from PySide6.QtCore import QObject, QUrl, QTimer, Signal
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWebEngineCore import QWebEngineScript

# 注入到网页的辅助函数（全局 window.__ds*），在每次文档加载后可用
INJECT_JS = r"""
(function(){
  if (window.__dsInjected) return;
  window.__dsInjected = true;

  // 查找 DeepSeek 输入框：优先 textarea，其次 contenteditable / role=textbox
  window.__dsFindInput = function(){
    var el = document.querySelector('textarea');
    if (el) return el;
    el = document.querySelector('[contenteditable="true"]');
    if (el) return el;
    el = document.querySelector('[role="textbox"]');
    if (el) return el;
    return null;
  };

  // 设置输入框文本（兼容 React 受控组件：用原生 setter + 派发 input 事件）
  window.__dsSetInput = function(text){
    var el = window.__dsFindInput();
    if(!el) return false;
    if(el.tagName === 'TEXTAREA' || el.tagName === 'INPUT'){
      var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype
                                            : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, text);
      el.dispatchEvent(new Event('input', {bubbles:true}));
    } else {
      el.focus();
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
      el.dispatchEvent(new Event('input', {bubbles:true}));
    }
    return true;
  };

  // 模拟回车发送
  window.__dsSend = function(){
    var el = window.__dsFindInput();
    if(!el) return false;
    el.focus();
    ['keydown','keypress','keyup'].forEach(function(type){
      el.dispatchEvent(new KeyboardEvent(type, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
    });
    return true;
  };

  // 取最新助手回复文本：DeepSeek 助手正文在 .ds-markdown 内，取最后一个
  window.__dsGetLatestReply = function(){
    var nodes = document.querySelectorAll('.ds-markdown');
    if(nodes && nodes.length){
      var t = (nodes[nodes.length-1].innerText || nodes[nodes.length-1].textContent || '').trim();
      return t;
    }
    // 兜底：尝试常见聊天气泡类
    var alt = document.querySelectorAll('[class*="markdown"], [class*="message"]');
    if(alt && alt.length){
      var last = alt[alt.length-1];
      var txt = (last.innerText || last.textContent || '').trim();
      return txt;
    }
    return '';
  };
})();
"""


class WebClient(QObject):
    replyReady = Signal(str)   # 一条完整的新回复
    pageReady = Signal()

    def __init__(self, webview: QWebEngineView):
        super().__init__()
        self.view = webview
        self._last_emitted = None   # 已播报过的最后一条
        self._primed = False        # 首轮轮询仅用于"校准基线"，不播报
        self._candidate = ""
        self._stable = 0
        self._timer = QTimer()
        self._timer.setInterval(900)
        self._timer.timeout.connect(self._poll)

        page = self.view.page()
        # 注册注入脚本：每次文档就绪后自动执行
        script = QWebEngineScript()
        script.setName("ds_helpers")
        script.setSourceCode(INJECT_JS)
        script.setInjectionPoint(QWebEngineScript.DocumentReady)
        script.setRunsOnSubFrames(False)
        script.setWorldId(QWebEngineScript.MainWorld)
        page.profile().scripts().insert(script)

        self.view.loadFinished.connect(self._on_loaded)
        self.url = QUrl("https://chat.deepseek.com")

    def load(self):
        self.view.setUrl(self.url)

    def _on_loaded(self, ok: bool):
        if ok:
            self._primed = False
            self._last_emitted = None
            self._candidate = ""
            self._stable = 0
            # 进入页面后开始轮询回复
            if not self._timer.isActive():
                self._timer.start()
            self.pageReady.emit()

    def send_text(self, text: str):
        """把文本填入 DeepSeek 输入框并发送"""
        text = (text or "").strip()
        if not text:
            return
        safe = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        js = f"window.__dsSetInput('{safe}'); window.__dsSend();"
        self.view.page().runJavaScript(js)

    def _poll(self):
        self.view.page().runJavaScript("window.__dsGetLatestReply()", self._on_reply)

    def _on_reply(self, text):
        text = (text or "").strip()
        if not text:
            return
        # 首轮仅校准基线（不播报历史消息）
        if not self._primed:
            self._primed = True
            self._last_emitted = text
            return
        if text == self._last_emitted:
            return
        if text == self._candidate:
            self._stable += 1
        else:
            self._candidate = text
            self._stable = 0
            return
        # 连续 2 次相同 => 视为稳定完成，抛出新回复
        if self._stable >= 2:
            self._last_emitted = text
            self._stable = 0
            self.replyReady.emit(text)
