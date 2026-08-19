/* DeepSeek 语音层（Web 技术）：浮动工具条 + 语音输入/播报开关 + 免手模式（关键字唤醒）+ 在线更新 + 眼镜图片。
   由原生在 chat.deepseek.com 页面加载后注入。
   交互：
   🎤 点一下说话，说完自动填入并发送（final 事件）；
   🛎 免手模式：说唤醒词（默认"小深"）→ 内容自动填入并发送 → 回复播报；
   📷 智能眼镜最新照片作为图片输入；🔄 在线检查更新；🎧 蓝牙耳机状态；
   播报模式（完整/简短/结论）可切换并记忆，避免回复太长结论不明确。 */
(function () {
  if (window.__dsInjected) return;
  window.__dsInjected = true;

  var css = '' +
    /* ---------- 工具条容器：flex 换行，窄屏不划屏 ---------- */
    '#dsBar{position:fixed;top:8px;right:8px;z-index:2147483647;display:flex;flex-wrap:wrap;' +
    'align-items:center;gap:6px 8px;max-width:calc(100vw - 16px);box-sizing:border-box;' +
    'background:rgba(17,18,26,.94);color:#fff;padding:8px 10px;border-radius:14px;' +
    'font:13px/1.2 system-ui,-apple-system,sans-serif;box-shadow:0 6px 22px rgba(0,0,0,.5);' +
    'backdrop-filter:blur(6px);border:1px solid rgba(255,255,255,.08);}' +
    /* ---------- 图标按钮 ---------- */
    '#dsBar button{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;' +
    'background:linear-gradient(145deg,#3b82f6,#2563eb);color:#fff;border:0;border-radius:10px;' +
    'font-size:17px;cursor:pointer;transition:transform .12s,box-shadow .12s,filter .12s;' +
    'box-shadow:0 2px 6px rgba(37,99,235,.35);}' +
    '#dsBar button:hover{filter:brightness(1.12);transform:translateY(-1px);}' +
    '#dsBar button:active{transform:scale(.9);}' +
    '#dsBar button.on{background:linear-gradient(145deg,#ef4444,#dc2626);box-shadow:0 2px 8px rgba(239,68,68,.5);animation:dsPulse 1.1s infinite;}' +
    '#dsBar button:disabled{opacity:.35;cursor:not-allowed;filter:grayscale(.6);}' +
    '@keyframes dsPulse{0%{opacity:1}50%{opacity:.55}100%{opacity:1}}' +
    '#dsWake{background:linear-gradient(145deg,#6b7280,#4b5563);box-shadow:0 2px 6px rgba(75,85,99,.35);}' +
    '#dsWake.on{background:linear-gradient(145deg,#10b981,#059669);box-shadow:0 2px 8px rgba(16,185,129,.5);animation:none;}' +
    '#dsBt{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;' +
    'border-radius:10px;font-size:15px;opacity:.38;cursor:default;background:rgba(255,255,255,.06);' +
    'transition:opacity .3s,filter .3s,box-shadow .3s;}' +
    '#dsBt.on{opacity:1;box-shadow:0 0 10px rgba(59,130,246,.8);filter:drop-shadow(0 0 4px #3b82f6);}' +
    /* ---------- 设置行（pill 开关 + 播报模式） ---------- */
    '#dsSet{display:flex;align-items:center;gap:10px;width:100%;flex-wrap:wrap;}' +
    '.dsSw{display:inline-flex;align-items:center;gap:6px;cursor:pointer;user-select:none;color:#dbeafe;' +
    'background:rgba(255,255,255,.07);padding:3px 8px;border-radius:9px;}' +
    '.dsSw input{display:none;}' +
    '.dsSw .dsTrack{width:30px;height:17px;border-radius:9px;background:#52525b;position:relative;transition:background .2s;}' +
    '.dsSw .dsThumb{position:absolute;top:2px;left:2px;width:13px;height:13px;border-radius:50%;' +
    'background:#fff;transition:left .18s ease;box-shadow:0 1px 2px rgba(0,0,0,.4);}' +
    '.dsSw input:checked + .dsTrack{background:#22c55e;}' +
    '.dsSw input:checked + .dsTrack .dsThumb{left:15px;}' +
    '#dsMode{background:#1f2937;color:#e5e7eb;border:1px solid rgba(255,255,255,.14);border-radius:8px;' +
    'padding:3px 6px;font-size:12px;cursor:pointer;outline:none;}' +
    '#dsKw{background:#f59e0b;color:#1f2937;font-weight:700;padding:3px 8px;border-radius:8px;' +
    'font-size:12px;cursor:pointer;user-select:none;box-shadow:0 1px 4px rgba(245,158,11,.4);}' +
    '#dsKw:active{transform:scale(.92);}' +
    '#dsStatus{max-width:130px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;opacity:.8;' +
    'font-size:12px;color:#93c5fd;margin-left:auto;}';

  var style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  var bar = document.createElement('div');
  bar.id = 'dsBar';
  bar.innerHTML =
    '<button id="dsMic" title="语音输入（点一下说话，说完自动发送）">🎤</button>' +
    '<button id="dsWake" title="免手模式：说唤醒词才输入，回复自动播报">🛎</button>' +
    '<button id="dsImg" title="眼镜图片：用最新照片（智能眼镜优先）作为输入">📷</button>' +
    '<button id="dsUpd" title="检查更新：在线检测新版本并升级">🔄</button>' +
    '<span id="dsBt" title="蓝牙耳机：连上后语音输入走耳机麦克风、播报走耳机">🎧</span>' +
    '<div id="dsSet">' +
    '<label class="dsSw">输入<input type="checkbox" id="dsInChk" checked><span class="dsTrack"><span class="dsThumb"></span></span></label>' +
    '<label class="dsSw">播报<input type="checkbox" id="dsOutChk" checked><span class="dsTrack"><span class="dsThumb"></span></span></label>' +
    '<label class="dsSw" title="播报长度：结论=只播前60字，简短=前150字，完整=全文">播报<select id="dsMode">' +
    '<option value="brief">简短</option><option value="key">结论</option><option value="full">完整</option></select></label>' +
    '<label class="dsSw" title="开启后必须说唤醒词才作为输入，防周围语音误输入">唤醒词<input type="checkbox" id="dsGuard"><span class="dsTrack"><span class="dsThumb"></span></span></label>' +
    '<span id="dsKw" title="点我修改唤醒词">小深</span>' +
    '<span id="dsStatus"></span>' +
    '</div>';
  document.body.appendChild(bar);

  var mic = document.getElementById('dsMic');
  var wakeBtn = document.getElementById('dsWake');
  var imgBtn = document.getElementById('dsImg');
  var updBtn = document.getElementById('dsUpd');
  var btIcon = document.getElementById('dsBt');
  var inChk = document.getElementById('dsInChk');
  var outChk = document.getElementById('dsOutChk');
  var modeSel = document.getElementById('dsMode');
  var guardChk = document.getElementById('dsGuard');
  var kwLbl = document.getElementById('dsKw');
  var status = document.getElementById('dsStatus');
  var inOn = true, outOn = true, mode = 'brief', guardOn = false;
  var wakeOn = false;
  var wakeWord = '小深';
  inChk.onchange = function () { inOn = inChk.checked; };
  outChk.onchange = function () { outOn = outChk.checked; };
  guardChk.onchange = function () { guardOn = guardChk.checked; };
  // 点唤醒词文字修改唤醒词（同时作用于"唤醒词才能输入"与免手模式）
  kwLbl.onclick = function () {
    var kw = prompt('输入唤醒词（说它才作为输入）：', wakeWord);
    if (!kw || !kw.trim()) return;
    wakeWord = kw.trim();
    kwLbl.textContent = wakeWord;
    if (wakeOn) VoiceBridge.startWake(wakeWord);   // 免手模式同步新唤醒词
  };
  try {
    var saved = localStorage.getItem('dsMode');
    if (saved && ['brief', 'key', 'full'].indexOf(saved) >= 0) {
      mode = saved;
      modeSel.value = saved;
    }
  } catch (e) { }

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
      kwLbl.textContent = wakeWord;
      VoiceBridge.startWake(wakeWord);
      wakeOn = true;
      wakeBtn.classList.add('on');
      mic.disabled = true;
      status.textContent = '免手待命：说「' + wakeWord + '」（间隔监听，无唤醒词不入输入）';
    } else {
      VoiceBridge.stopWake();
      wakeOn = false;
      wakeBtn.classList.remove('on');
      mic.disabled = false;
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

  // 在线版本检测与升级：触发原生检查（有新版本会弹窗，可下载安装）
  updBtn.onclick = function () {
    try {
      prompt('__DS_CHECK_UPDATE__');
      status.textContent = '已触发在线检查，请留意提示';
    } catch (e) { status.textContent = '检查更新不可用'; }
  };

  // 原生回传：{type:'start'|'partial'|'final'|'error'|'wake', text:''}
  window.__onSpeech = function (obj) {
    if (!obj) return;
    if (obj.type === 'start') { mic.classList.add('on'); status.textContent = '聆听中…'; }
    else if (obj.type === 'partial') {
      status.textContent = obj.text || '聆听中…';
      // 开启"唤醒词才能输入"时不预填输入框（等 final 判断是否命中唤醒词），防误输入
      if (!guardOn && obj.text) fillInput(obj.text);
    }
    else if (obj.type === 'final') {
      // 说完即发：自动填入并发送给 DeepSeek（唤醒词开关开启时须以唤醒词开头）
      mic.classList.remove('on');
      var t = obj.text || '';
      if (guardOn) {
        var rest = stripKw(t);
        if (rest === null) {
          status.textContent = '未说唤醒词「' + wakeWord + '」，已忽略（防误输入）';
          return;
        }
        t = rest;
      }
      status.textContent = '已发送';
      if (t) { fillInput(t, true); sendMsg(); }
    }
    else if (obj.type === 'error') { mic.classList.remove('on'); status.textContent = '语音错误'; }
    else if (obj.type === 'wake') {
      status.textContent = '已唤醒，发送…';
      if (obj.text) { fillInput(obj.text, true); sendMsg(); }
      else { status.textContent = '唤醒成功，请说内容'; }
    }
  };

  // 检查文本是否以唤醒词开头（允许常见分隔符）；命中返回去除唤醒词后的内容，否则 null
  function stripKw(text) {
    if (!text) return null;
    if (text.startsWith(wakeWord)) return text.substring(wakeWord.length).trim();
    var seps = ['，', ',', '、', ' ', '　'];
    for (var i = 0; i < seps.length; i++) {
      if (text.indexOf(wakeWord + seps[i]) === 0) {
        return text.substring(wakeWord.length + seps[i].length).trim();
      }
    }
    return null;
  }

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

  // 播报模式截断：完整=全文 / 简短=前150字 / 结论=前60字（尽量在句末截断）
  var MODE_LIMIT = { full: 0, brief: 150, key: 60 };
  function trimReply(txt) {
    var limit = MODE_LIMIT[mode] || 0;
    if (!limit || txt.length <= limit) return txt;
    var slice = txt.slice(0, limit);
    var cut = Math.max(slice.lastIndexOf('。'), slice.lastIndexOf('！'),
      slice.lastIndexOf('？'), slice.lastIndexOf('.'), slice.lastIndexOf('\n'));
    if (cut > limit * 0.3) slice = slice.slice(0, cut + 1);
    return slice + '…';
  }

  // 捕获 AI 回复并播报（防抖 1.2s，只读最终完整文本；播报前先播输出提示音，按模式截断精简）。
  // 输入框只用于显示麦克风采集的语音文字；AI 输出仅精简播报，不回填输入框。
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
    if (txt && txt !== lastText && txt !== lastSpoken &&
      (!lastSpoken || txt.indexOf(lastSpoken) !== 0)) {
      lastText = txt;
      if (typeof VoiceBridge !== 'undefined') {
        VoiceBridge.playTone('out');                       // 输出提示音（思考完成）
        setTimeout(function () { VoiceBridge.speak(trimReply(txt)); }, 450);
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
