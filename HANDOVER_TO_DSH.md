# Mineradio-Android 工作交接文档

> 生成时间：2026-10-15  
> 交接对象：dsh（DeepSeek Harness）  
> 项目仓库：https://github.com/hitikem/Mineradio-Android

---

## 零、2026-10-15 深夜续查结论（**请先读这一节，它推翻了下面的第四节**）

### 真正的根因：`music-metadata@11` 是纯 ESM 包，Node 18 无法 `require`

内嵌运行时是 nodejs-mobile 的 **Node 18.20.4**，而 Node 18 的 `require()`
**不支持加载 ESM**。`node-app/node_modules/music-metadata` 当时是 11.14.0，
它是 `"type": "module"` 且 `exports` 里**只有 `import` 条件**，于是：

```
NeteaseCloudMusicApi/module/cloud.js:1
    const mm = require('music-metadata')        ← 模块顶层 require
→ Error [ERR_PACKAGE_PATH_NOT_EXPORTED]:
    No "exports" main defined in .../music-metadata/package.json
→ server.js 在 require 阶段抛错
→ Node 进程退出，server.listen() 从未执行
→ Kotlin 侧端口探测全部失败
→ 界面显示「本地音乐服务启动失败」
```

链条上每一环都有实测证据：

| 验证 | 命令/环境 | 结果 |
|---|---|---|
| Node 18.20.4 跑 `node-app/main.js` | 本机 Windows 复现 | **失败**：`ERR_PACKAGE_PATH_NOT_EXPORTED: music-metadata` |
| Node 22 跑同一份代码 | 同上 | 成功，HTTP 200（与桌面 Electron 内置 Node 版本一致） |
| 把 `music-metadata` 换成 7.14.0（官方 `dist-tags.cjs` 指向的最后一个 CJS 版） | Node 18.20.4 | **成功**：首页 HTTP 200（129327 字节），`/api/search` 也是 200 |

**为什么之前一直没查到**：桌面 Electron 用的是 Node 22（支持 `require(esm)`），
所以上游和本地用新 Node 测试都正常，只有装到手机上才炸。
这也解释了为什么 `libnode.so` 符号、16KB 对齐、JNI 绑定全都查不出问题 —— 它们本来就没问题。

### 已做的修复

1. `node-app/package.json`：`music-metadata` 固定为 `^7.14.0`（dependencies 与 overrides 一起改）
2. 重装 `node-app/node_modules`，整条依赖链回到 CommonJS
   （file-type 16.5.4 / strtok3 6.3.0 / token-types 4.2.1 / media-typer 1.1.1，全部 CJS）
3. `AppConfig.ASSET_REVISION` 1 → 2：让老用户升级后**重新解压** node-app，
   否则旧设备上那份 ESM 版依赖会一直留着
4. `node-app/main.js`：启动时记录 `node` 版本与 `require(esm)` 能力，
   并在检测到 ESM-only 依赖时直接打出人话报错
5. `scripts/scan-node18-incompatible.py`：扫描全量依赖里所有 Node 18 加载不了的包
6. 该脚本已接入 Gradle（`checkNode18Compat` → `prepareNodeAppZip` 的前置任务），
   **以后再出现不兼容依赖会直接构建失败**，不会等到装到手机上才发现

### 关于第四节「16KB 补丁破坏运行时」的判断

那个判断是**错的**，已被推翻：

- 当前 `app/src/main/jniLibs/arm64-v8a/libnode.so` 的 SHA256 与参考项目
  `app/src/main/cpp/libnode/arm64-v8a/libnode.so` **逐字节一致**
  （`7C907316BEB6E78E34495926C9AC1BEFE079369B14650D508EA812929258250C`，62475584 字节），
  就是 nodejs-mobile 18.20.4 的原始库，**没有任何 16KB 补丁痕迹**（`p_align=0x1000`）。
- 用户机型是红米 K80 + Android 17，**4KB 页内核**，`p_align=4096` 本来就合法。
- 参考项目那套「已跑通」的 native 启动方式（新线程里调 `node::Start`、重定向
  stdout/stderr、`setenv` 后启动）与本项目**结构完全一致**，不存在 ABI 绑定差异。

### 仍存在的一个真实缺口（**尚未修复**）

`arm64-v8a` 的 `libnode.so` 目前是 **4KB 对齐**，在 **16KB 内存页**机型（Pixel 8/9、
部分新旗舰、选择 16KB 模式的小米新机）上会被动态链接器直接拒绝加载 ——
即 `README.md` 里宣称已修复的 16KB 问题，**在当前构建里其实是回退状态**。

- `armeabi-v7a` / `x86_64` 已是 16KB（0x4000）
- `arm64-v8a` 是 4KB（0x1000）← 唯一有问题的那个
- v2.2.0-android.5 的日志会在报告开头打印**内存页大小**，16KB 机型会打 16384，
  一眼就能确认是否命中

修法：用 `scripts/patch-elf-16kb.py` 重新给 arm64 那份打补丁（脚本自带同余/等价性校验），
或改用官方 16KB 对齐的 nodejs-mobile 构建。**4KB 机型（含红米 K80）不受影响**，
所以这不阻塞本次复测。

### 新增的诊断能力（v2.2.0-android.5）

启动失败时界面会多出「**查看启动日志**」按钮，进去是合并后的诊断报告
（Kotlin 启动日志 + 原生 Node 诊断 + Node/server.js 日志），可截图、可分享。
详见 `docs/BOOT_DIAGNOSTICS.md`。

日志位置（应用私有目录，无需权限）：

```
files/userdata/logs/
├── boot.log                Kotlin 侧启动全过程
├── native-boot.log         原生层诊断（node::Start 返回码 / 信号 / 库路径 / 页大小）
├── mineradio-node.log      Node 侧日志（main.js 会记录 Node 版本与 require(esm) 能力）
└── mineradio-diagnose.txt  三者合并的报告
```

---

## 一、项目背景

**Mineradio-Android** 是桌面端「粒子音乐可视化播放器」Mineradio 的 Android 移植版本。

- **前端**：HTML5 + WebGL（粒子歌词舞台、3D 歌单架、可视化效果）
- **后端**：内嵌 Node.js（通过 nodejs-mobile 的 `libnode.so`）提供 HTTP 服务 + 音乐平台 API 代理
- **渲染**：Android WebView 加载本地 `http://127.0.0.1:<port>`
- **架构**：Node 直接服务静态文件 + API（端口由 Kotlin 侧随机选取）
- **参考项目**：https://github.com/Timingyyds/Mineradio-Android（已跑通，但架构不同：Kotlin HTTP 服务器 8800 + Node API 后端 3000）
- **上游源码**：https://github.com/XxHuberrr/Mineradio-paused（桌面端完整源码，长期停更，Android 版的 `node-app/` 即来源于此）

---

## 二、当前状态

| 项 | 状态 |
|---|---|
| 最新版本 | **v2.2.0-android.4**（build 4） |
| 上次发布 | https://github.com/hitikem/Mineradio-Android/releases/tag/v2.2.0-android.4 |
| 核心问题 | **Node 服务启动后立即退出**，用户看到「本地音乐服务启动失败」 |
| 16KB 对齐 | 已处理（但可能引入了新问题） |
| 用户设备 | 小米/红米（arm64-v8a，4KB 页内核，截图命名格式确认） |

---

## 三、已做的工作（按时间线）

### 1. 环境搭建
- 安装 Android SDK（API 35）、NDK r27、CMake 3.22.1
- 安装 Temurin OpenJDK 17（构建用）
- 配置 Gradle、Git、GitHub CLI（gh）

### 2. 16KB 页对齐修复（v2.2.0-android.2 / .3）
- 问题：Android 15+ 部分机型使用 16KB 内存页，旧 NDK 构建的 `.so` 文件 `p_align=4096` 会导致 `dlopen` 失败
- 修复：编写 `scripts/patch-elf-16kb.py`，将 `libnode.so`、`libc++_shared.so`、`libmineradio_node.so` 的 ELF LOAD 段对齐改为 16KB
- 同时升级 NDK 到 r27（默认 16KB 构建）
- 加入 CI 静态校验脚本 `scripts/verify-apk-16kb.py`

### 3. Node 启动问题排查（v2.2.0-android.4）
- **现象**：应用能打开（`.so` 加载成功），但 `NodeRunner.start()` 返回 null，提示「本地音乐服务启动失败」
- **排查过程**：
  1. 确认 `libnode.so` 中 `node::Start` 符号（`_ZN4node5StartEiPPc`）存在且为 GLOBAL
  2. 确认 `libmineradio_node.so` 正确声明了 `DT_NEEDED` 依赖和 PLT 重定位
  3. 拉取参考项目源码，对比 native 层实现：
     - 参考项目使用 `node.h` 头文件直接调用 `node::Start`
     - 我的项目使用 `asm("_ZN4node5StartEiPPc")` 直接绑定符号
     - 参考项目包含 `rn-bridge` 模块（`NODE_MODULE_LINKED`）用于 Kotlin ↔ Node 通信
     - 参考项目有 `registerNodeDataDirPath`、`registerBridgeCb` 等初始化步骤
  4. 发现关键差异：**我的 `libnode.so` 经过 16KB ELF 补丁后大小增加 12KB，而参考项目使用的是原始未修改版本**
  5. 决定：用参考项目的原始 `libnode.so` 替换我的补丁版本，重新构建发布

---

## 四、遇到的 Bug 与未解问题

### Bug #1：16KB 对齐补丁可能破坏运行时
- **现象**：补丁后的 `libnode.so` 能通过所有静态校验（结构、同余、等价性、4KB 兼容性），但 Node 启动后立即退出
- **怀疑原因**：虽然 ELF program headers 被正确修改，但 Node.js/V8 内部可能通过某些方式（如解析自身 ELF section headers、硬编码偏移、或 unwind 表）依赖原始文件布局
- **当前处理**：v2.2.0-android.4 已换回参考项目的原始 `libnode.so`（4KB 对齐），等待用户验证

### Bug #2：Node 启动失败（根本原因仍未确认）
- **如果 v2.2.0-android.4 仍然失败**，可能原因：
  1. 我的 JNI 绑定方式（`asm` 标签）与 `node::Start` 的调用约定/ABI 存在细微不匹配
  2. 缺少 `rn-bridge` 模块：nodejs-mobile 的 `libnode.so` 可能期望 `rn-bridge` 在启动时注册
  3. 缺少 `registerNodeDataDirPath`：nodejs-mobile 的 Node 运行时可能需要数据目录注册
  4. `main.js` 或 `server.js` 在 Android 上执行出错（但无 logcat 日志确认）

### Bug #3：不同架构的 `libnode.so` 来源不一致
- 参考项目只提供了 `arm64-v8a` 的 `libnode.so`
- 我的项目中 `armeabi-v7a` 和 `x86_64` 仍使用 nodejs-mobile 官方下载的版本（且被 16KB 补丁过）
- **建议**：如果确定补丁版本有问题，应从 nodejs-mobile Release 重新下载所有 ABI 的原始版本，或统一只支持 `arm64-v8a`

---

## 五、关键文件位置

```
C:/Users/Administrator/WorkBuddy AI/2026-10-15-13-13-24/Mineradio-Android/
├── app/src/main/cpp/
│   ├── CMakeLists.txt              # CMake 配置（定义 libmineradio_node.so）
│   └── mineradio_node.cpp          # JNI 桥接层（调用 node::Start）
├── app/src/main/jniLibs/
│   └── arm64-v8a/libnode.so        # ★ 当前为参考项目原始版本
├── app/src/main/java/com/mineradio/android/
│   ├── NodeRunner.kt               # Node 启动封装（JNI 调用、端口探测、就绪轮询）
│   ├── NodeForegroundService.kt    # 前台服务（持有 WebView + Node 生命周期）
│   ├── MainActivity.kt             # Activity（绑定服务、挂载 WebView）
│   └── AssetInstaller.kt           # 解压 assets/mineradio-node-app.zip
├── node-app/
│   ├── main.js                     # Node 入口（设置环境变量、require server.js）
│   └── server.js                   # HTTP 服务器 + API 代理（上游代码）
├── scripts/
│   ├── patch-elf-16kb.py           # ELF 16KB 对齐补丁脚本
│   ├── patch-libnode-16kb.sh       # 批量修补 libnode.so
│   ├── verify-apk-16kb.py          # CI 静态校验脚本
│   └── fetch-nodejs-mobile.sh      # 下载 nodejs-mobile 预编译库
└── release-assets/
    └── Mineradio-2.2.0-android-arm64-v8a.apk
```

---

## 六、上游源码仓库（XxHuberrr/Mineradio-paused）

这是桌面端 Mineradio 的**完整源代码**，Android 移植版的 `node-app/` 目录直接来源于此。

| 属性 | 内容 |
|---|---|
| 仓库 | https://github.com/XxHuberrr/Mineradio-paused |
| 状态 | **长期停更**（作者个人原因，源码公开保留） |
| 版本 | v2.2.0 |
| Star | 11,125 |
| 文件数 | ~310 个 |

### 关键信息

- **Electron 主进程**：`desktop/main.js` —— 创建 BrowserWindow、启动本地 HTTP 服务、处理 IPC
- **后端服务**：`server.js` —— 纯 Node 实现，无 Electron 依赖，Android 端直接复用
- **前端资源**：`public/` —— 138 个文件，HTML/CSS/JS + WebGL 粒子渲染
- **第三方模块**：`cuefield/`（AutoMix 过渡规划）、`dj-analyzer.js` 等

### 运行时依赖（package.json）

```json
{
  "gsap": "^3.15.0",
  "mpg123-decoder": "^1.0.3",
  "music-metadata": "11.14.0",
  "NeteaseCloudMusicApi": "^4.32.0",
  "qrcode": "^1.5.4"
}
```

> **注意**：`electron` 和 `electron-builder` 是 devDependencies，Android 版不需要。
> 如果 Android 版的 `node_modules` 不完整，优先确保这 5 个运行时依赖存在。

### 对 Android 移植的价值

1. **`server.js` 调试**：如果 Node 在 Android 上启动但立即退出，可能是 `server.js` 执行出错。可对照上游版本确认是否有 Android 不兼容的代码（如 `require('electron')` 泄漏到服务端）。
2. **`public/` 同步**：上游更新前端资源时，Android 版需要同步更新 `node-app/public/`。
3. **API 变更**：上游的 `kugou-api.js`、`qq-vip-api.js`、`qishui-api.js` 等模块如果修复了平台接口，Android 版可直接替换。

### 无 Android 相关代码

该仓库**没有任何 Android 移植代码**（无 `app/src/`、`gradle`、`jniLibs` 等）。Android 移植完全由社区/第三方完成。

---

## 七、参考项目关键差异

参考项目 `Timingyyds/Mineradio-Android` 的架构：

| 组件 | 参考项目 | 我的项目 |
|---|---|---|
| HTTP 静态文件服务 | Kotlin `MineradioServer`（端口 8800） | Node `server.js` 直接服务 |
| API 后端 | Node `server.js`（端口 3000） | Node `server.js`（同一端口） |
| Node 启动 | `NodeJSMobile.java` + `native-lib.cpp` + `rn-bridge.cpp` | `NodeRunner.kt` + `mineradio_node.cpp` |
| Native 模块 | `rn-bridge`（`NODE_MODULE_LINKED`） | 无 |
| 数据目录注册 | `registerNodeDataDirPath` | 无 |
| 头文件 | 完整 `include/node/`（~634 个文件） | 无头文件，用 `asm` 绑定 |

**如果 v2.2.0-android.4 仍失败，下一步应尝试**：
1. 完整移植参考项目的 native 层（`libnode.so` + `include/` + `native-lib.cpp` + `rn-bridge.cpp`）
2. 或进一步移植 `NodeJSMobile.java` 及其启动流程

---

## 八、下一步建议

1. **等待用户测试 v2.2.0-android.4**
   - 如果成功：确认是 16KB 补丁导致运行时破坏，需重新评估补丁策略
   - 如果失败：进入下一步深度移植

2. **深度移植参考项目 native 层**
   - 复制参考项目的 `app/src/main/cpp/libnode/`、`include/`、`native-lib.cpp`、`rn-bridge.cpp/h`
   - 修改 `CMakeLists.txt` 编译 `libmineradio-node-native.so`
   - 修改 Kotlin 层调用新的 JNI API（`startNodeWithArguments` 等）

3. **收集 logcat 日志**
   - 命令：`adb logcat | grep -iE "mineradio|NodeRunner|NodeJSMobile|Start"`
   - 这是确认 Node 退出原因的最直接方式

4. **统一 libnode.so 来源**
   - 为所有 ABI 使用同一来源的原始 `libnode.so`
   - 或仅保留 `arm64-v8a`，移除其他 ABI 支持以简化问题域

---

## 九、环境信息

| 工具 | 版本/路径 |
|---|---|
| OS | Windows（Git Bash） |
| Android SDK | `D:\AndroidDev\sdk` |
| NDK | `27.0.12077973` |
| CMake | `3.22.1` |
| JDK | Temurin 17.0.9（临时路径 `/tmp/jdk-17.0.9+9`） |
| Gradle | 8.x（wrapper） |
| GitHub CLI | 已授权，token 在 `/tmp/gh_device_token` |
| Python | `C:/Users/Administrator/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe` |
| 项目路径 | `C:/Users/Administrator/WorkBuddy AI/2026-10-15-13-13-24/Mineradio-Android` |

---

## 十、对话关键节点摘要

1. 用户反馈「还是启动失败」，要求参考 Timingyyds/Mineradio-Android 的实现
2. 我分析了参考项目的 `native-lib.cpp`、`rn-bridge.cpp`、`NodeJSMobile.java`、`MineradioServer.kt`
3. 发现参考项目使用原始未修改的 `libnode.so`（4KB 对齐），而我的版本经过 16KB 补丁
4. 决定最小改动：仅替换 `libnode.so`，保持 JNI/Kotlin 代码不变
5. 构建并发布 v2.2.0-android.4

---

*此文档由 WorkBuddy AI 生成，供 dsh 继续跟进。*
