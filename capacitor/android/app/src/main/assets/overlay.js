/* DeepSeek 语音层（Web 技术）：浮动工具条 + 语音输入/播报开关 + 免手模式（关键字唤醒）。
   由原生在 chat.deepseek.com 页面加载后注入。
   免手模式：说唤醒词（默认"小深"，可点 🛎 修改）→ 输入提示音 → 内容自动填入并发送 → AI 思考 → 输出提示音 → 播报。 */
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
    '#dsStatus{max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;opacity:.85;}' +
    '#dsBt{opacity:.35;font-size:15px;cursor:default;transition:opacity .3s,filter .3s;}' +
    '#dsBt.on{opacity:1;filter:drop-shadow(0 0 4px #3b82f6);}' +
    '#dsWake{background:#6b7280;}' +
    '#dsWake.on{background:#10b981;animation:none;}';

  var style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  var bar = document.createElement('div');
  bar.id = 'dsBar';
  bar.innerHTML =
    '<button id="dsMic" title="语音输入（点一下说话）">🎤</button>' +
    '<button id="dsWake" title="免手模式：说唤醒词才输入，回复自动播报">🛎</button>' +
    '<button id="dsImg" title="眼镜图片：用最新照片（智能眼镜优先）作为输入">📷</button>' +
    '<span id="dsBt" title="蓝牙耳机：连上后语音输入走耳机麦克风、播报走耳机">🎧</span>' +
    '<label>输入<input type="checkbox" id="dsInChk" checked></label>' +
    '<label>播报<input type="checkbox" id="dsOutChk" checked></label>' +
    '<span id="dsStatus"></span>';
  document.body.appendChild(bar);

  var mic = document.getElementById('dsMic');
  var wakeBtn = document.getElementById('dsWake');
  var imgBtn = document.getElementById('dsImg');
  var btIcon = document.getElementById('dsBt');
  var inChk = document.getElementById('dsInChk');
  var outChk = document.getElementById('dsOutChk');
  var status = document.getElementById('dsStatus');
  var inOn = true, outOn = true;
  var wakeOn = false;
  var wakeWord = '小深';
  inChk.onchange = function () { inOn = inChk.checked; };
  outChk.onchange = function () { outOn = outChk.checked; };

  // 刷新蓝牙耳机连接状态（连接后输入走耳机麦克风、播报走耳机）
  function refreshBt() {
    try {
      var on = typeof VoiceBridge !== 'undefined' && VoiceBridge.getBluetoothState && VoiceBridge.getBluetoothState();
      btIcon.classList.toggle('on', !!on);
      btIcon.title = on ? '蓝牙耳机已连接：输入/播报走耳机' : '蓝牙耳机未连接：使用手机麦克风/扬声器';
    } catch (e) { }
  }
  refreshBt();
  setInterval(refreshBt, 3000);

  mic.onclick = function () {
    refreshBt();
    if (typeof VoiceBridge === 'undefined') { status.textContent = '语音不可用'; return; }
    if (mic.classList.contains('on')) { VoiceBridge.stopRecognition(); mic.classList.remove('on'); }
    else { VoiceBridge.startRecognition(); }
  };

  // 免手模式开关：开启后禁用手动 mic，避免两套监听打架
  wakeBtn.onclick = function () {
    if (typeof VoiceBridge === 'undefined') { status.textContent = '语音不可用'; return; }
    if (!wakeOn) {
      var kw = prompt('输入唤醒词（说它才激活输入）：', wakeWord);
      if (!kw || !kw.trim()) return;
      wakeWord = kw.trim();
      VoiceBridge.startWake(wakeWord);
      wakeOn = true;
      wakeBtn.classList.add('on');
      mic.disabled = true; mic.style.opacity = .4;
      status.textContent = '免手待命：说「' + wakeWord + '」（间隔监听，无唤醒词不入输入）';
    } else {
      VoiceBridge.stopWake();
      wakeOn = false;
      wakeBtn.classList.remove('on');
      mic.disabled = false; mic.style.opacity = 1;
      status.textContent = '';
    }
  };

  // 智能眼镜图片输入：触发网页图片上传，原生返回相册最新照片（眼镜相册优先）
  imgBtn.onclick = function () {
    try {
      var input = document.createElement('input');
      input.type = 'file';
      input.accept = 'image/*';
      input.style.display = 'none';
      document.body.appendChild(input);
      input.click();
      status.textContent = '正在取最新照片…';
      setTimeout(function () { input.remove(); }, 30000);
    } catch (e) { status.textContent = '图片上传不可用'; }
  };

  // 原生回传：{type:'start'|'partial'|'error'|'wake', text:''}
  window.__onSpeech = function (obj) {
    if (!obj) return;
    if (obj.type === 'start') { mic.classList.add('on'); status.textContent = '聆听中…'; }
    else if (obj.type === 'partial') { status.textContent = obj.text || '聆听中…'; if (obj.text) fillInput(obj.text); }
    else if (obj.type === 'error') { mic.classList.remove('on'); status.textContent = '语音错误'; }
    else if (obj.type === 'wake') {
      // 关键字命中：内容作为输入，自动发送
      status.textContent = '已唤醒，发送…';
      if (obj.text) { fillInput(obj.text, true); sendMsg(); }
      else { status.textContent = '唤醒成功，请说内容'; }
    }
  };

  function fillInput(text, silent) {
    if (!inOn && !silent) return;
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
      if (!silent) status.textContent = '已填入';
    } catch (e) { }
  }

  // 模拟回车发送（优先），不行再找发送按钮点击
  function sendMsg() {
    var box = document.querySelector('textarea, [contenteditable="true"], [role="textbox"]');
    if (box) {
      try {
        var ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true });
        box.dispatchEvent(ev);
        return;
      } catch (e) { }
    }
    try {
      var btn = document.querySelector('button[class*="send"], [class*="send"] button, button[aria-label*="发送"]');
      if (btn) btn.click();
    } catch (e) { }
  }

  // 捕获 AI 回复并播报（防抖 1.2s，只读最终完整文本；播报前先播输出提示音）
  var lastText = '';
  var timer = null;
  var lastSpoken = '';
  function pickReply() {
    if (!outOn) return;
    var nodes = document.querySelectorAll(
      '[class*="message"]:not([class*="user"]), [class*="reply"], [class*="assistant"], [class*="bubble"]:not([class*="user"])'
    );
    if (!nodes || !nodes.length) return;
    var node = nodes[nodes.length - 1];
    var txt = (node.innerText || '').trim();
    // 流式追加时 txt 会以 lastSpoken 为前缀，跳过直到文本稳定（完整回复）
    if (txt && txt !== lastText && txt !== lastSpoken &&
        (!lastSpoken || txt.indexOf(lastSpoken) !== 0)) {
      lastText = txt;
      if (typeof VoiceBridge !== 'undefined') {
        VoiceBridge.playTone('out');                       // 输出提示音（思考完成）
        setTimeout(function () { VoiceBridge.speak(txt); }, 450);
        lastSpoken = txt;
      }
    }
  }
  var obs = new MutationObserver(function () {
    if (!outOn) return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(pickReply, 1200);
  });
  obs.observe(document.body, { childList: true, subtree: true });
})();
