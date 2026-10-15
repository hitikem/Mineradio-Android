package com.mineradio.android

/**
 * 注入到页面的适配脚本。
 *
 * 三条原则：
 *  1) **不定义 `window.desktopWindow`**。上游前端对它的每一处调用都做了
 *     `window.desktopWindow && typeof ... === 'function'` 的存在性判断，
 *     留空即为「以纯浏览器形态运行」——这正是作者自己支持的运行方式，风险最低。
 *     原生能力改从 `window.MineradioAndroid` 这个独立命名空间提供。
 *  2) **只读取上游已有的 DOM 与全局函数**，不改上游 JS 文件本身，
 *     这样后续同步上游新版本不会产生冲突。
 *  3) 所有逻辑都包在 try/catch 里，适配层出问题绝不能连累播放器本体。
 */
object InjectedPageScript {

    /** 页面加载前注入：DOM 还没生成，只做「插桩」类工作 */
    val EARLY: String = """
(function () {
  if (window.__mineradioAndroidEarly) return;
  window.__mineradioAndroidEarly = true;

  // ---------------------------------------------------------------- 媒体跟踪
  // 上游用 new Audio(...) 动态创建播放器，这里挂钩子把「当前活跃的音频元素」记下来，
  // 供通知栏 / 锁屏 / 蓝牙耳机按键控制使用。
  var active = null;
  var lastPush = 0;

  function pickActive() {
    if (active && active.src) return active;
    var list = document.querySelectorAll('audio');
    for (var i = list.length - 1; i >= 0; i--) {
      if (list[i].src) { active = list[i]; return active; }
    }
    return null;
  }

  function text(sel) {
    try {
      var el = document.querySelector(sel);
      return el ? String(el.textContent || '').trim() : '';
    } catch (e) { return ''; }
  }

  function cover() {
    try {
      var el = document.querySelector('#control-cover');
      if (!el) return '';
      var style = window.getComputedStyle(el);
      var bg = style.backgroundImage || '';
      var m = bg.match(/url\(["']?(.*?)["']?\)/);
      if (m && m[1] && m[1].indexOf('data:') !== 0) return m[1];
      var img = el.querySelector('img');
      if (img && img.src) return img.src;
      return '';
    } catch (e) { return ''; }
  }

  function state() {
    var el = pickActive();
    var duration = el && isFinite(el.duration) ? el.duration : 0;
    var position = el && isFinite(el.currentTime) ? el.currentTime : 0;
    return {
      hasMedia: !!el,
      playing: !!(el && !el.paused && !el.ended),
      title: text('#control-title-text'),
      artist: text('#control-artist'),
      cover: cover(),
      duration: duration,
      position: position
    };
  }

  function push(force) {
    var now = Date.now();
    if (!force && now - lastPush < 900) return;
    lastPush = now;
    try {
      if (window.MineradioNative && window.MineradioNative.onState) {
        window.MineradioNative.onState(JSON.stringify(state()));
      }
    } catch (e) { /* 原生桥不可用时静默忽略 */ }
  }

  function bind(el) {
    if (!el || el.__mineradioBound) return;
    el.__mineradioBound = true;
    ['play', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'seeked', 'emptied']
      .forEach(function (ev) {
        el.addEventListener(ev, function () { push(true); }, true);
      });
    el.addEventListener('timeupdate', function () { push(false); }, true);
    el.addEventListener('volumechange', function () { push(true); }, true);
  }

  // 挂钩 play：谁在播谁就是当前曲目
  try {
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      active = this;
      bind(this);
      push(true);
      return origPlay.apply(this, arguments);
    };
    var origPause = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.pause = function () {
      var r = origPause.apply(this, arguments);
      push(true);
      return r;
    };
  } catch (e) { /* 某些实现不允许改写原型，忽略 */ }

  // 兜底：定期扫描并补绑定（有些元素不走 play 原型）
  setInterval(function () {
    var list = document.querySelectorAll('audio');
    for (var i = 0; i < list.length; i++) bind(list[i]);
    if (!active && list.length) pickActive();
  }, 2000);

  // ---------------------------------------------------------------- 供原生调用的命令
  window.MineradioAndroid = {
    getState: function () { return JSON.stringify(state()); },

    play: function () {
      try {
        var el = pickActive();
        if (el && el.paused) { el.play(); return 'ok'; }
        if (typeof window.togglePlay === 'function' && !(el && !el.paused)) {
          window.togglePlay();
          return 'ok';
        }
        return el ? 'already' : 'no-media';
      } catch (e) { return 'error'; }
    },

    pause: function () {
      try {
        var el = pickActive();
        if (el && !el.paused) { el.pause(); return 'ok'; }
        if (typeof window.togglePlay === 'function') { window.togglePlay(); return 'ok'; }
        return 'no-media';
      } catch (e) { return 'error'; }
    },

    toggle: function () {
      try {
        var el = pickActive();
        if (el) { if (el.paused) { el.play(); } else { el.pause(); } return 'ok'; }
        if (typeof window.togglePlay === 'function') { window.togglePlay(); return 'ok'; }
        return 'no-media';
      } catch (e) { return 'error'; }
    },

    next: function () {
      try {
        if (typeof window.nextTrack === 'function') { window.nextTrack(true); return 'ok'; }
        var btn = document.getElementById('next-btn');
        if (btn) { btn.click(); return 'ok'; }
        return 'unavailable';
      } catch (e) { return 'error'; }
    },

    previous: function () {
      try {
        if (typeof window.prevTrack === 'function') { window.prevTrack(true); return 'ok'; }
        var btn = document.getElementById('prev-btn');
        if (btn) { btn.click(); return 'ok'; }
        return 'unavailable';
      } catch (e) { return 'error'; }
    },

    seekTo: function (seconds) {
      try {
        var el = pickActive();
        if (!el || !isFinite(el.duration)) return 'no-media';
        el.currentTime = Math.max(0, Math.min(el.duration, Number(seconds) || 0));
        push(true);
        return 'ok';
      } catch (e) { return 'error'; }
    },

    seekRatio: function (ratio) {
      try {
        var el = pickActive();
        if (!el || !isFinite(el.duration)) return 'no-media';
        el.currentTime = Math.max(0, Math.min(1, Number(ratio) || 0)) * el.duration;
        push(true);
        return 'ok';
      } catch (e) { return 'error'; }
    },

    setVolume: function (v) {
      try {
        var el = pickActive();
        if (el) el.volume = Math.max(0, Math.min(1, Number(v) || 0));
        return 'ok';
      } catch (e) { return 'error'; }
    },

    /**
     * 处理安卓返回键。
     * 返回 'handled' 表示「关掉了某个弹层，别退出」；'ignored' 表示交给原生决定。
     */
    back: function () {
      try {
        if (document.fullscreenElement) {
          document.exitFullscreen();
          return 'handled';
        }
        var before = overlayCount();
        var init = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
        [document, window].forEach(function (target) {
          try { target.dispatchEvent(new KeyboardEvent('keydown', init)); } catch (e) {}
          try { target.dispatchEvent(new KeyboardEvent('keyup', init)); } catch (e) {}
        });
        return overlayCount() < before ? 'handled' : 'ignored';
      } catch (e) { return 'ignored'; }
    },

    ping: function () { return 'pong'; }
  };

  function overlayCount() {
    try {
      var nodes = document.querySelectorAll('[class*="modal"],[class*="overlay"],[class*="panel"]');
      var n = 0;
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el === document.body) continue;
        var cs = window.getComputedStyle(el);
        if (cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0.05) n++;
      }
      return n;
    } catch (e) { return 0; }
  }

  // ---------------------------------------------------------------- 官方网页登录
  //
  // 上游各平台的登录都是「弹一个 Electron 窗口加载官方登录页，再捞 cookie」，
  // 判断条件是 `window.desktopWindow && window.desktopWindow.isDesktop &&
  // typeof window.desktopWindow.openXxxMusicLogin === 'function'`。
  //
  // 安卓端没有 Electron，这里用原生 WebView 提供等价能力：
  // openXxxMusicLogin() 返回 Promise，原生打开登录窗口，完成后回调 resolve。
  // 这样「电脑上能登的，手机上也能登」，而且不需要第二台设备扫码。
  //
  // 注意：只提供登录相关能力。**刻意不提供 getState**，
  // 于是桌面窗口类功能（壁纸引擎、桌面模式）保持关闭状态 ——
  // 上游对这些调用都有 typeof 守卫，缺失即跳过。
  var loginSeq = 0;
  var loginWaiters = {};

  window.__mineradioLoginResult = function (requestId, json) {
    var resolve = loginWaiters[requestId];
    if (!resolve) return;
    delete loginWaiters[requestId];
    var data = {};
    try { data = JSON.parse(json) || {}; } catch (e) { data = { ok: false, error: 'BAD_RESULT' }; }
    try { resolve(data); } catch (e) {}
  };

  function openNativeLogin(providerId) {
    return new Promise(function (resolve) {
      var requestId = 'login-' + (++loginSeq) + '-' + Date.now();
      loginWaiters[requestId] = resolve;
      try {
        if (window.MineradioNative && typeof window.MineradioNative.openLogin === 'function') {
          window.MineradioNative.openLogin(providerId, requestId);
        } else {
          delete loginWaiters[requestId];
          resolve({ ok: false, error: 'BRIDGE_UNAVAILABLE', message: '原生登录桥不可用' });
        }
      } catch (e) {
        delete loginWaiters[requestId];
        resolve({ ok: false, error: 'BRIDGE_ERROR', message: String(e) });
      }
    });
  }

  window.desktopWindow = {
    isDesktop: true,
    openNeteaseMusicLogin: function () { return openNativeLogin('netease'); },
    openQQMusicLogin: function () { return openNativeLogin('qq'); },
    openKugouMusicLogin: function () { return openNativeLogin('kugou'); }
  };

  // 原生侧在页面就绪后调用一次，把状态同步过去
  window.addEventListener('load', function () { setTimeout(function () { push(true); }, 1200); });
  document.addEventListener('visibilitychange', function () { push(true); });
})();
""".trimIndent()

    /**
     * 页面加载完成后注入：处理移动端布局与交互细节。
     * 这些是「桌面窗口」与「手机屏幕」的差异点，不改上游源码。
     */
    val LATE: String = """
(function () {
  if (window.__mineradioAndroidLate) return;
  window.__mineradioAndroidLate = true;

  var style = document.createElement('style');
  style.id = 'mineradio-android-shell';
  style.textContent = [
    /* 桌面窗口的三个按钮（最小化/最大化/关闭）在安卓上没有意义，隐藏掉 */
    '.desktop-window-controls{display:none !important;}',
    /* 标题栏不再需要「拖动窗口」，但保留其中的引导/更新/DIY 入口 */
    '.desktop-drag-region{-webkit-app-region:no-drag !important;cursor:default !important;}',

    /* 顶到刘海/挖孔屏的安全区，避免内容被遮住 */
    'html,body{height:100% !important;overscroll-behavior:none !important;}',
    '#desktop-window-shell{padding-top:env(safe-area-inset-top) !important;}',

    /* 去掉长按弹出的系统菜单和选中高亮，更接近原生 App 的手感 */
    'html{-webkit-touch-callout:none !important;-webkit-tap-highlight-color:transparent !important;}',

    /* 滚动条在触屏上纯属噪音 */
    '::-webkit-scrollbar{width:0 !important;height:0 !important;}',

    /* 移动端触摸目标下限，避免按钮太小点不中 */
    '#play-btn,#prev-btn,#next-btn{min-width:44px !important;min-height:44px !important;}'
  ].join('\n');
  document.head.appendChild(style);

  // 上报一次布局尺寸，便于在日志里排查手机端排版问题
  try {
    if (window.MineradioNative && window.MineradioNative.log) {
      window.MineradioNative.log(
        '页面就绪 viewport=' + window.innerWidth + 'x' + window.innerHeight +
        ' dpr=' + window.devicePixelRatio
      );
    }
  } catch (e) {}
})();
""".trimIndent()
}
