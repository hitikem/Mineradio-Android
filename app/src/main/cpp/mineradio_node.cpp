// Mineradio Android —— Node 运行时 JNI 桥接层
//
// 设计要点
// ---------
// 1) 直接按导出符号绑定 node::Start(int, char**)，不引入 nodejs-mobile 的整包头文件包。
// 2) Node 必须在独立线程里跑：node::Start 会一直阻塞到事件循环结束。
// 3) stdout/stderr 重定向到管道，由读线程转发到 logcat —— 否则 Android 上
//    原生线程的 stdout 会进 /dev/null，出问题完全看不到日志。
// 4) **诊断**：整个过程同时写一份 native-boot.log 到应用私有目录。
//    用户没有 adb，一旦 node::Start 悄悄失败（返回非 0、或根本没执行到 JS），
//    唯一能拿到证据的地方就是这份文件。
//
// 关于 16KB 内存页
// ----------------
// LOAD 段 p_align 对齐到 16KB 只在 sizeof(void*)==8 的 ABI 上做，而且是
// **条件性**的：16KB 页机型上 p_align<16384 会被动态链接器直接拒绝。
// 具体见 CMakeLists.txt 里的说明。

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <cstdarg>
#include <string>
#include <vector>

#define LOG_TAG "MineradioNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// libnode.so 中已导出的 node::Start(int argc, char* argv[])
// 签名来自 node.h: NODE_EXTERN int Start(int argc, char* argv[]);
// 使用 asm 标签绑定，避免携带 nodejs-mobile 的头文件包。
extern "C" int node_start_entry(int argc, char** argv) asm("_ZN4node5StartEiPPc");

#ifndef MINERADIO_BUILD_NOTE
#define MINERADIO_BUILD_NOTE "unknown"
#endif

namespace {

std::string g_project_dir;
std::string g_entry_script;
std::vector<std::string> g_node_args;

// 诊断日志文件
const char* kNativeLogPath = nullptr;
int g_log_fd = -1;
pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

// stdout/stderr 转发管道
int g_pipe_fds[2] = {-1, -1};
pthread_t g_node_thread;
volatile bool g_started = false;
volatile bool g_finished = false;
volatile int g_exit_code = -1;

// ---------------------------------------------------------------- 时间

// 线程安全的时间格式化：ctime() 用静态缓冲，这里改成自己拼
std::string now_string() {
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  struct tm tm_buf;
  localtime_r(&ts.tv_sec, &tm_buf);
  char buf[64];
  snprintf(buf, sizeof(buf), "%02d-%02d %02d:%02d:%02d.%03ld",
           tm_buf.tm_mon + 1, tm_buf.tm_mday, tm_buf.tm_hour, tm_buf.tm_min,
           tm_buf.tm_sec, ts.tv_nsec / 1000000);
  return std::string(buf);
}

// ---------------------------------------------------------------- 诊断日志

void open_native_log() {
  if (kNativeLogPath == nullptr || g_log_fd >= 0) return;
  int fd = open(kNativeLogPath, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
  if (fd >= 0) {
    fchmod(fd, 0644);
    g_log_fd = fd;
  }
}

// 单次 write() 写入一行：要么完整写入，要么不写，便于崩溃后读日志
void nlog(const char* level, const char* fmt, ...) {
  char body[2048];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(body, sizeof(body), fmt, ap);
  va_end(ap);

  __android_log_print(
      strcmp(level, "E") == 0 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
      LOG_TAG, "[native] %s", body);

  if (g_log_fd < 0) return;

  char line[2240];
  const std::string stamp = now_string();
  int n = snprintf(line, sizeof(line), "%s [%s] [pid=%d] %s\n",
                   stamp.c_str(), level, getpid(), body);
  if (n <= 0) return;

  pthread_mutex_lock(&g_log_mutex);
  ssize_t ignored = write(g_log_fd, line, static_cast<size_t>(n));
  (void)ignored;
  pthread_mutex_unlock(&g_log_mutex);
}

// ---------------------------------------------------------------- 信号捕获

struct sigaction g_old_sa[4];
bool g_crash_handlers_installed = false;
const int kSignals[4] = {SIGSEGV, SIGBUS, SIGILL, SIGABRT};
const char* kSignalNames[4] = {"SIGSEGV", "SIGBUS", "SIGILL", "SIGABRT"};

// 预先拼好的常量：崩溃处理器里不能调用 snprintf/localtime 这类非异步信号安全的函数
char g_signal_head[4][96];
int g_pid = 0;

// 崩溃时只调用异步信号安全的 write()
void crash_handler(int sig, siginfo_t* info, void* /*ctx*/) {
  int index = -1;
  for (int i = 0; i < 4; ++i) {
    if (kSignals[i] == sig) {
      index = i;
      break;
    }
  }

  if (g_log_fd >= 0 && index >= 0) {
    ssize_t ignored = write(g_log_fd, g_signal_head[index], strlen(g_signal_head[index]));
    (void)ignored;

    // 崩溃地址手工拼成十六进制（%p 需要 snprintf，信号处理器里不能用）
    char addr[64];
    unsigned long long value =
        reinterpret_cast<unsigned long long>(info != nullptr ? info->si_addr : nullptr);
    int len = 0;
    addr[len++] = ' ';
    addr[len++] = 'a';
    addr[len++] = 'd';
    addr[len++] = 'd';
    addr[len++] = 'r';
    addr[len++] = '=';
    addr[len++] = '0';
    addr[len++] = 'x';
    for (int shift = 60; shift >= 0; shift -= 4) {
      const int digit = static_cast<int>((value >> shift) & 0xF);
      addr[len++] = static_cast<char>(digit < 10 ? '0' + digit : 'a' + digit - 10);
    }
    addr[len++] = '\n';
    ignored = write(g_log_fd, addr, static_cast<size_t>(len));
    (void)ignored;
  }

  // 恢复默认处理并重新抛出，保留系统的 tombstone / 崩溃上报
  if (index >= 0) {
    sigaction(sig, &g_old_sa[index], nullptr);
  }
  raise(sig);
}

void install_crash_handlers() {
  if (g_crash_handlers_installed) return;  // 只装一次，否则会把我们自己的处理器当成“原处理器”
  g_crash_handlers_installed = true;

  g_pid = getpid();
  for (int i = 0; i < 4; ++i) {
    snprintf(g_signal_head[i], sizeof(g_signal_head[i]),
             "%d [E] [pid=%d] 捕获到信号 %s(%d)，Node 启动过程中崩溃；",
             static_cast<int>(time(nullptr)), g_pid, kSignalNames[i], kSignals[i]);
  }

  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = crash_handler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);

  for (int i = 0; i < 4; ++i) {
    sigaction(kSignals[i], &sa, &g_old_sa[i]);
  }
}

// ---------------------------------------------------------------- 环境快照

void log_self_maps_libnode() {
  FILE* f = fopen("/proc/self/maps", "r");
  if (f == nullptr) {
    nlog("W", "读取 /proc/self/maps 失败：%s", strerror(errno));
    return;
  }
  char line[512];
  bool found = false;
  while (fgets(line, sizeof(line), f) != nullptr) {
    if (strstr(line, "libnode.so") == nullptr && strstr(line, "libc++_shared.so") == nullptr) {
      continue;
    }
    found = true;
    // 只保留路径部分，前面的地址/权限对定位没帮助
    char* path = strchr(line, '/');
    if (path != nullptr) {
      size_t len = strlen(path);
      while (len > 0 && (path[len - 1] == '\n' || path[len - 1] == '\r')) {
        path[--len] = '\0';
      }
      nlog("I", "已映射：%s", path);
    }
  }
  fclose(f);
  if (!found) {
    nlog("E", "/proc/self/maps 中没有 libnode.so —— 原生库没被加载！");
  }
}

void log_dladdr(const char* name, void* symbol) {
  Dl_info info;
  memset(&info, 0, sizeof(info));
  if (dladdr(symbol, &info) != 0 && info.dli_fname != nullptr) {
    struct stat st;
    long size = (stat(info.dli_fname, &st) == 0) ? static_cast<long>(st.st_size) : -1;
    nlog("I", "dladdr(%s) → %s（%ld 字节）", name, info.dli_fname, size);
  } else {
    nlog("W", "dladdr(%s) 失败", name);
  }
}

void log_file_probe(const char* label, const std::string& path) {
  struct stat st;
  if (stat(path.c_str(), &st) != 0) {
    nlog("E", "%s 不存在或不可访问：%s（errno=%d %s）", label, path.c_str(), errno,
         strerror(errno));
    return;
  }
  const bool readable = (access(path.c_str(), R_OK) == 0);
  nlog("I", "%s 存在：%s（%ld 字节，可读=%s，模式=%o）", label, path.c_str(),
       static_cast<long>(st.st_size), readable ? "是" : "否", st.st_mode & 07777);
}

// ---------------------------------------------------------------- 日志转发

void* stdout_pump(void* /*arg*/) {
  char buf[4096];
  std::string pending;

  while (true) {
    ssize_t n = read(g_pipe_fds[0], buf, sizeof(buf) - 1);
    if (n <= 0) break;  // 管道关闭或出错
    buf[n] = '\0';
    pending.append(buf, static_cast<size_t>(n));

    // 按行切分再打日志：logcat 单条有长度上限，整块打会被截断
    size_t pos;
    while ((pos = pending.find('\n')) != std::string::npos) {
      std::string line = pending.substr(0, pos);
      pending.erase(0, pos + 1);
      if (!line.empty() && line.back() == '\r') line.pop_back();
      if (!line.empty()) {
        __android_log_write(ANDROID_LOG_INFO, LOG_TAG, line.c_str());
      }
    }
    // 单行超长时强制刷出，避免无限堆积
    if (pending.size() > 3072) {
      __android_log_write(ANDROID_LOG_INFO, LOG_TAG, pending.c_str());
      pending.clear();
    }
  }

  if (!pending.empty()) {
    __android_log_write(ANDROID_LOG_INFO, LOG_TAG, pending.c_str());
  }
  return nullptr;
}

// ---------------------------------------------------------------- Node 线程

void* node_thread_main(void* /*arg*/) {
  nlog("I", "Node 线程启动：tid=%d", gettid());
  nlog("I", "构建标记：%s", MINERADIO_BUILD_NOTE);

  log_self_maps_libnode();
  log_dladdr("node::Start", reinterpret_cast<void*>(&node_start_entry));
  log_dladdr("pthread_create", reinterpret_cast<void*>(&pthread_create));

  if (chdir(g_project_dir.c_str()) != 0) {
    nlog("E", "chdir 到 %s 失败：%s", g_project_dir.c_str(), strerror(errno));
  } else {
    char cwd[512];
    nlog("I", "chdir 成功，当前目录=%s",
         getcwd(cwd, sizeof(cwd)) != nullptr ? cwd : "?");
  }

  log_file_probe("入口脚本", g_entry_script);
  log_file_probe("package.json", g_project_dir + "/package.json");
  log_file_probe("node_modules", g_project_dir + "/node_modules");

  // 组装 argv：node <args...> <entry>
  std::vector<std::string> storage;
  storage.emplace_back("node");
  for (const auto& a : g_node_args) storage.push_back(a);
  storage.push_back(g_entry_script);

  std::vector<char*> argv;
  argv.reserve(storage.size() + 1);
  for (auto& s : storage) argv.push_back(const_cast<char*>(s.c_str()));
  argv.push_back(nullptr);

  nlog("I", "准备调用 node::Start：argc=%zu cwd=%s", storage.size(),
       g_project_dir.c_str());
  for (size_t i = 0; i < storage.size(); ++i) {
    nlog("I", "  argv[%zu] = %s", i, storage[i].c_str());
  }

  const std::string t0 = now_string();
  nlog("I", ">>> 进入 node::Start（%s）", t0.c_str());

  int rc = node_start_entry(static_cast<int>(storage.size()), argv.data());

  g_exit_code = rc;
  g_finished = true;
  nlog("E", "<<< node::Start 已返回（%s 进入，%s 返回）：退出码 = %d",
       t0.c_str(), now_string().c_str(), rc);
  if (rc == 0) {
    nlog("W", "退出码为 0：Node 事件循环正常结束 —— 说明脚本跑完了但没有东西在监听端口，"
              "通常是 server.js 里进程提前退出");
  } else {
    nlog("E", "退出码非 0：Node 启动或脚本执行失败，请对照上方 main.js 输出的报错");
  }
  return nullptr;
}

}  // namespace

extern "C" {

/**
 * 启动内嵌 Node 运行时。
 *
 * @param project_dir  Node 项目根目录（已从 assets 解压到可写路径）
 * @param entry_script 入口脚本绝对路径
 * @param env_pairs    "KEY=VALUE" 形式的环境变量数组
 * @param node_args    传给 node 的命令行参数（如 --max-old-space-size=1024）
 * @param log_path     native 诊断日志的绝对路径，可为 null
 */
JNIEXPORT jboolean JNICALL
Java_com_mineradio_android_NodeRunner_nativeStartNode(
    JNIEnv* env, jclass /*clazz*/,
    jstring project_dir, jstring entry_script,
    jobjectArray env_pairs, jobjectArray node_args, jstring log_path) {

  auto to_string = [&](jstring s) -> std::string {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
  };

  // 先开日志文件：后面每一步都要留痕
  const std::string log_path_str = to_string(log_path);
  if (!log_path_str.empty()) {
    static std::string holder;
    holder = log_path_str;
    kNativeLogPath = holder.c_str();
  }
  open_native_log();
  install_crash_handlers();

  nlog("I", "================ native 启动诊断开始 ================");
  nlog("I", "JNI 进入：tid=%d 构建=%s", gettid(), MINERADIO_BUILD_NOTE);
  nlog("I", "诊断日志文件=%s", kNativeLogPath ? kNativeLogPath : "(未设置)");

  if (g_started) {
    nlog("I", "Node 已在运行，忽略重复启动");
    return JNI_TRUE;
  }

  g_project_dir = to_string(project_dir);
  g_entry_script = to_string(entry_script);

  nlog("I", "project_dir=%s", g_project_dir.c_str());
  nlog("I", "entry_script=%s", g_entry_script.c_str());
  nlog("I", "ABI 指针宽度=%zu 位", sizeof(void*) * 8);
  nlog("I", "页大小=%ld 字节（16KB 页机型上应为 16384）", sysconf(_SC_PAGESIZE));

  if (env_pairs != nullptr) {
    const jsize n = env->GetArrayLength(env_pairs);
    nlog("I", "环境变量 %d 条：", n);
    for (jsize i = 0; i < n; ++i) {
      auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(env_pairs, i));
      std::string pair = to_string(item);
      env->DeleteLocalRef(item);
      if (pair.empty()) continue;

      const size_t eq = pair.find('=');
      if (eq == std::string::npos || eq == 0) continue;
      const std::string key = pair.substr(0, eq);
      const std::string value = pair.substr(eq + 1);
      if (setenv(key.c_str(), value.c_str(), 1) != 0) {
        nlog("W", "  setenv(%s) 失败：%s", key.c_str(), strerror(errno));
      }
      // 只看是否设置成功，完整值由 Kotlin 侧记录
      nlog("I", "  %s=%s", key.c_str(), value.c_str());
    }
  }

  g_node_args.clear();
  if (node_args != nullptr) {
    const jsize n = env->GetArrayLength(node_args);
    for (jsize i = 0; i < n; ++i) {
      auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(node_args, i));
      std::string arg = to_string(item);
      env->DeleteLocalRef(item);
      if (!arg.empty()) g_node_args.push_back(arg);
    }
  }

  if (g_project_dir.empty() || g_entry_script.empty()) {
    nlog("E", "project_dir / entry_script 不能为空");
    return JNI_FALSE;
  }

  // 建立 stdout/stderr 管道并启动转发线程
  if (pipe(g_pipe_fds) != 0) {
    nlog("E", "创建管道失败：%s", strerror(errno));
    return JNI_FALSE;
  }
  nlog("I", "管道已建立：read=%d write=%d", g_pipe_fds[0], g_pipe_fds[1]);

  pthread_t pump_thread;
  if (pthread_create(&pump_thread, nullptr, stdout_pump, nullptr) != 0) {
    nlog("E", "启动日志转发线程失败：%s", strerror(errno));
  } else {
    pthread_detach(pump_thread);
  }

  dup2(g_pipe_fds[1], STDOUT_FILENO);
  dup2(g_pipe_fds[1], STDERR_FILENO);
  close(g_pipe_fds[1]);
  g_pipe_fds[1] = -1;

  // 行缓冲，保证日志及时可见
  setvbuf(stdout, nullptr, _IOLBF, 0);
  setvbuf(stderr, nullptr, _IONBF, 0);

  g_started = true;

  if (pthread_create(&g_node_thread, nullptr, node_thread_main, nullptr) != 0) {
    nlog("E", "启动 Node 线程失败：%s", strerror(errno));
    g_started = false;
    return JNI_FALSE;
  }
  pthread_detach(g_node_thread);

  nlog("I", "Node 线程已创建，nativeStartNode 返回 true");
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mineradio_android_NodeRunner_nativeIsRunning(
    JNIEnv* /*env*/, jclass /*clazz*/) {
  return (g_started && !g_finished) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_mineradio_android_NodeRunner_nativeExitCode(
    JNIEnv* /*env*/, jclass /*clazz*/) {
  return g_exit_code;
}

/** 系统内存页大小，BootLog 用它判断是否运行在 16KB 页机型上 */
JNIEXPORT jint JNICALL
Java_com_mineradio_android_BootLog_nativePageSize(
    JNIEnv* /*env*/, jclass /*clazz*/) {
  long page = sysconf(_SC_PAGESIZE);
  return page > 0 ? static_cast<jint>(page) : -1;
}

/** 原生库信息报告：加载路径、大小、libnode.so 的 SHA256，供界面展示 */
JNIEXPORT jstring JNICALL
Java_com_mineradio_android_BootLog_nativeReport(
    JNIEnv* env, jclass /*clazz*/) {
  std::string out;
  char buf[1024];

  out += "本库构建：";
  out += MINERADIO_BUILD_NOTE;
  out += "\n";
  snprintf(buf, sizeof(buf), "指针宽度：%zu 位　页大小：%ld 字节\n", sizeof(void*) * 8,
           sysconf(_SC_PAGESIZE));
  out += buf;

  Dl_info info;
  memset(&info, 0, sizeof(info));
  if (dladdr(reinterpret_cast<void*>(&node_start_entry), &info) != 0 &&
      info.dli_fname != nullptr) {
    struct stat st;
    long size = (stat(info.dli_fname, &st) == 0) ? static_cast<long>(st.st_size) : -1;
    snprintf(buf, sizeof(buf), "libnode.so 加载路径：%s（%ld 字节）\n", info.dli_fname, size);
    out += buf;
  } else {
    out += "libnode.so 加载路径：dladdr 失败\n";
  }

  memset(&info, 0, sizeof(info));
  if (dladdr(reinterpret_cast<void*>(&Java_com_mineradio_android_BootLog_nativeReport),
             &info) != 0 &&
      info.dli_fname != nullptr) {
    snprintf(buf, sizeof(buf), "本桥接库路径：%s\n", info.dli_fname);
    out += buf;
  }

  snprintf(buf, sizeof(buf), "运行状态：started=%d finished=%d exitCode=%d\n",
           g_started ? 1 : 0, g_finished ? 1 : 0, g_exit_code);
  out += buf;

  return env->NewStringUTF(out.c_str());
}

}  // extern "C"
