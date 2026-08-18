/* DeepSeek 语音层（Web 技术）：浮动工具条 + 语音输入/播报开关。
   由原生在 chat.deepseek.com 页面加载后注入。 */
(function () {
  if (window.__dsInjected) return;
  window.__dsInjected = true;

  var css = '' +
    '#dsBar{position:fixed;top:8px;right:8px;z-index:2147483647;display:flex;align-items:center;gap:8px;' +
    'background:rgba(20,20,30,.92);color:#fff;padding:8px 10px;border-radius:12px;' +
    'font:13px/1.2 system-ui,-apple-system,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.45);}' +
    '#dsBar button{background:#3b82f6;color:#fff;border:0;border-radius:8px;width:36px;height:36px;' +
    'font-size:18px;cursor:pointer;}' +
    '#dsBar button.on{background:#ef4444;animation:dsPulse 1s infinite;}' +
    '@keyframes dsPulse{0%{opacity:1}50%{opacity:.5}100%{opacity:1}}' +
    '#dsBar label{display:flex;align-items:center;gap:4px;cursor:pointer;user-select:none;}' +
    '#dsStatus{max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;opacity:.85;}';

  var style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  var bar = document.createElement('div');
  bar.id = 'dsBar';
  bar.innerHTML =
    '<button id="dsMic" title="语音输入">🎤</button>' +
    '<label>输入<input type="checkbox" id="dsInChk" checked></label>' +
    '<label>播报<input type="checkbox" id="dsOutChk" checked></label>' +
    '<span id="dsStatus"></span>';
  document.body.appendChild(bar);

  var mic = document.getElementById('dsMic');
  var inChk = document.getElementById('dsInChk');
  var outChk = document.getElementById('dsOutChk');
  var status = document.getElementById('dsStatus');
  var inOn = true, outOn = true;
  inChk.onchange = function () { inOn = inChk.checked; };
  outChk.onchange = function () { outOn = outChk.checked; };

  mic.onclick = function () {
    if (typeof VoiceBridge === 'undefined') { status.textContent = '语音不可用'; return; }
    if (mic.classList.contains('on')) { VoiceBridge.stopRecognition(); }
    else { VoiceBridge.startRecognition(); }
  };

  // 原生回传：{type:'start'|'partial'|'error', text:''}
  window.__onSpeech = function (obj) {
    if (!obj) return;
    if (obj.type === 'start') { mic.classList.add('on'); status.textContent = '聆听中…'; }
    else if (obj.type === 'partial') { status.textContent = obj.text || '聆听中…'; if (obj.text) fillInput(obj.text); }
    else if (obj.type === 'error') { mic.classList.remove('on'); status.textContent = '语音错误'; }
  };

  function fillInput(text) {
    if (!inOn) return;
    var box = document.querySelector('textarea, input[type=text], [contenteditable="true"], [role="textbox"]');
    if (!box) return;
    try {
      if (box.tagName === 'TEXTAREA' || box.tagName === 'INPUT') {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
        setter.call(box, text);
        box.dispatchEvent(new Event('input', { bubbles: true }));
      } else {
        box.innerText = text;
        box.dispatchEvent(new Event('input', { bubbles: true }));
      }
      status.textContent = '已填入';
    } catch (e) { }
  }

  // 捕获 AI 回复并播报（防抖 1.2s，只读最终完整文本）
  var lastText = '';
  var timer = null;
  function pickReply() {
    if (!outOn) return;
    var nodes = document.querySelectorAll(
      '[class*="message"]:not([class*="user"]), [class*="reply"], [class*="assistant"], [class*="bubble"]:not([class*="user"])'
    );
    if (!nodes || !nodes.length) return;
    var node = nodes[nodes.length - 1];
    var txt = (node.innerText || '').trim();
    if (txt && txt !== lastText) {
      lastText = txt;
      if (typeof VoiceBridge !== 'undefined') VoiceBridge.speak(txt);
    }
  }
  var obs = new MutationObserver(function () {
    if (!outOn) return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(pickReply, 1200);
  });
  obs.observe(document.body, { childList: true, subtree: true });
})();
