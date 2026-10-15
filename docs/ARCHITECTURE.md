# 移植架构与关键决策

本文记录把 Mineradio 从 Windows Electron 桌面应用搬到安卓时，每一个需要判断的地方
以及为什么这么选。给后来想改这个项目的人看。

---

## 一、先摸清上游的架构

移植方案完全由上游架构决定，所以第一步是把它的运行模型搞清楚：

| 观察 | 结论 |
| --- | --- |
| `package.json` 的 `main` 是 `desktop/main.js`，依赖里有 `electron` | 是 Electron 应用 |
| `desktop/main.js` 有 248KB | 主进程逻辑很重，但那是**桌面专有**的（窗口、托盘、壁纸引擎） |
| `server.js` 有 270KB，`grep require('electron') server.js` → **0 处** | **后端是纯 Node**，这是移植可行的根本前提 |
| 顶层 require 只有 `http/https/fs/path/crypto/tls/url` | 没有任何原生依赖 |
| 全仓库搜 `child_process`、`worker_threads`、`.node'` → 无 | 不需要派生进程，也不需要编译原生模块 |
| `server.js` 末尾 `server.listen(PORT, HOST)`，然后前端从同源加载 | 前后端靠 HTTP 通信，中间这条缝就是移植的切入点 |
| 前端 91 个 `/api/*` 调用全是相对路径，无 WebSocket | 只要 WebView 加载同一个 origin，接口调用不用改 |
| 前端对 `window.desktopWindow` 的每一处调用都带 `typeof === 'function'` 判断 | 缺失时会优雅降级，**网页版本身就是一种受支持的运行形态** |

第 7 条尤其重要：作者显然自己也在浏览器里跑过这套前端，所以「不提供 Electron 桥」
不是一种 hack，而是沿着设计意图走。

## 二、运行容器的选择

要把 Electron 那套「本地服务 + Chromium 窗口」在安卓上复现，候选方案有几个：

| 方案 | 为什么不选 |
| --- | --- |
| 用 Kotlin/Java 重写 `server.js` | 270KB 单文件、91 个接口、聚合 4 个音乐平台。重写等于重做项目，且必然与上游脱节 |
| 打包 `node` 可执行文件用 `Runtime.exec` 跑 | Android 10 起禁止执行应用数据目录里的文件（W^X）。除非把 targetSdk 降到 28，不可接受 |
| 用 Termux / UserLAnd 这类外部环境 | 需要用户额外装软件、手动配环境，不是「一个 App」 |
| **把 Node 作为动态库嵌进 App 进程** | ✅ 采用 |

最终用 [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile)：它提供编译好的
`libnode.so`（Node 18.20.4），通过 JNI 在应用进程内的独立线程里跑 `node::Start`。

这样 Electron 的「主进程」换成了「应用进程里的一个 Node 线程」，
「渲染进程」换成了「同一个 App 里的 WebView」，中间仍是 HTTP。

## 三、几个具体的技术判断

### 1. 绕开 nodejs-mobile 的 12MB 头文件包

官方文档让你 `#include <node.h>`，但那需要引入 `include/` 目录：**12MB、634 个文件**
（大头是 V8 的几十个头文件）。这些头文件唯一的作用是让我们能调用 `node::Start`。

但 `node::Start` 在 `libnode.so` 里是一个**已定义的全局符号**，名字固定：

```
_ZN4node5StartEiPPc        // int node::Start(int, char**)
bind = GLOBAL, type = FUNC, shndx = 12（已定义）, size = 772
```

（签名来自 `node.h:318`：`NODE_EXTERN int Start(int argc, char* argv[]);`）

所以直接用 asm 标签绑定即可，仓库里不用放任何头文件：

```cpp
extern "C" int node_start_entry(int argc, char** argv) asm("_ZN4node5StartEiPPc");
```

编译期就能验证对不对——名字错了会直接链接失败。

### 2. Electron 兼容桩而不是改上游代码

`server.js` 本身不 require electron，但它的依赖链里有一处：

```
server.js → qishui-qr-login.js → qishui-auth-v6.js → require('electron')   ← 顶层 require
```

**不加处理的话，服务端根本起不来。** 处理方式是在 `node-app/stubs/electron/` 放一个桩包，
通过 `package.json` 的 `"electron": "file:./stubs/electron"` 装进 `node_modules`：

- `app` / `session` / 各类生命周期钩子 → 可用实现或安全空实现
- `BrowserWindow` → **构造时抛出明确错误**，而不是返回一个假对象

这样汽水音乐登录会得到一个可读的失败信息，其他一切照常。
如果返回假对象，上游会在 `win.loadURL(...)` 上以更难排查的方式挂掉。

### 3. 覆盖式解压，绝不删目录

上游把登录态写在**自己的目录**里：

```js
const DEFAULT_COOKIE_FILE = path.join(__dirname, '.cookie');
const DEFAULT_QQ_COOKIE_FILE = path.join(__dirname, '.qq-cookie');
// 还有 .kugou-cookie / .qishui-cookie / data/
```

如果版本升级时「先删目录再解压」，用户每次更新都会掉登录。
所以 `AssetInstaller` 只把压缩包里的文件**覆盖**上去，目录里多出来的运行期文件自然保留。

### 4. 资源打成 zip 而不是直接塞 assets

`node-app/` 有 **2989 个文件**（其中 `node_modules` 占 2826 个）。
逐文件遍历 assets 解压，首次启动要十几秒。

打成一个 zip 后只需顺序读一个流，快一个数量级，APK 也更小。
配合 `androidResources { noCompress += "zip" }`，还能用 `openFd()` 拿到总长度做进度条。

### 5. WebView 归 Service 所有，不归 Activity

这是「后台播放」能不能成立的关键。

如果 WebView 归 Activity：用户一划掉最近任务 → Activity 销毁 → WebView 销毁 → 音乐断。

所以 WebView 用带主题的 **Service Context** 创建，由 `NodeForegroundService` 持有；
Activity 只是通过 Binder 借来挂到自己布局上，`onDestroy` 时**只摘不销毁**。
页面和音频继续存活，划掉任务后仍在播放。

### 6. 媒体控制靠注入脚本桥接，不改上游前端

WebView 里的 HTML5 `<audio>` 不会自动产生系统媒体通知。要做通知栏 / 锁屏 / 耳机按键控制，
需要一个真正的 `MediaSession`。桥接方式是：

**原生 → 页面**：注入 `window.MineradioAndroid`，提供 `play/pause/next/previous/seekTo`，
内部调用上游已有的全局函数（`togglePlay()` / `nextTrack(true)` / `prevTrack(true)`）
和 DOM 元素（`#play-btn` / `#next-btn` / `#prev-btn`）。

**页面 → 原生**：注入脚本包装 `HTMLMediaElement.prototype.play`，
跟踪「当前活跃的音频元素」，再从 DOM 读曲目信息：

| 信息 | 来源 |
| --- | --- |
| 标题 | `#control-title-text` |
| 歌手 | `#control-artist` |
| 封面 | `#control-cover` 的 `background-image` |

状态通过 `window.MineradioNative.onState(json)` 推给原生（节流 900ms），
原生侧驱动 `MediaSession` + `MediaStyle` 通知。

**这样做的代价是不碰上游任何一个 JS 文件**，后续同步上游新版本不会产生冲突。

### 7. 不定义 `window.desktopWindow`

看起来提供一个大而全的桥接对象更「完整」，但风险更高：
上游有些地方写的是 `window.desktopWindow.getState()` 而非先判断 `typeof`，
提供一个残缺对象反而可能触发 `TypeError`。

保持它为 `undefined`，所有 `window.desktopWindow && ...` 守卫直接短路——
这正是「在浏览器里打开」的行为，也是作者自己支持的行为。原生能力走独立的
`window.MineradioAndroid` 命名空间，两边互不干扰。

### 8. 放开明文流量

内嵌服务在 `127.0.0.1`，必须允许明文。但 **base-config 也放开了**，原因是：
第三方音源（网易云等）的封面 CDN 至今大量使用 `http://`，且上游存在
直接使用原始封面 URL 的渲染路径（`coverUrlWithSize()` 不做代理）。
Android 9 起默认禁止明文流量，不放开的表现是「封面大片空白」，在真机上极难定位。

音频流本身统一走 `/api/audio?url=...` 由本地服务代理，不依赖明文直连。

### 9. 绑定 127.0.0.1 而不是 0.0.0.0

上游默认 `HOST = process.env.HOST || '0.0.0.0'`。桌面上无所谓，手机上
意味着同一 WiFi 下任何人都能访问你的播放器（包括登录态相关接口）。
所以启动时显式传 `HOST=127.0.0.1`。

### 10. 端口动态分配

不用固定的 3000，而是从 17321 起扫描空闲端口。避免与手机上其他应用冲突，
也避免用户装了两个版本时互相抢占。

## 四、启动流程

```
MainActivity.onCreate
   ├─ NodeForegroundService.start()   ← 立刻进前台，防止被系统回收
   └─ bindService()

Service.bootstrap()  （工作线程）
   ├─ AssetInstaller.isUpToDate()?  否 → 解压 zip 到 filesDir/node-app/
   ├─ NodeRunner.start()
   │     ├─ 找空闲端口
   │     ├─ nativeStartNode(projectDir, entry, env[], args[])
   │     │     ├─ setenv(PORT/HOST/HOME/TMPDIR/NODE_PATH/...)
   │     │     ├─ pipe() + dup2() 把 stdout/stderr 接进管道
   │     │     ├─ 读线程 → logcat（否则原生线程的 stdout 进 /dev/null，什么都看不到）
   │     │     └─ pthread_create → chdir + node::Start()
   │     └─ 轮询 http://127.0.0.1:<port>/ 直到 200
   ├─ 主线程建 WebView（Service Context + 主题）
   │     ├─ evaluateJavascript(EARLY)   ← 挂钩子必须先于上游脚本
   │     └─ loadUrl(baseUrl)
   └─ onPageFinished → evaluateJavascript(LATE) → phase = READY
```

首次启动会经历「解压 → 起服务 → 加载页面」三个阶段，界面上是带百分比的进度条。

## 五、后续可以做的事

按价值排序：

1. **汽水音乐登录**：把 Electron 的 `BrowserWindow` / `webContents.executeJavaScript` /
   `session.webRequest` 映射到安卓 WebView，做成一个迷你 Electron shim。
   这样 `qishui-auth-v6.js` 可以几乎不改地跑起来。
2. **本地音乐导入**：接安卓的 `ACTION_OPEN_DOCUMENT` + SAF，配合 `music-metadata` 扫描。
3. **音频输出设备选择**：上游有 `/api/audio-output*` 相关界面，接安卓 `AudioManager`。
4. **锁屏歌词**：`MediaMetadata` 已经能带封面，可以再补 `METADATA_KEY_LYRICS`。
5. **降低首次启动耗时**：把 zip 换成预解压到 `noBackupFilesDir` 的增量方案，
   或者用 `mmap` 直接从 APK 读（需要改 Node 的 fs，成本高）。
