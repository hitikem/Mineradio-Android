// Mineradio Android —— Node 运行时 JNI 桥接层
//
// 设计要点
// ---------
// 1) 直接按导出符号绑定 node::Start(int, char**)，不引入 nodejs-mobile 的整包头文件包。
// 2) Node 必须在独立线程里跑：node::Start 会一直阻塞到事件循环结束。
// 3) stdout/stderr 重定向到管道，由读线程转发到 logcat —— 否则 Android 上
//    原生线程的 stdout 会进 /dev/null，出问题完全看不到日志。

#include <jni.h>

#include <android/log.h>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "MineradioNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// libnode.so 中已导出的 node::Start(int argc, char* argv[])
// 签名来自 node.h: NODE_EXTERN int Start(int argc, char* argv[]);
// 使用 asm 标签绑定，避免携带 nodejs-mobile 的头文件包。
extern "C" int node_start_entry(int argc, char** argv) asm("_ZN4node5StartEiPPc");

namespace {

std::string g_project_dir;
std::string g_entry_script;
std::vector<std::string> g_node_args;
std::vector<std::string> g_env_pairs;

int g_pipe_fds[2] = {-1, -1};
pthread_t g_node_thread;
volatile bool g_started = false;
volatile bool g_finished = false;
volatile int g_exit_code = -1;

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
  if (chdir(g_project_dir.c_str()) != 0) {
    LOGE("chdir 到 %s 失败", g_project_dir.c_str());
  }

  // 组装 argv：node <args...> <entry>
  std::vector<std::string> storage;
  storage.emplace_back("node");
  for (const auto& a : g_node_args) storage.push_back(a);
  storage.push_back(g_entry_script);

  std::vector<char*> argv;
  argv.reserve(storage.size() + 1);
  for (auto& s : storage) argv.push_back(const_cast<char*>(s.c_str()));
  argv.push_back(nullptr);

  LOGI("启动 Node：cwd=%s argc=%zu", g_project_dir.c_str(), storage.size());
  for (size_t i = 0; i < storage.size(); ++i) {
    LOGI("  argv[%zu] = %s", i, storage[i].c_str());
  }

  int rc = node_start_entry(static_cast<int>(storage.size()), argv.data());

  g_exit_code = rc;
  g_finished = true;
  LOGI("node::Start 已返回，退出码 = %d", rc);
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
 */
JNIEXPORT jboolean JNICALL
Java_com_mineradio_android_NodeRunner_nativeStartNode(
    JNIEnv* env, jclass /*clazz*/,
    jstring project_dir, jstring entry_script,
    jobjectArray env_pairs, jobjectArray node_args) {

  if (g_started) {
    LOGI("Node 已在运行，忽略重复启动");
    return JNI_TRUE;
  }

  auto to_string = [&](jstring s) -> std::string {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
  };

  g_project_dir = to_string(project_dir);
  g_entry_script = to_string(entry_script);

  g_env_pairs.clear();
  if (env_pairs != nullptr) {
    const jsize n = env->GetArrayLength(env_pairs);
    for (jsize i = 0; i < n; ++i) {
      auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(env_pairs, i));
      std::string pair = to_string(item);
      env->DeleteLocalRef(item);
      if (pair.empty()) continue;

      const size_t eq = pair.find('=');
      if (eq == std::string::npos || eq == 0) continue;
      const std::string key = pair.substr(0, eq);
      const std::string value = pair.substr(eq + 1);
      setenv(key.c_str(), value.c_str(), 1);
      g_env_pairs.push_back(pair);
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
    LOGE("project_dir / entry_script 不能为空");
    return JNI_FALSE;
  }

  // 建立 stdout/stderr 管道并启动转发线程
  if (pipe(g_pipe_fds) != 0) {
    LOGE("创建管道失败");
    return JNI_FALSE;
  }

  pthread_t pump_thread;
  if (pthread_create(&pump_thread, nullptr, stdout_pump, nullptr) != 0) {
    LOGE("启动日志转发线程失败");
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
    LOGE("启动 Node 线程失败");
    g_started = false;
    return JNI_FALSE;
  }
  pthread_detach(g_node_thread);

  LOGI("Node 线程已启动");
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

}  // extern "C"
