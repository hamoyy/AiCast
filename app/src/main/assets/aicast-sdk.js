// AiCast（爱投屏）通用 JS SDK —— 唯一的"框架"，只做通信桥，不含内容逻辑（docs/DESIGN.md §9）
(function () {
  if (window.AiCast) return;

  function emit(type, data) {
    try {
      AndroidAiCast.emit(type, JSON.stringify(data === undefined ? null : data));
    } catch (e) { console.warn('AiCast.emit failed', e); }
  }

  var listeners = [];
  window.addEventListener('aicast-message', function (ev) {
    listeners.forEach(function (cb) { try { cb(ev.detail); } catch (e) { console.error(e); } });
  });
  function onMessage(cb) { listeners.push(cb); return function () { listeners = listeners.filter(function (f) { return f !== cb; }); }; }

  function getInfo() {
    return new Promise(function (resolve) {
      setTimeout(function () {
        try { resolve(JSON.parse(AndroidAiCast.getInfo())); } catch (e) { resolve(null); }
      }, 0);
    });
  }

  window.AiCast = { emit: emit, onMessage: onMessage, getInfo: getInfo };

  // 遥控器/键盘按键也作为事件上报（TV 场景 agent 可感知选台/确认键）
  // Android D-pad 进入 WebView 后映射为方向键/回车：19-23 为原生 keyCode，37-40/13 为 Chromium 映射值
  var KEYS = { 19:'UP', 20:'DOWN', 21:'LEFT', 22:'RIGHT', 23:'OK', 37:'LEFT', 38:'UP', 39:'RIGHT', 40:'DOWN', 13:'OK' };
  window.addEventListener('keydown', function (ev) {
    if (KEYS[ev.keyCode]) emit('key', { key: KEYS[ev.keyCode], keyCode: ev.keyCode });
  });
})();
