/* DeepSeek 语音层（Web 技术）：浮动工具条 + ⚙️ 设置/关于双菜单 + 免手模式（关键字唤醒）+ 眼镜图片。
   由原生在 chat.deepseek.com 页面加载后注入。
   交互：
   🎤 点一下说话，说完自动填入并发送（final 事件）；受"输入"开关控制；
   🛎 免手模式：说唤醒词（默认"小深"）→ 自动填入并发送 → 播报；播报期间暂停监听，
      播报完毕按"输入"开关决定是否恢复监听（开=继续接收语音转文字，关=停止接收）；
   📷 智能眼镜最新照片作为图片输入；⚙️ 设置（输入/播报/模式/唤醒词）与 关于（版本/检查更新）；🎧 蓝牙耳机状态。 */
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
    '#dsCall{width:auto;min-width:52px;padding:0 10px;font-size:18px;background:linear-gradient(145deg,#10b981,#059669);box-shadow:0 2px 8px rgba(16,185,129,.45);}' +
    '#dsCall.on{background:linear-gradient(145deg,#ef4444,#dc2626);box-shadow:0 2px 8px rgba(239,68,68,.5);animation:dsPulse 1.2s infinite;}' +
    '#dsBt{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;' +
    'border-radius:10px;font-size:15px;opacity:.38;cursor:default;background:rgba(255,255,255,.06);' +
    'transition:opacity .3s,filter .3s,box-shadow .3s;}' +
    '#dsBt.on{opacity:1;box-shadow:0 0 10px rgba(59,130,246,.8);filter:drop-shadow(0 0 4px #3b82f6);}' +
    '#dsStatus{max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;opacity:.8;' +
    'font-size:12px;color:#93c5fd;margin-left:auto;}' +
    /* ---------- 菜单面板（设置 / 关于 双 tab） ---------- */
    '#dsAbout{position:fixed;top:56px;right:8px;z-index:2147483646;display:none;min-width:280px;' +
    'background:rgba(24,26,38,.97);color:#fff;border:1px solid rgba(255,255,255,.12);' +
    'border-radius:14px;padding:12px 14px;font:13px/1.5 system-ui,-apple-system,sans-serif;' +
    'box-shadow:0 12px 32px rgba(0,0,0,.55);}' +
    '#dsAbout.show{display:block;}' +
    '#dsTabs{display:flex;gap:6px;margin-bottom:10px;}' +
    '#dsTabs button{flex:1;background:rgba(255,255,255,.08);color:#d1d5db;border:0;border-radius:8px;' +
    'padding:6px 0;font-size:13px;cursor:pointer;}' +
    '#dsTabs button.on{background:#3b82f6;color:#fff;font-weight:500;}' +
    '#dsPanelSet,#dsPanelAbout{display:none;}' +
    '#dsPanelSet.show,#dsPanelAbout.show{display:block;}' +
    '.dsSw{display:inline-flex;align-items:center;gap:6px;cursor:pointer;user-select:none;color:#dbeafe;' +
    'background:rgba(255,255,255,.07);padding:4px 8px;border-radius:9px;margin:0 8px 8px 0;}' +
    '.dsSw input{display:none;}' +
    '.dsSw .dsTrack{width:30px;height:17px;border-radius:9px;background:#52525b;position:relative;transition:background .2s;}' +
    '.dsSw .dsThumb{position:absolute;top:2px;left:2px;width:13px;height:13px;border-radius:50%;' +
    'background:#fff;transition:left .18s ease;box-shadow:0 1px 2px rgba(0,0,0,.4);}' +
    '.dsSw input:checked + .dsTrack{background:#22c55e;}' +
    '.dsSw input:checked + .dsTrack .dsThumb{left:15px;}' +
    '#dsMode{background:#1f2937;color:#e5e7eb;border:1px solid rgba(255,255,255,.14);border-radius:8px;' +
    'padding:3px 6px;font-size:12px;cursor:pointer;outline:none;}' +
    '#dsKw{display:inline-block;background:#f59e0b;color:#1f2937;font-weight:700;padding:4px 10px;' +
    'border-radius:8px;font-size:12px;cursor:pointer;user-select:none;box-shadow:0 1px 4px rgba(245,158,11,.4);' +
    'vertical-align:middle;}' +
    '#dsKw:active{transform:scale(.92);}' +
    '#dsAbout .dsSec{margin:8px 0 4px;font-size:12px;color:#9ca3af;letter-spacing:1px;}' +
    '#dsAbout .dsCur{margin:2px 0;font-size:14px;color:#e5e7eb;}' +
    '#dsAbout .dsVer{color:#f59e0b;font-weight:800;font-size:16px;}' +
    '#dsAbout .dsFeat{color:#93c5fd;margin:4px 0 10px;line-height:1.8;}' +
    '#dsAbout .dsBtn{display:inline-flex;align-items:center;gap:6px;margin:0 8px 0 0;' +
    'background:linear-gradient(145deg,#3b82f6,#2563eb);color:#fff;border:0;border-radius:9px;' +
    'padding:7px 14px;font-size:13px;cursor:pointer;}' +
    '#dsAbout .dsBtn:active{transform:scale(.95);}' +
    '#dsAbout #dsAboutClose{background:#4b5563;margin-right:0;}' +
    '#dsDiagOut{display:none;max-height:190px;overflow:auto;margin:8px 0 0;padding:8px;' +
    'background:rgba(0,0,0,.45);border:1px solid rgba(255,255,255,.1);border-radius:8px;' +
    'color:#a7f3d0;font:11px/1.5 ui-monospace,Consolas,monospace;white-space:pre-wrap;word-break:break-all;}' +
    '#dsDiagOut.show{display:block;}';

  var style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  var bar = document.createElement('div');
  bar.id = 'dsBar';
  bar.innerHTML =
    '<button id="dsCall" title="电话模式：接通后直接说话，说话即转文字自动发送，回复自动播报（像打电话）">📞</button>' +
    '<button id="dsMic" title="语音输入：点一下说话，识别完自动发送；麦克风不可用/无权限时可在输入框手动输入">🎤</button>' +
    '<button id="dsWake" title="免手模式：说唤醒词才输入，回复自动播报">🛎</button>' +
    '<button id="dsImg" title="眼镜图片：用最新照片（智能眼镜优先）作为输入">📷</button>' +
    '<button id="dsMenu" title="设置（三点菜单）：输入/播报/唤醒词、版本与更新">⋯</button>' +
    '<span id="dsBt" title="蓝牙耳机：连上后语音输入走耳机麦克风、播报走耳机">🎧</span>' +
    '<span id="dsStatus"></span>';
  document.body.appendChild(bar);

  // 菜单面板：设置 / 关于 双 tab
  var about = document.createElement('div');
  about.id = 'dsAbout';
  about.innerHTML =
    '<div id="dsTabs">' +
    '<button id="dsTabSet" class="on">设置</button>' +
    '<button id="dsTabAbout">关于</button>' +
    '</div>' +
    '<div id="dsPanelSet" class="show">' +
    '<div class="dsSec">语音输入</div>' +
    '<label class="dsSw">语音转文字<input type="checkbox" id="dsInChk" checked><span class="dsTrack"><span class="dsThumb"></span></span></label>' +
    '<label class="dsSw" title="识别文字实时显示在输入框，说话以唤醒词结尾（如：查天气小深）自动发送">结尾小深发送<input type="checkbox" id="dsTail"><span class="dsTrack"><span class="dsThumb"></span></span></label>' +
    '<div class="dsSec">语音播报</div>' +
    '<label class="dsSw">朗读回复<input type="checkbox" id="dsOutChk" checked><span class="dsTrack"><span class="dsThumb"></span></span></label>' +
    '<label class="dsSw">模式<select id="dsMode">' +
    '<option value="brief">简短</option><option value="key">结论</option><option value="full">完整</option></select></label>' +
    '<div class="dsSec">免手唤醒</div>' +
    '<label class="dsSw" title="开启后必须说唤醒词才作为输入，防周围语音误输入">唤醒词才输入<input type="checkbox" id="dsGuard"><span class="dsTrack"><span class="dsThumb"></span></span></label>' +
    '<span id="dsKw" title="点我修改唤醒词">小深</span>' +
    '</div>' +
    '<div id="dsPanelAbout">' +
    '<div class="dsSec">版本与更新</div>' +
    '<div class="dsCur">当前版本：<span class="dsVer" id="dsVer">--</span></div>' +
    '<div style="margin:10px 0 6px;">' +
    '<button class="dsBtn" id="dsAboutUpd">🔄 检查更新</button>' +
    '<button class="dsBtn" id="dsDiag">🔎 语音诊断</button>' +
    '<button class="dsBtn" id="dsAboutClose">✕ 关闭</button>' +
    '</div>' +
    '<pre id="dsDiagOut"></pre>' +
    '<div class="dsSec">功能</div>' +
    '<div class="dsFeat">' +
    '<span>📞 电话模式：接通后直接说话，自动发送并播报</span>' +
    '<span>🎤 语音输入：说话即转文字（麦克风不可用时可手动输入）</span>' +
    '<span>🔊 语音播报：回复自动朗读</span>' +
    '<span>🛎 免手唤醒：说唤醒词才输入</span>' +
    '<span>📷 眼镜图片：最新照片作输入</span>' +
    '</div>' +
    '</div>';
  document.body.appendChild(about);

  var mic = document.getElementById('dsMic');
  var wakeBtn = document.getElementById('dsWake');
  var imgBtn = document.getElementById('dsImg');
  var menuBtn = document.getElementById('dsMenu');
  var btIcon = document.getElementById('dsBt');
  var callBtn = document.getElementById('dsCall');
  var inChk = document.getElementById('dsInChk');
  var outChk = document.getElementById('dsOutChk');
  var modeSel = document.getElementById('dsMode');
  var guardChk = document.getElementById('dsGuard');
  var tailChk = document.getElementById('dsTail');
  var kwLbl = document.getElementById('dsKw');
  var status = document.getElementById('dsStatus');
  var inOn = true, outOn = true, mode = 'brief', guardOn = false, tailOn = false;
  var wakeOn = false;
  var callOn = false;
  var wakeWord = '小深';

  // 双 tab 切换：设置 / 关于
  var tabSet = document.getElementById('dsTabSet');
  var tabAbout = document.getElementById('dsTabAbout');
  var panelSet = document.getElementById('dsPanelSet');
  var panelAbout = document.getElementById('dsPanelAbout');
  function showTab(which) {
    tabSet.classList.toggle('on', which === 'set');
    tabAbout.classList.toggle('on', which === 'about');
    panelSet.classList.toggle('show', which === 'set');
    panelAbout.classList.toggle('show', which === 'about');
  }
  tabSet.onclick = function () { showTab('set'); };
  tabAbout.onclick = function () { showTab('about'); };

  // 关于：显示版本号、展开/收起、检查更新
  try {
    var ver = typeof VoiceBridge !== 'undefined' && VoiceBridge.getVersionName && VoiceBridge.getVersionName();
    if (ver) document.getElementById('dsVer').textContent = 'v' + ver;
  } catch (e) { }
  menuBtn.onclick = function () { about.classList.toggle('show'); };
  document.getElementById('dsAboutClose').onclick = function () { about.classList.remove('show'); };
  document.getElementById('dsAboutUpd').onclick = function () {
    try {
      prompt('__DS_CHECK_UPDATE__');
      status.textContent = '已触发在线检查，请留意提示';
    } catch (e) { status.textContent = '检查更新不可用'; }
  };

  // 语音诊断：把原生侧真实状态摊开（权限/识别服务/识别器/最近错误），定位"提示已接通但没声音"
  var diagOut = document.getElementById('dsDiagOut');
  document.getElementById('dsDiag').onclick = function () {
    var lines = [];
    try {
      if (typeof VoiceBridge === 'undefined') {
        lines.push('❌ VoiceBridge 未注入（JS 桥丢失，重启 App）');
      } else if (!VoiceBridge.getVoiceDiagnostics) {
        lines.push('⚠️ 旧版原生桥，无诊断接口，请升级 App');
      } else {
        var d = JSON.parse(VoiceBridge.getVoiceDiagnostics());
        lines.push((d.micPermission ? '✅' : '❌') + ' 麦克风权限：' + d.micPermission);
        lines.push((d.recognizerReady ? '✅' : '❌') + ' 识别器已创建：' + d.recognizerReady);
        lines.push('   系统报告可用：' + d.systemReportsAvailable);
        lines.push('   使用服务：' + d.component + (d.onDevice ? '（离线）' : ''));
        lines.push('   已安装识别服务：' + ((d.installedServices || []).join(', ') || '（无）'));
        lines.push('   电话模式：' + d.callActive + '，正在录音：' + d.listening);
        lines.push('   语音合成：' + d.ttsReady);
        lines.push('   最近错误：' + (d.lastError || '无'));
        if (!d.micPermission) lines.push('👉 请点下方按钮授权麦克风');
        else if (!d.recognizerReady) lines.push('👉 本机缺少语音识别服务，需安装"Google 语音服务"或系统语音包');
      }
      var box = findInputBox();
      lines.push((box ? '✅' : '❌') + ' 输入框定位：' + (box ? (box.tagName + (box.id ? '#' + box.id : '')) : '未找到'));
    } catch (e) {
      lines.push('诊断异常：' + e);
    }
    diagOut.textContent = lines.join('\n');
    diagOut.classList.add('show');
    try {
      if (typeof VoiceBridge !== 'undefined' && VoiceBridge.hasMicPermission
        && !VoiceBridge.hasMicPermission()) {
        VoiceBridge.requestMicPermission();
      }
    } catch (e) { }
  };
  document.addEventListener('click', function (e) {
    if (about.classList.contains('show') && !about.contains(e.target) && e.target !== menuBtn) {
      about.classList.remove('show');
    }
  });

  inChk.onchange = function () { inOn = inChk.checked; };
  outChk.onchange = function () { outOn = outChk.checked; };
  guardChk.onchange = function () { guardOn = guardChk.checked; };
  tailChk.onchange = function () { tailOn = tailChk.checked; };
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

  // 把原生返回的状态码翻译成人话（ok 以外都要让用户看见真实原因）
  function explain(r) {
    if (r === 'noperm') return '需要麦克风权限，请在弹窗中点「允许」';
    if (r === 'norecog') return '本机无语音识别服务 → 点 ⋯ 看「语音诊断」';
    if (r === 'busy') return '识别器忙，请稍后再试';
    return '语音启动失败（' + (r || '未知') + '），可手动输入';
  }

  mic.onclick = function () {
    refreshBt();
    if (typeof VoiceBridge === 'undefined') { status.textContent = '语音不可用，请在输入框手动输入'; return; }
    if (mic.classList.contains('on')) { VoiceBridge.stopRecognition(); mic.classList.remove('on'); }
    else {
      var r;
      try { r = VoiceBridge.startRecognition(); } catch (e) { r = 'exception'; }
      r = (r === null || r === undefined) ? 'ok' : String(r);
      if (r !== 'ok') { mic.classList.remove('on'); status.textContent = explain(r); }
    }
  };

  // 免手模式开关：开启后禁用手动 mic，避免两套监听打架
  wakeBtn.onclick = function () {
    if (typeof VoiceBridge === 'undefined') { status.textContent = '语音不可用，请手动输入'; return; }
    if (!wakeOn) {
      var kw = prompt('输入唤醒词（说它才激活输入）：', wakeWord);
      if (!kw || !kw.trim()) return;
      wakeWord = kw.trim();
      kwLbl.textContent = wakeWord;
      var rw;
      try { rw = VoiceBridge.startWake(wakeWord); } catch (e) { rw = 'exception'; }
      rw = (rw === null || rw === undefined) ? 'ok' : String(rw);
      if (rw !== 'ok') { status.textContent = explain(rw); return; }
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

  // 电话模式：接通后说话即输入（无需唤醒词、无需按键），回复自动播报，说完继续听。
  // 关键：以原生 startCall() 的**真实返回状态**决定 UI，绝不"假装已接通"。
  function callUiOn() {
    callOn = true;
    callBtn.classList.add('on');
    callBtn.textContent = '📵';
    mic.disabled = true;
    wakeBtn.disabled = true;
  }

  function callUiOff() {
    callOn = false;
    callBtn.classList.remove('on');
    callBtn.textContent = '📞';
    mic.disabled = false;
    wakeBtn.disabled = false;
  }

  function connectCall() {
    if (typeof VoiceBridge === 'undefined' || !VoiceBridge.startCall) {
      status.textContent = '语音桥未就绪，请重启 App（可手动输入）';
      callUiOff();
      return false;
    }
    // 关闭手动 mic 与免手模式，避免两套监听打架
    if (wakeOn) { try { VoiceBridge.stopWake(); } catch (e) { } wakeOn = false; wakeBtn.classList.remove('on'); }
    mic.classList.remove('on');
    try { VoiceBridge.stopRecognition(); } catch (e) { }

    var r;
    try { r = VoiceBridge.startCall(); } catch (e) { r = 'exception'; }
    r = (r === null || r === undefined) ? '' : String(r);

    if (r === 'ok') {
      callUiOn();
      status.textContent = '已接通：直接说话（说完自动发送）';
      return true;
    }
    // 失败：如实告知原因，别让用户对着不工作的麦克风说话
    callUiOff();
    if (r === 'noperm') {
      status.textContent = '需要麦克风权限，请在弹窗中点「允许」';
    } else if (r === 'norecog') {
      status.textContent = '本机无语音识别服务 → 点 ⋯ 看「语音诊断」';
    } else {
      status.textContent = '接通失败（' + (r || '未知') + '），可手动输入';
    }
    return false;
  }

  function hangUpCall() {
    try { VoiceBridge.stopCall(); } catch (e) { }
    callUiOff();
    status.textContent = '已挂断';
  }

  callBtn.onclick = function () {
    if (!callOn) connectCall();
    else hangUpCall();
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

  // 原生回传：{type:'start'|'partial'|'final'|'error'|'wake'|'done', text:''}
  window.__onSpeech = function (obj) {
    if (!obj) return;
    if (obj.type === 'start') { mic.classList.add('on'); status.textContent = '聆听中…'; }
    else if (obj.type === 'partial') {
      status.textContent = obj.text || '聆听中…';
      // 开启"结尾小深发送"时识别文字实时显示在输入框（不受"唤醒词才输入"拦截）；静默填充不刷提示
      if (obj.text && (tailOn || !guardOn)) fillInput(obj.text, true);
    }
    else if (obj.type === 'final') {
      // 说完即发：受"输入"开关与"唤醒词才输入"开关控制；"结尾小深发送"开启时以结尾唤醒词为发送信号
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
      if (!t) return;
      if (tailOn) {
        var end = stripKwEnd(t);
        if (end !== null) {
          // 以"小深"结尾：去掉结尾唤醒词，填入输入框并发送
          if (inOn) {
            status.textContent = '已发送';
            fillInput(end, false);
            sendMsg();
          } else {
            status.textContent = '已识别（输入已关闭，未发送）';
          }
        } else {
          // 未以小深结尾：文字保留在输入框，用户可继续语音或手动发送
          if (inOn) {
            fillInput(t, false);
            status.textContent = '已填入，说「' + wakeWord + '」结尾或手动发送';
          } else {
            status.textContent = '已识别（输入已关闭，未发送）';
          }
        }
      } else if (inOn) {
        status.textContent = '已发送';
        fillInput(t, false);
        sendMsg();
      } else {
        status.textContent = '已识别（输入已关闭，未发送）';
      }
    }
    else if (obj.type === 'error') {
      mic.classList.remove('on');
      status.textContent = obj.text ? ('语音出错：' + obj.text) : '语音不可用，可在输入框手动输入';
    }
    // 电话模式：原生真正开始录音（区别于"以为接通了"）
    else if (obj.type === 'callready') {
      if (callOn) { callUiOn(); status.textContent = '聆听中…请说话'; }
    }
    // 电话模式真实故障上报：权限被拒 / 识别服务异常
    else if (obj.type === 'callerr') {
      status.textContent = obj.text || '语音识别异常';
      if (obj.text && (obj.text.indexOf('权限') >= 0 || obj.text.indexOf('不可用') >= 0)) {
        callUiOff();   // 已被原生挂断，UI 同步复位，避免显示"已接通"误导
      }
    }
    // 麦克风授权结果：授权成功 → 自动重新接通电话模式
    else if (obj.type === 'perm') {
      if (obj.text === 'granted') {
        status.textContent = '麦克风已授权，正在接通…';
        autoTried = false;
        setTimeout(function () { connectCall(); }, 300);
      } else {
        callUiOff();
        status.textContent = '麦克风权限被拒绝，电话模式不可用（可手动输入）';
      }
    }
    else if (obj.type === 'wake') {
      if (!obj.text) { status.textContent = callOn ? '请说话…' : '唤醒成功，请说内容'; return; }
      var wt = tailOn ? (stripKwEnd(obj.text) || obj.text) : obj.text;
      if (inOn) {
        status.textContent = callOn ? '已发送：' + wt : '已唤醒，发送…';
        fillInput(wt, false);
        sendMsg();
      } else {
        status.textContent = '已识别（输入已关闭，未发送）';
      }
    }
    else if (obj.type === 'done') {
      // 播报完毕：电话模式恢复监听继续听；免手模式恢复唤醒待命
      if (callOn) {
        if (inOn) {
          try { VoiceBridge.resumeCall(); } catch (e) { }
          status.textContent = '继续聆听：请说话';
        } else {
          status.textContent = '播报完毕（输入已关闭，暂停接收语音）';
        }
      } else if (wakeOn) {
        if (inOn) {
          try { VoiceBridge.resumeWake(); } catch (e) { }
          status.textContent = '免手待命：说「' + wakeWord + '」';
        } else {
          status.textContent = '播报完毕（输入已关闭，暂停接收语音）';
        }
      }
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

  // 检查文本是否以唤醒词结尾（允许结尾带标点/空格，如"小深""小深。"）；命中返回去除结尾唤醒词后的内容，否则 null
  function stripKwEnd(text) {
    if (!text || !wakeWord) return null;
    var re = new RegExp(wakeWord.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[，。！？,.!?、\\s]*$');
    if (re.test(text)) {
      return text.replace(re, '').trim();
    }
    return null;
  }

  // 从候选里挑"最像聊天输入框"的那个：可见 + 可编辑 + 面积大 + 越靠页面下方越优先
  function pickBest(list) {
    var best = null, bestScore = -1;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      try {
        if (el.disabled || el.readOnly) continue;
        if (el.closest && el.closest('#dsBar, #dsAbout')) continue;   // 排除本工具条自身
        var r = el.getBoundingClientRect();
        if (r.width < 40 || r.height < 14) continue;
        var cs = window.getComputedStyle(el);
        if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
        // 评分：面积为主，位置越靠下加权（聊天输入框固定在底部）
        var score = r.width * r.height + r.top * 3;
        if (score > bestScore) { bestScore = score; best = el; }
      } catch (e) { }
    }
    return best;
  }

  // 查找当前可用的聊天输入框：先精确命中 DeepSeek 输入框 id，再按类型分级挑选；
  // 主文档找不到则穿透 iframe（页面结构可能变化）
  function findInputBox() {
    try {
      var direct = document.querySelector('#chat-input, textarea#chat-input, textarea[id*="chat"]');
      if (direct) {
        var dr = direct.getBoundingClientRect();
        if (dr.width > 20 && dr.height > 10) return direct;
      }
    } catch (e) { }
    function scan(doc) {
      var ta = pickBest(doc.querySelectorAll('textarea'));
      if (ta) return ta;
      var ce = pickBest(doc.querySelectorAll('[contenteditable="true"], div[contenteditable], [role="textbox"]'));
      if (ce) return ce;
      return pickBest(doc.querySelectorAll('input[type="text"], input[type="search"]'));
    }
    var box = scan(document);
    if (box) return box;
    try {
      var frames = document.querySelectorAll('iframe');
      for (var j = 0; j < frames.length; j++) {
        try {
          var d = frames[j].contentDocument;
          if (d) { box = scan(d); if (box) return box; }
        } catch (e) { }
      }
    } catch (e) { }
    return null;
  }

  // 手动输入不再拦截：语音优先、识别完自动发送；麦克风不可用/无权限时用户可直接在输入框打字（Enter 发送）。

  // 向输入框注入文本（多级 fallback：execCommand → 原生 setter → 直接赋值），返回是否成功
  function setBoxText(box, text) {
    if (!box) return false;
    try {
      if (box.tagName === 'TEXTAREA' || box.tagName === 'INPUT') {
        try {
          var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
          if (setter) setter.call(box, text); else box.value = text;
        } catch (e2) { box.value = text; }
        box.dispatchEvent(new Event('input', { bubbles: true }));
        return (box.value || '').indexOf(text) >= 0;
      }
      // contenteditable（富文本编辑器）
      box.focus();
      var ok = false;
      try {
        document.execCommand('selectAll', false, null);
        document.execCommand('insertText', false, text);
        ok = (box.innerText || '').indexOf(text) >= 0;
      } catch (e) { ok = false; }
      if (!ok) {
        box.innerText = text;                                  // fallback：直接赋值
        box.dispatchEvent(new Event('input', { bubbles: true }));
        box.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
        ok = (box.innerText || '').indexOf(text) >= 0;
      }
      return ok;
    } catch (e) { return false; }
  }

  function fillInput(text, silent) {
    if (!inOn) return false;                     // "输入"开关关闭时不填入输入框
    var box = findInputBox();
    if (!box) {
      status.textContent = '未找到输入框（页面结构变化）';
      return false;
    }
    var ok = setBoxText(box, text);
    if (!ok) status.textContent = '输入框注入失败';
    else if (!silent) status.textContent = '已填入';
    return ok;
  }

  // 完整回车键序列（keydown/keypress/keyup）：部分编辑器只认其中某一个
  function fireEnter(box) {
    var opts = {
      key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
      bubbles: true, cancelable: true
    };
    try { box.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e) { }
    try { box.dispatchEvent(new KeyboardEvent('keypress', opts)); } catch (e) { }
    try { box.dispatchEvent(new KeyboardEvent('keyup', opts)); } catch (e) { }
  }

  // 找发送按钮：DeepSeek 用 div[role=button]，故不能只找 <button>
  function clickSend(box) {
    var sels = [
      'button[aria-label*="发送"]', 'div[role="button"][aria-label*="发送"]',
      '[data-testid*="send"]', 'button[class*="send"]', 'div[class*="send"]',
      '[class*="send"] button', 'button[type="submit"]'
    ];
    var boxRect = null;
    try { boxRect = box && box.getBoundingClientRect(); } catch (e) { }
    for (var i = 0; i < sels.length; i++) {
      var els;
      try { els = document.querySelectorAll(sels[i]); } catch (e) { continue; }
      for (var j = 0; j < els.length; j++) {
        var el = els[j];
        try {
          if (el.closest && el.closest('#dsBar, #dsAbout')) continue;
          if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
          var r = el.getBoundingClientRect();
          if (r.width < 8 || r.height < 8) continue;
          // 发送按钮通常和输入框在同一水平带内
          if (boxRect && r.top < boxRect.top - 120) continue;
          el.click();
          return true;
        } catch (e) { }
      }
    }
    return false;
  }

  // 发送：Enter 优先 → 仍未发出则点发送按钮 → 再失败则明确提示（不静默失败）
  function sendMsg() {
    var box = findInputBox();
    if (!box) {
      status.textContent = '未找到输入框（页面结构变化）';
      return;
    }
    try { box.focus(); } catch (e) { }
    fireEnter(box);
    setTimeout(function () {
      try {
        var still = findInputBox();
        var cur = still && (still.value || still.innerText || '').trim();
        if (!cur) return;                       // 已发出
        if (clickSend(still)) {
          setTimeout(function () {
            try {
              var again = findInputBox();
              var left = again && (again.value || again.innerText || '').trim();
              if (left) status.textContent = '已填入但未能自动发送，请手动点发送';
            } catch (e3) { }
          }, 700);
        } else {
          status.textContent = '已填入但没找到发送按钮，请手动发送';
        }
      } catch (e2) { }
    }, 450);
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
  // 免手模式：播报开始前暂停唤醒监听（避免采集到播报声），播报完毕由 done 事件决定是否恢复。
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
        if (wakeOn) { try { VoiceBridge.pauseWake(); } catch (e) { } }   // 播报期间暂停免手监听
        if (callOn) { try { VoiceBridge.pauseCall(); } catch (e) { } }   // 播报期间暂停电话监听
        VoiceBridge.playTone('out');                                     // 输出提示音（思考完成）
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

  // 自动接通电话模式（像打电话：打开即可直接说话输入，无需按键/唤醒词）。
  // 等页面稳定后尝试；语音不可用或用户手动挂断后不再自动重连。
  var autoTried = false;
  function tryAutoCall() {
    if (autoTried || callOn || !inOn) return false;
    // 桥可能还没挂上（跨域导航/ROM 延迟）→ 本轮不算尝试，等下一轮
    if (typeof VoiceBridge === 'undefined' || !VoiceBridge.startCall) return false;
    autoTried = true;
    connectCall();
    return true;
  }
  // 页面与 JS 桥就绪时机不确定：轮询尝试接通，成功/明确失败即停，20 秒后放弃
  var autoTimer = setInterval(function () {
    if (autoTried || callOn) { clearInterval(autoTimer); return; }
    tryAutoCall();
  }, 1200);
  setTimeout(function () { clearInterval(autoTimer); }, 20000);
  setTimeout(tryAutoCall, 2000);
})();
