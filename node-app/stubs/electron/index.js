'use strict';

/**
 * Mineradio Android —— Electron 兼容桩
 *
 * 上游 Mineradio 是 Electron 应用，部分模块（如 qishui-auth-v6.js）在顶层
 * `require('electron')`。安卓端我们把 Node 运行时嵌进 App，没有 Electron 主进程，
 * 因此需要一个桩来：
 *   1) 保证 require 不抛异常（否则整个 server.js 起不来）；
 *   2) 对「确实做不到」的桌面专有能力给出明确报错，让对应接口优雅失败而不是拖垮进程；
 *   3) 对能做的能力（路径、退出等）给出合理实现。
 *
 * 覆盖情况：
 *   ✅ app / session / 路径 / 生命周期钩子  —— 可用或安全空实现
 *   ⚠️ BrowserWindow（汽水音乐安全验证窗口）—— 抛错降级，安卓 v1 不支持
 */

const os = require('os');
const path = require('path');
const fs = require('fs');

// ---------------------------------------------------------------- 运行环境路径

function resolveUserDataDir() {
  // 由 Android 侧 main.js 注入；兜底用系统临时目录，保证永远可写
  const injected = process.env.MINERADIO_ANDROID_USER_DATA;
  if (injected) {
    try {
      fs.mkdirSync(injected, { recursive: true });
      return injected;
    } catch (_) { /* 落到下面的兜底 */ }
  }
  const fallback = path.join(os.tmpdir(), 'mineradio-android');
  try { fs.mkdirSync(fallback, { recursive: true }); } catch (_) {}
  return fallback;
}

const USER_DATA = resolveUserDataDir();

const PATH_MAP = {
  userData: USER_DATA,
  appData: USER_DATA,
  cache: path.join(USER_DATA, 'cache'),
  temp: os.tmpdir(),
  logs: path.join(USER_DATA, 'logs'),
  home: USER_DATA,
  documents: USER_DATA,
  downloads: path.join(USER_DATA, 'downloads'),
  music: path.join(USER_DATA, 'music'),
  desktop: USER_DATA,
  exe: process.execPath || 'node',
};

// ---------------------------------------------------------------- app

const appListeners = new Map();

const app = {
  isReady: () => true,
  whenReady: () => Promise.resolve(),
  getPath: (name) => {
    const p = PATH_MAP[name] || path.join(USER_DATA, String(name || 'misc'));
    try { fs.mkdirSync(p, { recursive: true }); } catch (_) {}
    return p;
  },
  getName: () => 'Mineradio',
  getVersion: () => process.env.MINERADIO_VERSION || '2.2.0',
  getAppPath: () => process.env.MINERADIO_PROJECT_DIR || __dirname,
  isPackaged: false,
  setAppUserModelId: () => {},
  requestSingleInstanceLock: () => true,
  releaseSingleInstanceLock: () => {},
  quit: () => { process.exit(0); },
  exit: () => { process.exit(0); },
  relaunch: () => {},
  disableHardwareAcceleration: () => {},
  commandLine: { appendSwitch: () => {}, appendArgument: () => {}, hasSwitch: () => false },
  on: (event, handler) => {
    if (!appListeners.has(event)) appListeners.set(event, []);
    appListeners.get(event).push(handler);
    return app;
  },
  once: (event, handler) => app.on(event, handler),
  off: () => app,
  removeListener: () => app,
  emit: () => false,
  whenReadySync: () => {},
  setPath: () => {},
};

// ---------------------------------------------------------------- session

function makeWebRequest() {
  // 上游只用它做「事后日志记录」，空实现不影响主流程
  return {
    onBeforeRequest: () => {},
    onBeforeSendHeaders: () => {},
    onHeadersReceived: () => {},
    onCompleted: () => {},
    onErrorOccurred: () => {},
    onResponseStarted: () => {},
    onBeforeRedirect: () => {},
  };
}

function makeCookies() {
  return {
    get: (_filter, cb) => { if (typeof cb === 'function') cb(null, []); return Promise.resolve([]); },
    set: (_details, cb) => { if (typeof cb === 'function') cb(null); return Promise.resolve(); },
    remove: (_url, _name, cb) => { if (typeof cb === 'function') cb(null); return Promise.resolve(); },
    flushStore: () => Promise.resolve(),
    on: () => {},
  };
}

function makeSession(partition) {
  return {
    partition,
    webRequest: makeWebRequest(),
    cookies: makeCookies(),
    clearStorageData: () => Promise.resolve(),
    clearCache: () => Promise.resolve(),
    flushStorageData: () => {},
    setUserAgent: () => {},
    getUserAgent: () => 'Mineradio-Android',
    setPermissionRequestHandler: () => {},
    setCertificateVerifyProc: () => {},
    protocol: {
      handle: () => Promise.resolve(),
      unhandle: () => Promise.resolve(),
      registerFileProtocol: () => {},
      interceptFileProtocol: () => {},
      isProtocolHandled: () => true,
    },
    fromPartition: (p) => makeSession(p),
    defaultSession: null,
  };
}

const session = makeSession('persist:mineradio');
session.defaultSession = session;
session.fromPartition = (p) => makeSession(p);
session.fromPath = (p) => makeSession(p);

// ---------------------------------------------------------------- BrowserWindow

const DESKTOP_WINDOW_UNSUPPORTED =
  'Mineradio Android：当前版本不支持桌面内嵌浏览器窗口（Electron BrowserWindow）。' +
  '该能力用于「汽水音乐」安全验证登录，安卓端请改用网易云 / QQ 音乐 / 酷狗 登录。';

class BrowserWindow {
  constructor(options) {
    this._options = options || {};
    this._unsupported = true;
    throw new Error(DESKTOP_WINDOW_UNSUPPORTED);
  }
}

// ---------------------------------------------------------------- 其余导出

const noop = () => {};
const noopAsync = () => Promise.resolve();

const ipcMain = {
  handle: noop, handleOnce: noop, on: noop, once: noop, off: noop,
  removeHandler: noop, removeAllListeners: noop, emit: () => false,
};

const ipcRenderer = {
  invoke: noopAsync, send: noop, sendSync: () => null,
  on: noop, once: noop, off: noop, removeListener: noop, removeAllListeners: noop,
};

const contextBridge = {
  exposeInMainWorld: (key, api) => {
    if (typeof globalThis !== 'undefined' && globalThis[key] === undefined) {
      try { globalThis[key] = api; } catch (_) {}
    }
  },
  exposeInIsolatedWorld: noop,
  executeInMainWorld: noop,
};

const clipboard = {
  readText: () => '', writeText: noop, readHTML: () => '', writeHTML: noop,
  clear: noop, availableFormats: () => [],
};

const shell = {
  openExternal: () => Promise.resolve(),
  openPath: () => Promise.resolve(''),
  showItemInFolder: noop,
  trashItem: () => Promise.resolve(),
  beep: noop,
};

const dialog = {
  showOpenDialog: () => Promise.resolve({ canceled: true, filePaths: [] }),
  showSaveDialog: () => Promise.resolve({ canceled: true, filePath: undefined }),
  showMessageBox: () => Promise.resolve({ response: 0 }),
  showErrorBox: noop,
};

const screen = {
  getPrimaryDisplay: () => ({ id: 1, bounds: { x: 0, y: 0, width: 1080, height: 1920 }, scaleFactor: 1, workAreaSize: { width: 1080, height: 1920 } }),
  getAllDisplays: () => [screen.getPrimaryDisplay()],
  getCursorScreenPoint: () => ({ x: 0, y: 0 }),
  on: noop,
};

const globalShortcut = { register: () => false, unregister: noop, unregisterAll: noop, isRegistered: () => false };

const Menu = {
  buildFromTemplate: () => ({ popup: noop, closePopup: noop }),
  setApplicationMenu: noop,
  getApplicationMenu: () => null,
};
const MenuItem = class MenuItem { constructor(o) { Object.assign(this, o || {}); } };

const Tray = class Tray {
  constructor() {}
  setToolTip() {} setImage() {} setContextMenu() {} on() {} destroy() {} popUpContextMenu() {}
};

const nativeImage = {
  createFromPath: () => ({ isEmpty: () => true, toPNG: () => Buffer.alloc(0) }),
  createEmpty: () => ({ isEmpty: () => true, toPNG: () => Buffer.alloc(0) }),
  createFromBuffer: () => ({ isEmpty: () => true, toPNG: () => Buffer.alloc(0) }),
};

const protocol = {
  handle: noop, unhandle: noop, registerSchemesAsPrivileged: noop,
  registerFileProtocol: noop, registerBufferProtocol: noop, registerStringProtocol: noop,
  interceptFileProtocol: noop, isProtocolHandled: () => true,
};

const desktopCapturer = {
  getSources: () => Promise.resolve([]),
};

const powerMonitor = {
  on: noop, once: noop, off: noop,
  getSystemIdleTime: () => 0,
  getSystemIdleState: () => 'active',
  isOnBatteryPower: () => false,
};

const webUtils = {
  getPathForFile: () => '',
};

const crashReporter = { start: noop, addExtraParameter: noop };

const nativeTheme = {
  shouldUseDarkColors: true,
  on: noop,
};

module.exports = {
  app,
  session,
  BrowserWindow,
  ipcMain,
  ipcRenderer,
  contextBridge,
  clipboard,
  shell,
  dialog,
  screen,
  globalShortcut,
  Menu,
  MenuItem,
  Tray,
  nativeImage,
  protocol,
  desktopCapturer,
  powerMonitor,
  webUtils,
  crashReporter,
  nativeTheme,
  __isMineradioAndroidStub: true,
};
