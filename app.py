"""
DeepSeek 本地桌面客户端
========================
独立桌面窗口程序：在原生窗口里直接运行 chat.deepseek.com，
因此「网页返回什么，程序就显示什么」，登录与网页一致（用户名+密码）。

本地增强能力：
  - 浮动工具条「打开分享对话」：一键跳到你给的分享链接。
  - 「本地工作目录」面板（双向）：
      * 选文件夹 → 本地读取目录内文本文件，作为上下文填入对话；
      * 把 DeepSeek 的回复 / 当前页，写回该目录（本地代理，服务器不碰你磁盘）。
  - 「导出到本地备份」：把当前页文字存到本地 saved_conversations/。

依赖：pip install pywebview
运行：python app.py
"""

import os
import datetime
import json
import webview

# ===== 可配置项 =====
SHARED_LINK = "https://chat.deepseek.com/a/chat/s/b2bd094e-6d56-4d44-a5a0-910c2aaa5d70"
START_URL = SHARED_LINK
WINDOW_TITLE = "DeepSeek 本地客户端"
BASE = os.path.dirname(os.path.abspath(__file__))
SAVE_DIR = os.path.join(BASE, "saved_conversations")
WORKSPACE_CFG = os.path.join(BASE, "workspace.json")
READ_MAX_CHARS = 60000  # 喂给模型前目录内容上限，超出截断

# 只读取这些文本类型；其余（图片/二进制）跳过，避免把垃圾塞进对话
TEXT_EXT = {
    ".txt", ".md", ".markdown", ".py", ".js", ".ts", ".tsx", ".jsx",
    ".html", ".htm", ".css", ".scss", ".json", ".csv", ".yaml", ".yml",
    ".xml", ".c", ".cpp", ".h", ".hpp", ".java", ".go", ".rs", ".sh",
    ".bat", ".ps1", ".ini", ".toml", ".cfg", ".sql", ".log", ".tex",
    ".rst", ".ipynb",
}


def _inject_js():
    """注入浮动工具条 + 本地工作目录面板的 JS 源码。"""
    return r"""
(function () {
  var SHARED = "__SHARED_LINK__";

  function findInput() {
    return document.querySelector("textarea") ||
           document.querySelector('div[contenteditable="true"]') || null;
  }

  function setDeepSeekInput(text) {
    var el = findInput();
    if (!el) return false;
    try {
      if (el.tagName === "TEXTAREA") {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
        setter.call(el, text);
      } else {
        el.focus();
        el.textContent = text;
      }
      el.dispatchEvent(new Event("input", { bubbles: true }));
      return true;
    } catch (e) { return false; }
  }

  function injectAll() {
    if (document.getElementById("ds_panel")) return;

    var css = [
      "position:fixed", "left:12px", "bottom:12px", "z-index:2147483647",
      "width:320px", "max-height:78vh", "overflow:auto",
      "background:rgba(24,24,27,0.97)", "color:#e4e4e7",
      "border:1px solid #3f3f46", "border-radius:12px",
      "font:13px/1.5 system-ui,sans-serif", "box-shadow:0 8px 30px rgba(0,0,0,0.5)",
      "padding:10px", "display:flex", "flex-direction:column", "gap:6px"
    ].join(";");

    var panel = document.createElement("div");
    panel.id = "ds_panel";
    panel.style.cssText = css;

    panel.innerHTML = [
      '<div style="display:flex;justify-content:space-between;align-items:center;cursor:move">',
      '  <b style="font-size:13px">📁 本地工作目录</b>',
      '  <span id="ds_min" style="cursor:pointer;color:#a1a1aa">－</span>',
      '</div>',
      '<div style="display:flex;gap:6px;align-items:center">',
      '  <button id="ds_pick" style="flex:0 0 auto">选择文件夹</button>',
      '  <span id="ds_path" style="color:#a1a1aa;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">未选择</span>',
      '</div>',
      '<div style="display:flex;gap:6px">',
      '  <button id="ds_read">读取目录→填入对话</button>',
      '  <button id="ds_copy">复制目录内容</button>',
      '</div>',
      '<textarea id="ds_ctx" placeholder="目录内容会显示在这里，可手动编辑后再填入对话" style="height:120px"></textarea>',
      '<div style="color:#a1a1aa;font-size:12px">💬 写回目录</div>',
      '<div style="display:flex;gap:6px">',
      '  <input id="ds_fname" placeholder="文件名，如 reply.md" style="flex:1;min-width:0">',
      '  <button id="ds_save" style="flex:0 0 auto">保存到目录</button>',
      '</div>',
      '<textarea id="ds_out" placeholder="粘贴 DeepSeek 回复，或点“导出当前页”"></textarea>',
      '<div style="display:flex;gap:6px;flex-wrap:wrap">',
      '  <button id="ds_export">导出当前页→目录</button>',
      '  <button id="ds_backup">导出到本地备份</button>',
      '  <button id="ds_open">打开分享对话</button>',
      '</div>'
    ].join("");

    // 按钮样式
    var btns = panel.querySelectorAll("button");
    btns.forEach(function (b) {
      b.style.cssText = "cursor:pointer;border:0;border-radius:6px;padding:6px 8px;background:#3f3f46;color:#fff;font:12px system-ui";
    });
    var inp = panel.querySelectorAll("input,textarea");
    inp.forEach(function (e) {
      e.style.cssText = (e.style.cssText || "") + ";background:#18181b;color:#e4e4e7;border:1px solid #3f3f46;border-radius:6px;padding:6px;font:12px system-ui;box-sizing:border-box";
    });

    if (document.body) document.body.appendChild(panel);

    var api = (window.pywebview && window.pywebview.api) ? window.pywebview.api : null;
    function guard() { if (!api) alert("本地接口不可用"); return !!api; }

    document.getElementById("ds_pick").onclick = function () {
      if (!guard()) return;
      api.pick_folder().then(function (p) {
        if (!p) return;
        document.getElementById("ds_path").textContent = p;
        api.read_files(p).then(function (txt) {
          document.getElementById("ds_ctx").value = txt || "";
        });
      });
    };

    document.getElementById("ds_read").onclick = function () {
      if (!guard()) return;
      api.read_files().then(function (txt) {
        var box = document.getElementById("ds_ctx");
        box.value = txt || "";
        var ok = setDeepSeekInput(box.value);
        if (ok) alert("已填入对话输入框，可补充问题后发送");
        else {
          if (navigator.clipboard) navigator.clipboard.writeText(box.value);
          alert("未找到输入框，已复制到剪贴板，请手动粘贴到对话框");
        }
      });
    };

    document.getElementById("ds_copy").onclick = function () {
      var t = document.getElementById("ds_ctx").value || "";
      if (navigator.clipboard) navigator.clipboard.writeText(t);
      alert("目录内容已复制到剪贴板");
    };

    document.getElementById("ds_save").onclick = function () {
      if (!guard()) return;
      var name = document.getElementById("ds_fname").value ||
                 ("reply_" + new Date().toISOString().slice(0,19).replace(/[:T]/g,"-") + ".md");
      var content = document.getElementById("ds_out").value || "";
      if (!content) { alert("回复内容为空"); return; }
      api.save_to_workspace(name, content).then(function (pp) {
        alert("已写入：" + pp);
      }).catch(function (e) { alert("保存失败：" + e); });
    };

    document.getElementById("ds_export").onclick = function () {
      if (!guard()) return;
      var text = (document.body && document.body.innerText) ? document.body.innerText : "";
      var name = "page_" + new Date().toISOString().slice(0,19).replace(/[:T]/g,"-") + ".md";
      api.save_to_workspace(name, text).then(function (pp) {
        alert("已导出到工作目录：" + pp);
      }).catch(function (e) { alert("导出失败：" + e); });
    };

    document.getElementById("ds_backup").onclick = function () {
      if (!guard()) return;
      var text = (document.body && document.body.innerText) ? document.body.innerText : "";
      api.save_markdown(text).then(function (pp) {
        alert("已备份到本地：" + pp);
      }).catch(function (e) { alert("备份失败：" + e); });
    };

    document.getElementById("ds_open").onclick = function () {
      window.location.href = SHARED;
    };

    // 收起 / 展开
    var minimized = false;
    document.getElementById("ds_min").onclick = function () {
      minimized = !minimized;
      var bodyEls = Array.prototype.slice.call(panel.children).filter(function (c) { return c.id !== "ds_min" && c.tagName !== "DIV" || (c.children && c.children.length && c.textContent.indexOf("📁") === -1); });
      // 简单处理：隐藏除标题和收起按钮外的所有子节点
      Array.prototype.slice.call(panel.children).forEach(function (c) {
        if (c.querySelector && c.querySelector("#ds_min")) return;
        if (c.textContent && c.textContent.indexOf("📁") !== -1) return;
        c.style.display = minimized ? "none" : (c.tagName === "BUTTON" || c.tagName === "TEXTAREA" || c.tagName === "INPUT" || c.tagName === "DIV" ? "" : "");
      });
      this.textContent = minimized ? "＋" : "－";
    };

    // 拖动
    var drag = panel.querySelector("div");
    var sx, sy, ox, oy, dragging = false;
    drag.addEventListener("mousedown", function (e) {
      dragging = true; sx = e.clientX; sy = e.clientY;
      var r = panel.getBoundingClientRect();
      ox = r.left; oy = r.top;
      panel.style.left = ox + "px"; panel.style.bottom = "auto"; panel.style.top = oy + "px";
      e.preventDefault();
    });
    document.addEventListener("mousemove", function (e) {
      if (!dragging) return;
      panel.style.left = (ox + e.clientX - sx) + "px";
      panel.style.top = (oy + e.clientY - sy) + "px";
    });
    document.addEventListener("mouseup", function () { dragging = false; });
  }

  injectAll();
  if (window.MutationObserver) {
    var mo = new MutationObserver(function () { injectAll(); });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  }
  window.addEventListener("load", injectAll);
})();
""".replace("__SHARED_LINK__", SHARED_LINK)


class Api:
    """暴露给网页 JS 调用的本地接口。"""

    def __init__(self):
        self.workspace_dir = self._load_workspace_cfg()

    # ---- 工作目录：选择 / 读取 / 写回 ----
    def pick_folder(self):
        """弹出系统文件夹选择框，返回选中路径（取消返回空串）。"""
        try:
            w = webview.windows[0]
            res = w.create_file_dialog(webview.FOLDER_DIALOG)
            if res:
                path = res[0]
                self.workspace_dir = path
                self._save_workspace_cfg(path)
                return path
        except Exception as e:
            return "ERROR:" + str(e)
        return ""

    def get_workspace(self):
        return self.workspace_dir or ""

    def list_files(self, dir_path=""):
        """列出工作目录内文本文件（含相对路径/大小）。"""
        base = dir_path or self.workspace_dir or ""
        if not base or not os.path.isdir(base):
            return []
        out = []
        for root, dirs, files in os.walk(base):
            depth = root[len(base):].count(os.sep)
            if depth > 3:
                dirs[:] = []
                continue
            for fn in files:
                if os.path.splitext(fn)[1].lower() in TEXT_EXT:
                    full = os.path.join(root, fn)
                    try:
                        sz = os.path.getsize(full)
                    except OSError:
                        sz = 0
                    out.append({"path": os.path.relpath(full, base), "size": sz})
        return out

    def read_files(self, dir_path="", max_chars=READ_MAX_CHARS):
        """读取工作目录内文本文件，拼接为带标记的上下文字符串。"""
        base = dir_path or self.workspace_dir or ""
        if not base or not os.path.isdir(base):
            return ""
        parts = []
        total = 0
        for root, dirs, files in os.walk(base):
            depth = root[len(base):].count(os.sep)
            if depth > 3:
                dirs[:] = []
                continue
            for fn in files:
                ext = os.path.splitext(fn)[1].lower()
                if ext not in TEXT_EXT:
                    continue
                full = os.path.join(root, fn)
                try:
                    with open(full, "r", encoding="utf-8", errors="ignore") as f:
                        data = f.read()
                except Exception:
                    continue
                rel = os.path.relpath(full, base)
                chunk = "\n\n===== 文件: %s =====\n%s" % (rel, data)
                if total + len(chunk) > max_chars:
                    parts.append("\n\n[已截断：目录内容超过 %d 字符上限]" % max_chars)
                    return "".join(parts)
                parts.append(chunk)
                total += len(chunk)
        return "".join(parts)

    def save_to_workspace(self, filename, content, dir_path=""):
        """把文本写回工作目录，返回保存路径。"""
        base = dir_path or self.workspace_dir or ""
        if not base:
            return "ERROR:未选择工作目录"
        os.makedirs(base, exist_ok=True)
        fname = os.path.basename(filename.strip()) or (
            "reply_%s.md" % datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))
        path = os.path.join(base, fname)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content or "")
        return path

    # ---- 本地备份（不依赖工作目录） ----
    def save_markdown(self, text):
        os.makedirs(SAVE_DIR, exist_ok=True)
        ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        fname = "deepseek_%s.md" % ts
        path = os.path.join(SAVE_DIR, fname)
        header = (
            "# DeepSeek 对话导出\n\n- 导出时间：%s\n\n---\n\n" % ts
        )
        with open(path, "w", encoding="utf-8") as f:
            f.write(header)
            f.write(text or "")
        return path

    # ---- 配置持久化 ----
    def _save_workspace_cfg(self, path):
        try:
            with open(WORKSPACE_CFG, "w", encoding="utf-8") as f:
                json.dump({"workspace": path}, f)
        except Exception:
            pass

    def _load_workspace_cfg(self):
        try:
            if os.path.exists(WORKSPACE_CFG):
                with open(WORKSPACE_CFG, "r", encoding="utf-8") as f:
                    return json.load(f).get("workspace", "")
        except Exception:
            pass
        return ""


def on_loaded(window):
    try:
        window.evaluate_js(_inject_js())
    except Exception as e:
        print("注入面板失败：", e)


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
