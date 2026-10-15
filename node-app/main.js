'use strict';

/**
 * Mineradio Android —— 内嵌 Node 服务入口
 *
 * 职责：把上游 Electron 桌面应用依赖的运行环境在安卓上补齐，然后原样拉起 server.js。
 * 上游 server.js 是纯 Node 实现（http/https/fs/crypto/tls），没有任何 Electron 依赖，
 * 因此这里只需要处理「路径、日志、环境变量」三件事。
 */

const fs = require('fs');
const os = require('os');
const path = require('path');

const PROJECT_DIR = __dirname;

// ------------------------------------------------------------------ 可写目录

const USER_DATA =
  process.env.MINERADIO_ANDROID_USER_DATA || path.join(os.tmpdir(), 'mineradio-android');
const LOG_DIR = path.join(USER_DATA, 'logs');
const CACHE_DIR = path.join(USER_DATA, 'cache');
const TMP_DIR = path.join(USER_DATA, 'tmp');

for (const dir of [USER_DATA, LOG_DIR, CACHE_DIR, TMP_DIR]) {
  try {
    fs.mkdirSync(dir, { recursive: true });
  } catch (_) { /* 已存在或不可建，后续按需兜底 */ }
}

// Node 与依赖库会读这些变量来决定把数据放哪
process.env.HOME = USER_DATA;
process.env.USERPROFILE = USER_DATA;
process.env.TMPDIR = TMP_DIR;
process.env.TMP = TMP_DIR;
process.env.TEMP = TMP_DIR;
process.env.XDG_CACHE_HOME = CACHE_DIR;
process.env.XDG_CONFIG_HOME = path.join(USER_DATA, 'config');
process.env.XDG_DATA_HOME = path.join(USER_DATA, 'data');
process.env.NODE_ENV = process.env.NODE_ENV || 'production';

// 保证 require 从项目目录解析依赖
if (!process.env.NODE_PATH || process.env.NODE_PATH.indexOf(PROJECT_DIR) < 0) {
  process.env.NODE_PATH = [path.join(PROJECT_DIR, 'node_modules'), process.env.NODE_PATH]
    .filter(Boolean)
    .join(path.delimiter);
}
try {
  require('module').Module._initPaths();
} catch (_) {}

// 必须在 require server.js 之前切好工作目录：server.js 用 __dirname 定位 public/
try {
  process.chdir(PROJECT_DIR);
} catch (_) {}

// ------------------------------------------------------------------ 日志

const LOG_FILE = path.join(LOG_DIR, 'mineradio-node.log');
const LOG_MAX_BYTES = 2 * 1024 * 1024;

// 日志轮转：超过 2MB 就滚动一次，避免长期运行把手机存储写满
try {
  const st = fs.statSync(LOG_FILE);
  if (st.size > LOG_MAX_BYTES) {
    fs.renameSync(LOG_FILE, LOG_FILE + '.1');
  }
} catch (_) { /* 首次运行没有日志文件，正常 */ }

let logStream = null;
try {
  logStream = fs.createWriteStream(LOG_FILE, { flags: 'a' });
} catch (_) {
  logStream = null;
}

function writeLog(level, args) {
  const line =
    '[' + new Date().toISOString() + '][' + level + '] ' +
    args
      .map((a) => {
        if (typeof a === 'string') return a;
        if (a instanceof Error) return a.stack || a.message;
        try {
          return JSON.stringify(a);
        } catch (_) {
          return String(a);
        }
      })
      .join(' ') +
    '\n';

  // 1) 走 stdout/stderr —— 原生层已经把它重定向到 logcat，方便 adb 直接看
  // 2) 同时落盘，方便在 App 里「导出日志」排查问题
  if (level === 'ERROR' || level === 'WARN') {
    process.stderr.write(line);
  } else {
    process.stdout.write(line);
  }
  if (logStream) {
    try { logStream.write(line); } catch (_) {}
  }
}

const rawConsole = {
  log: console.log.bind(console),
  info: console.info.bind(console),
  warn: console.warn.bind(console),
  error: console.error.bind(console),
  debug: console.debug.bind(console),
};

console.log = (...a) => writeLog('INFO', a);
console.info = (...a) => writeLog('INFO', a);
console.warn = (...a) => writeLog('WARN', a);
console.error = (...a) => writeLog('ERROR', a);
console.debug = (...a) => writeLog('DEBUG', a);
console.trace = (...a) => writeLog('TRACE', a);

process.on('uncaughtException', (err) => {
  writeLog('FATAL', ['未捕获异常：', err]);
  // 不退出：单次请求出错不应该让整个播放器挂掉
});

process.on('unhandledRejection', (reason) => {
  writeLog('FATAL', ['未处理的 Promise 拒绝：', reason]);
});

// ------------------------------------------------------------------ 启动

// 运行时画像：排查「同样的代码在电脑上好好的、手机上起不来」时，第一眼要看这个
const runtimeInfo = {
  node: process.version,
  execPath: process.execPath,
  arch: process.arch,
  platform: process.platform,
  // Node 20.19+ / 22+ 才能用 require() 加载 ESM 包；nodejs-mobile 是 Node 18，不能。
  supportsRequireEsm: (() => {
    const [major, minor] = process.versions.node.split('.').map(Number);
    return major > 20 || (major === 20 && minor >= 19);
  })(),
};

writeLog('INFO', [
  'Mineradio Android 内嵌 Node 运行时启动',
  'node=' + runtimeInfo.node,
  'arch=' + runtimeInfo.arch,
  'pid=' + process.pid,
  'require(esm)=' + runtimeInfo.supportsRequireEsm,
  'projectDir=' + PROJECT_DIR,
  'userData=' + USER_DATA,
]);

// Node 18 加载不了纯 ESM 依赖。这里**提前**把话说清楚：
// 否则只会看到一句 ERR_PACKAGE_PATH_NOT_EXPORTED，很难联想到是运行时版本问题。
if (!runtimeInfo.supportsRequireEsm) {
  const esmOnly = ['music-metadata', 'file-type', 'strtok3', 'token-types'];
  for (const name of esmOnly) {
    try {
      const resolved = require.resolve(name + '/package.json');
      const meta = JSON.parse(fs.readFileSync(resolved, 'utf8'));
      const hasRequireCondition = (function scan(node) {
        if (!node || typeof node !== 'object') return false;
        if (Object.prototype.hasOwnProperty.call(node, 'require')) return true;
        return Object.values(node).some(scan);
      })(meta.exports);
      if (meta.type === 'module' && !hasRequireCondition) {
        writeLog('ERROR', [
          `依赖「${name}@${meta.version}」是纯 ESM 包，当前 Node ${runtimeInfo.node} 无法用 require() 加载。`,
          '请把该依赖固定到仍提供 CommonJS 的版本（music-metadata 用 dist-tags.cjs 指向的 7.14.0），',
          '运行 scripts/scan-node18-incompatible.py 可以看到完整清单。',
        ]);
      }
    } catch (_) {
      // 没装这个包就算了，不是错误
    }
  }
}

// 让上游模块能感知到「这不是桌面 Electron 环境」
global.__MINERADIO_ANDROID__ = true;
global.__MINERADIO_PROJECT_DIR__ = PROJECT_DIR;
global.__MINERADIO_USER_DATA__ = USER_DATA;

try {
  require('./server.js');
  writeLog('INFO', [
    'server.js 已加载，监听 ' +
      (process.env.HOST || '0.0.0.0') + ':' + (process.env.PORT || 3000),
  ]);
} catch (err) {
  writeLog('FATAL', ['server.js 启动失败：', err]);
  writeLog('FATAL', [
    '提示：若报错是 ERR_PACKAGE_PATH_NOT_EXPORTED / 无法 require 某个包，' +
      '通常是该依赖只有 ESM 版本，而内嵌运行时是 Node ' + runtimeInfo.node +
      '（不支持 require(esm)）。',
  ]);
  // 让原生侧能感知到失败（轮询会超时，同时日志里能看到原因）
  throw err;
}

module.exports = { PROJECT_DIR, USER_DATA, LOG_FILE };
