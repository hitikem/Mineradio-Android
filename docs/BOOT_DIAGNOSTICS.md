# Mineradio Android —— 启动诊断日志说明

> 适用版本：v2.2.0-android.5 及以后
> 目的：让「本地音乐服务启动失败」这句话变成可定位的具体原因

## 一、怎么拿到日志

启动失败时，界面上会出现两个按钮：

```
本地音乐服务启动失败
   [ 重试 ]  [ 查看启动日志 ]
```

点 **查看启动日志**，进入诊断页。页面里是四份日志合并后的完整报告，可以直接
**截图**，也可以点 **分享** 把文件发给开发者。

其它进入方式：

| 场景 | 操作 |
|---|---|
| 启动卡在「正在启动本地音乐服务…」不动 | **长按遮罩底部的版本号** |
| 已经进入主界面 | 目前只能长按版本号（版本号只在启动遮罩上显示） |

## 二、日志文件在哪

都在应用私有目录，卸载即清除，不需要任何权限：

```
/data/data/com.mineradio.android/files/userdata/logs/
├── boot.log                Kotlin 侧启动全过程
├── native-boot.log         原生层 Node 启动诊断（关键）
├── mineradio-node.log      Node / server.js 自己的日志
├── mineradio-node.log.1    上一次的 Node 日志（超过 2MB 时滚动）
├── crash.log               Java 未捕获异常
└── mineradio-diagnose.txt  上面几份合并后的报告（分享用）
```

## 三、报告里都有什么

### 设备信息（BootLog 开头）

```
机型：Xiaomi 红米 K80
Android：17（API xx）
ABI：arm64-v8a
内存页大小：4096 字节        ← 16KB 页机型上会是 16384
应用版本：2.2.0-android.5（5）
```

**内存页大小**这一行很重要：16KB 页机型上，`.so` 的 LOAD 段必须按 16KB 对齐，
否则动态链接器会直接拒绝加载。

### 设备/库信息

- `libnode.so` 的**实际加载路径与大小**（`dladdr` 拿到，不是猜的）
- 本桥接库的构建标记，内含三个 ABI 的 `libnode.so` SHA256 前 16 位
  —— 用来确认 APK 里到底是哪一份库
- `/proc/self/maps` 里实际映射进来的 `libnode.so` 行

### 启动过程

```
libnode.so 加载成功
libmineradio_node.so 加载成功
资源版本检查：标记=1 期望=1 校验通过=True → 需要解压=False
入口脚本：.../main.js（xxx 字节）
选定端口：17321
传给 Node 的环境变量：...
原生层已接受启动请求，开始轮询 http://127.0.0.1:17321/
第 5 次探测失败，仍在等待（Node 运行中=True，已等待 1s）
第 10 次探测失败，仍在等待（Node 运行中=True，已等待 2s）
node::Start 已返回：退出码 = N
Node 线程已退出（第 8 次探测时），停止等待，退出码=N
```

### 原生层关键判据（native-boot.log）

| 日志 | 说明 |
|---|---|
| `>>> 进入 node::Start` 有没有配对 `<<< node::Start 已返回` | 没配对说明**卡在 Node 内部**或进程被杀 |
| 退出码 = 0 | Node 事件循环正常结束，说明**脚本跑完但没人监听端口** |
| 退出码 ≠ 0 | Node 启动/脚本执行失败，往上看 `main.js` 的输出 |
| `捕获到信号 SIGSEGV/SIGBUS/SIGILL/SIGABRT` | Node 在启动中**崩了**（信号处理器写盘后仍会重新抛出，保留系统 tombstone） |
| `/proc/self/maps 中没有 libnode.so` | 原生库根本没被加载 |

## 四、几个典型结论的长相

**情况 A：Node 起来了，只是端口不通**

```
>>> 进入 node::Start
======================================================
 粒子音乐可视化 v2  →  http://localhost:17321
```
→ 说明 `server.js` 正常，问题在 Kotlin 探测侧。

**情况 B：脚本执行报错**

```
>>> 进入 node::Start
Error: Cannot find module 'xxx'
<<< node::Start 已返回：退出码 = 1
```

**情况 C：Node 内部崩溃**

```
>>> 进入 node::Start
<没有返回行>
1640000000 [E] [pid=12345] 捕获到信号 SIGSEGV(11)，Node 启动过程中崩溃； addr=0x0000000000000000
```

**情况 D：Node 压根没跑起来**

```
libnode.so 加载失败 —— 后续不可能启动 Node
java.lang.UnsatisfiedLinkError: dlopen failed: ".../libnode.so" has a LOAD
segment with alignment 4096, which is not a multiple of the page size 16384
```
→ 16KB 页机型 + 未对齐的预编译库，见下节。

## 五、附带的构建溯源清单

`scripts/gen-build-manifest.py` 会生成 `release-assets/libnode-manifest.txt`，
记录每个 ABI 的 `libnode.so` 在**进 APK 之前**（含符号，约 62MB）与
**进 APK 之后**（已 strip，约 49MB）的大小与 SHA256，以及每个 LOAD 段的 `p_align`。

之所以要这份清单：release 构建会 strip 符号，同一份库在两个位置的大小能差 13MB，
只看大小非常容易误判成「换了另一个文件」。

```bash
python scripts/gen-build-manifest.py -o release-assets/libnode-manifest.txt
```
