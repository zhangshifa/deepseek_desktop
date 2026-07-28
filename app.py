"""
DeepSeek 本地桌面客户端
========================
一个独立的桌面窗口程序：在原生窗口里直接运行 chat.deepseek.com，
因此「网页返回什么，程序就显示什么」，登录方式与网页完全一致
（用户名 + 密码），并且支持网页能访问的全部 DeepSeek 功能。

额外能力（本地增强）：
  - 浮动工具条上的「打开分享对话」：一键跳转到你给的那个分享链接。
  - 浮动工具条上的「保存到本地」：把当前页面（对话）文字导出为本地 Markdown 文件。

依赖：pip install pywebview
运行：python app.py
"""

import os
import datetime
import webview

# ===== 可配置项 =====
SHARED_LINK = "https://chat.deepseek.com/a/chat/s/b2bd094e-6d56-4d44-a5a0-910c2aaa5d70"
START_URL = SHARED_LINK  # 程序启动即进入该分享对话（未登录会先跳到登录页）
WINDOW_TITLE = "DeepSeek 本地客户端"
SAVE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saved_conversations")


def _inject_js():
    """注入浮动工具条的 JS 源码（SHARED_LINK 会被替换进按钮里）。"""
    return r"""
(function () {
  var SHARED = "__SHARED_LINK__";

  function injectBar() {
    if (document.getElementById("ds_local_bar")) return;

    var bar = document.createElement("div");
    bar.id = "ds_local_bar";
    bar.style.cssText = [
      "position:fixed",
      "top:10px",
      "right:10px",
      "z-index:2147483647",
      "background:rgba(31,31,31,0.95)",
      "color:#fff",
      "padding:8px 10px",
      "border-radius:10px",
      "font:13px/1.4 system-ui,sans-serif",
      "box-shadow:0 4px 14px rgba(0,0,0,0.45)",
      "display:flex",
      "gap:8px",
      "align-items:center"
    ].join(";");

    var btnOpen = document.createElement("button");
    btnOpen.textContent = "打开分享对话";
    btnOpen.style.cssText = "cursor:pointer;border:0;border-radius:6px;padding:6px 10px;background:#4f7cff;color:#fff;font:13px system-ui";
    btnOpen.onclick = function () { window.location.href = SHARED; };

    var btnSave = document.createElement("button");
    btnSave.textContent = "保存到本地";
    btnSave.style.cssText = "cursor:pointer;border:0;border-radius:6px;padding:6px 10px;background:#2a2a2a;color:#fff;font:13px system-ui";
    btnSave.onclick = function () {
      var text = (document.body && document.body.innerText) ? document.body.innerText : "";
      if (!text) { alert("没有可保存的内容"); return; }
      if (window.pywebview && window.pywebview.api) {
        window.pywebview.api.save_markdown(text).then(function (p) {
          alert("已保存到本地：\n" + p);
        }).catch(function (e) {
          alert("保存失败：" + e);
        });
      } else {
        alert("本地接口不可用（pywebview.api 未注入）");
      }
    };

    bar.appendChild(btnOpen);
    bar.appendChild(btnSave);

    if (document.body) {
      document.body.appendChild(bar);
    }
  }

  // 初次注入
  injectBar();

  // 用 MutationObserver 保持工具条存在（DeepSeek 是单页应用，会重渲染 DOM）
  if (window.MutationObserver) {
    var mo = new MutationObserver(function () { injectBar(); });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  }

  // 页面整体加载完再补一次
  window.addEventListener("load", injectBar);
})();
""".replace("__SHARED_LINK__", SHARED_LINK)


class Api:
    """暴露给网页 JS 调用的本地接口。"""

    def save_markdown(self, text):
        """把对话文字保存为本地 Markdown 文件，返回保存路径。"""
        os.makedirs(SAVE_DIR, exist_ok=True)
        ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        fname = "deepseek_%s.md" % ts
        path = os.path.join(SAVE_DIR, fname)
        header = (
            "# DeepSeek 对话导出\n\n"
            "- 来源链接：%s\n"
            "- 导出时间：%s\n\n"
            "---\n\n"
        ) % (SHARED_LINK, ts)
        with open(path, "w", encoding="utf-8") as f:
            f.write(header)
            f.write(text or "")
        return path


def on_loaded(window):
    """页面加载完成后注入浮动工具条。"""
    try:
        window.evaluate_js(_inject_js())
    except Exception as e:  # 注入失败不应崩溃整个程序
        print("注入工具条失败：", e)


def main():
    api = Api()
    window = webview.create_window(
        title=WINDOW_TITLE,
        url=START_URL,
        js_api=api,
        width=1280,
        height=820,
    )
    window.events.loaded += lambda: on_loaded(window)
    webview.start()


if __name__ == "__main__":
    main()
