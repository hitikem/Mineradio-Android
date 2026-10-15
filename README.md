# Mineradio for Android

[![Build APK](https://github.com/hitikem/Mineradio-Android/actions/workflows/build.yml/badge.svg)](https://github.com/hitikem/Mineradio-Android/actions/workflows/build.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-26-green.svg)](#已知限制)

> 把 Windows 桌面沉浸式音乐播放器 [Mineradio](https://github.com/XxHuberrr/Mineradio)
> 原样搬到安卓手机上。**不是仿制，是同一套代码在手机上跑。**

歌词舞台、粒子视觉、电影镜头运镜、3D 歌单架、网易云 / QQ 音乐 / 酷狗 登录与搜索播放——
桌面版有的效果，手机上都有。

## 直接下载

| 你的设备 | 下载 |
| --- | --- |
| 现代手机（2017 年后基本都是 64 位） | [`Mineradio-2.2.0-android-arm64-v8a.apk`](../../releases/download/v2.2.0-android.1/Mineradio-2.2.0-android-arm64-v8a.apk) |
| 老设备（32 位） | [`Mineradio-2.2.0-android-armeabi-v7a.apk`](../../releases/download/v2.2.0-android.1/Mineradio-2.2.0-android-armeabi-v7a.apk) |
| 不确定架构 | [`Mineradio-2.2.0-android-universal.apk`](../../releases/download/v2.2.0-android.1/Mineradio-2.2.0-android-universal.apk) |

全部版本见 [Releases](../../releases)。

---

## 它是怎么做到的

Mineradio 的架构其实特别适合移植，关键在于两点：

**1. 前端是纯网页。** `public/index.html` + 2.9MB JS 模块 + 436KB CSS + three.js / GSAP，
全部是浏览器里跑的东西。重写一遍界面必然「不一样」，所以这里**一行前端代码都没改**。

**2. 后端不依赖 Electron。** `server.js` 有 270KB、91 个 `/api/*` 接口，
但只用了 `http` / `https` / `fs` / `crypto` / `tls` 这些标准库，**没有 `require('electron')`**，
也没有任何原生模块（`.node`）、`child_process` 或 worker。

于是安卓端的做法就非常直接：

```
┌──────────────────── 安卓 App（单进程）────────────────────┐
│                                                           │
│   NodeForegroundService（前台服务，负责保活）              │
│        │                                                  │
│        ├── libnode.so ──► server.js  ← 上游原文件，未改动  │
│        │      (nodejs-mobile)  监听 127.0.0.1:<随机端口>    │
│        │                                                  │
│        └── WebView ──────► http://127.0.0.1:<端口>         │
│                  ↑ 加载的就是上游那份 public/ 前端          │
│                                                           │
│   注入脚本只做适配：媒体控制桥接 + 隐藏桌面窗口按钮          │
└───────────────────────────────────────────────────────────┘
```

也就是把「Electron 主进程 + 渲染进程」这一对，换成
「内嵌 Node 运行时 + 安卓 WebView」，中间那条 HTTP 通道完全保持原样。

> 桌面上 Electron 做的事（起本地服务、开一个 Chromium 窗口加载它），
> 安卓上就是「JNI 里跑 Node、WebView 加载它」。逻辑同构，所以效果同源。

## 功能

桌面版的核心能力在手机上都可用：

- ✅ 搜索播放（网易云 / QQ 音乐 / 酷狗）
- ✅ 歌词舞台、粒子视觉、电影镜头运镜、3D 歌单架
- ✅ 二维码登录（网易云 / QQ 音乐 / 酷狗）
- ✅ 播放队列、歌单、播客、DJ 曲目节奏分析
- ✅ 通知栏 / 锁屏 / 蓝牙耳机按键控制
- ✅ 后台播放，从最近任务划掉也继续放
- ✅ 播放时屏幕常亮
- ✅ 视觉参数、自定义封面、自定义歌词等设置持久化

### 手机端额外做的适配

上游前端本身已经带了 `max-width: 420px / 480px / 540px` 等移动端断点，
所以窄屏版式是现成的。移植层在此基础上补了几处：

- 隐藏桌面窗口的最小化 / 最大化 / 关闭按钮（手机上无意义）
- 适配刘海屏安全区，锁定系统字体缩放（防止把桌面版式挤爆）
- 关掉长按系统菜单、点击高亮、滚动条等触屏噪音
- 播放 / 上一首 / 下一首按钮触摸目标不小于 44dp
- 安卓返回键：优先关弹层，没有弹层则退到后台而不是退出（音乐继续）

## 已知限制

| 限制 | 说明 |
| --- | --- |
| **汽水音乐登录不可用** | 上游用真实 Electron 窗口加载汽水音乐的安全验证页面并挂钩 `session.webRequest`。安卓端没有 Electron 主进程，`node-app/stubs/electron/` 里给了一个明确报错的兼容桩，该平台登录会失败，**其余三个平台不受影响**。要做的话需要把 Electron 的 `BrowserWindow` / `webContents` / `session` 映射到 WebView，属于独立的一块工作。 |
| **桌面专有能力不适用** | Wallpaper Engine 壁纸、完整桌面模式、桌面歌词独立窗口、系统托盘这些依赖 Windows 的能力在手机上不生效；前端对 `window.desktopWindow` 的每一处调用都有存在性判断，缺失时会自动降级，不影响主流程。 |
| **无本地音乐导入** | 上游的本地音乐库依赖 Electron 的文件对话框与 `music-metadata` 扫描，暂未移植。 |
| **需要 Android 8.0+** | `minSdk 26`。 |

## 下载安装

到 [Releases](../../releases) 下载 APK：

- 现代手机（2017 年后基本都是 64 位）：`Mineradio-2.2.0-android-arm64-v8a.apk`
- 老设备（32 位）：`Mineradio-2.2.0-android-armeabi-v7a.apk`
- 不确定架构：`Mineradio-2.2.0-android-universal.apk`（体积较大，含全部架构）

首次启动需要展开约 42MB 的运行时资源（近 3000 个文件），**会有几秒钟的进度条**，
之后就都是秒开。这是正常的，不是卡住了。

> APK 使用 debug 密钥签名（未申请正式签名证书），部分系统可能提示风险，选择「仍要安装」即可。

## 自行构建

### 环境要求

- JDK 17
- Android SDK：platform 35、build-tools 35.0.0、**NDK 26.1.10909125**、CMake 3.22.1
- Node.js 18+（只用于装依赖和跑同步脚本）

### 步骤

`node-app/` 里的上游运行时文件**已经提交在仓库里**，直接构建即可：

```bash
# 1. 安装 Node 生产依赖（约 33MB，只装 server.js 真正用到的）
bash scripts/fetch-node-app-deps.sh

# 2. 下载 nodejs-mobile 的 libnode.so 到 jniLibs（约 180MB，不入库）
bash scripts/fetch-nodejs-mobile.sh

# 3. 配置 SDK 路径
echo "sdk.dir=/你的/Android/sdk" > local.properties

# 4. 编译
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`，默认按 ABI 拆成多个包并附带一个 universal 包。

`node-app/` 会在构建时被自动打成 `assets/mineradio-node-app.zip`，
由 Gradle 任务 `prepareNodeAppZip` 完成，不需要手动处理。

### 国内网络环境

`dl.google.com` 和 Maven Central 在国内经常连不上，Gradle 会报
`Remote host terminated the handshake` 之类的 TLS 错误。用仓库自带的镜像脚本换源：

```bash
./gradlew assembleRelease --init-script scripts/mirror-init.gradle
```

如果处在代理环境，也可以直接给 Gradle 指定代理：

```bash
./gradlew assembleRelease \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
  -Dhttp.proxyHost=127.0.0.1  -Dhttp.proxyPort=7890
```

### 需要跟进上游更新时

```bash
git clone --depth 1 https://github.com/XxHuberrr/Mineradio.git upstream/Mineradio
bash scripts/sync-node-app.sh upstream/Mineradio
```

脚本只复制服务端真正会加载的文件，桌面专有代码（`desktop/`、`scripts/`、`tests/`）不参与。

## 技术细节

想了解移植过程中的具体判断（为什么不用 Termux 跑 node、为什么覆盖式解压、
为什么绕开 nodejs-mobile 的 12MB 头文件包、媒体控制怎么桥接），
见 [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)。

## 致谢

- **XxHuberrr** —— Mineradio 作者。整个前端、后端、视觉系统都是他的作品，
  本仓库只是换了一层运行容器。
- **emily** —— 上游 README 中提到的早期视觉底层想法与 `emily` 预设共创者。
- [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) —— 提供了安卓上的 Node 运行时。

## 许可

**GPL-3.0**，与上游一致。详见 [LICENSE](./LICENSE) 与 [NOTICE.md](./NOTICE.md)。

本移植版为非官方作品，与原作者无隶属关系。
