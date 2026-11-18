// AiCast（爱投屏）通用 JS SDK —— 唯一的"框架"，只做通信桥，不含内容逻辑（docs/DESIGN.md §9）
(function () {
  if (window.AiCast) return;

  // 4.4（WebView 内核 Chromium 30~33）没有 Promise：不补的话 SDK 里的 getInfo() 会直接
  // ReferenceError，页面一调就整段脚本失效。这里只实现 SDK 需要的最小语义（then/catch/
  // resolve/reject，异步回调在下一个 tick），足够 ES5 页面使用。
  if (typeof window.Promise !== 'function') {
    var LegacyPromise = function (executor) {
      var state = 0, value, cbs = [];
      var self = this;
      function settle(ok, v) {
        if (state) return;
        state = ok ? 1 : 2;
        value = v;
        setTimeout(function () {
          for (var i = 0; i < cbs.length; i++) { cbs[i](state === 1, value); }
          cbs = [];
        }, 0);
      }
      self._then = function (ok, fail) {
        return new window.Promise(function (res, rej) {
          var cb = function (success, v) {
            try {
              var f = success ? ok : fail;
              if (typeof f === 'function') res(f(v));
              else (success ? res : rej)(v);
            } catch (e) { rej(e); }
          };
          if (state) setTimeout(function () { cb(state === 1, value); }, 0);
          else cbs.push(cb);
        });
      };
      try { executor(function (v) { settle(true, v); }, function (e) { settle(false, e); }); }
      catch (e) { settle(false, e); }
    };
    LegacyPromise.prototype.then = function (ok, fail) { return this._then(ok, fail); };
    LegacyPromise.prototype['catch'] = function (fail) { return this._then(null, fail); };
    LegacyPromise.resolve = function (v) { return new LegacyPromise(function (r) { r(v); }); };
    LegacyPromise.reject = function (e) { return new LegacyPromise(function (_, j) { j(e); }); };
    window.Promise = LegacyPromise;
  }

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
