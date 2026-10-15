# NOTICE —— 版权与来源声明

## 本仓库与上游的关系

**Mineradio-Android 是 [Mineradio](https://github.com/XxHuberrr/Mineradio) 的非官方安卓移植版**，
由社区爱好者制作，与原作者 XxHuberrr 无隶属关系，也未获得其背书。

上游 Mineradio 是一款 Windows 桌面沉浸式音乐播放器，采用 **GPL-3.0** 授权。
本仓库作为其衍生作品，同样以 **GPL-3.0** 发布（见 [LICENSE](./LICENSE)）。

```
Copyright (C) 2026 XxHuberrr            —— 上游 Mineradio 原作者
Copyright (C) 2026 Mineradio-Android 移植者 —— 安卓移植部分
```

## 上游代码的使用方式

本仓库 `node-app/` 目录下的以下文件**直接来自上游仓库，未作修改**：

| 文件 / 目录 | 说明 |
| --- | --- |
| `server.js` | 本地 HTTP 服务，实现 91 个 `/api/*` 接口 |
| `dj-analyzer.js` | 播客 / DJ 曲目节奏分析 |
| `kugou-api.js` | 酷狗音乐接入 |
| `qq-vip-api.js` | QQ 音乐接入 |
| `qishui-api.js`、`qishui-auth-v6.js`、`qishui-qr-login.js` | 汽水音乐接入 |
| `spotify-api.js` | Spotify 接入 |
| `cuefield/` | Cuefield AutoMix 转场规划 |
| `qishui-auth-v6/`、`qishui-audio-decryptor/` | 汽水音乐运行时资源与解密 |
| `public/` | 完整前端（页面、样式、视觉模块、three.js/GSAP 等 vendor） |

同步方式见 `scripts/sync-node-app.sh`，同步记录写在 `node-app/UPSTREAM_SYNC.json`。

**移植方新增的内容**（原仓库没有）：

- `app/` —— 全部安卓原生代码（Kotlin 外壳 + JNI 桥接）
- `node-app/main.js` —— 安卓端 Node 启动入口
- `node-app/stubs/electron/` —— Electron 兼容桩
- `scripts/`、`docs/`、构建配置

## 上游自身的第三方依赖

上游 Mineradio 使用了以下项目，版权归各自作者所有，详见
[docs/UPSTREAM_NOTICE.md](./docs/UPSTREAM_NOTICE.md)：

- Electron（本移植版不包含，改由安卓 WebView + 内嵌 Node 替代）
- Three.js、GSAP、music-tempo
- NeteaseCloudMusicApi
- mpg123-decoder、music-metadata、qrcode

## 本移植版新增的第三方组件

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) v18.20.4 | 安卓上的 Node.js 运行时（`libnode.so`） | MIT |
| [Node.js](https://nodejs.org/) 18.20.4 | 同上，nodejs-mobile 基于其构建 | MIT |
| AndroidX / Kotlin | 安卓 UI 与语言支持 | Apache-2.0 |

`libnode.so` 不提交进仓库，由 `scripts/fetch-nodejs-mobile.sh` 从官方 Release 下载。

## 第三方音乐平台声明

本项目**不是**网易云音乐、QQ 音乐、腾讯音乐娱乐集团、酷狗音乐或汽水音乐的官方客户端，
与上述任何平台均无隶属关系。

仓库中的平台接入能力来自上游 Mineradio，仅用于个人学习与本地客户端体验。
请遵守对应平台的用户协议、版权规则与会员权益规则。
本项目不提供绕过付费、绕过会员、破解音质或重新分发音乐内容的能力。

## 名称与视觉

MR Logo、Mineradio 名称、界面视觉设计与原创视觉表达归原作者 XxHuberrr 所有。
本移植版沿用这些名称与视觉仅为标识来源，不代表作者认可本移植版。
