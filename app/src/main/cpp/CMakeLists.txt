cmake_minimum_required(VERSION 3.22.1)

project(mineradio_node CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# libnode.so —— nodejs-mobile 的预编译 Node 运行时
#
# 不提交进仓库（三个 ABI 合计约 180MB），由 scripts/fetch-nodejs-mobile.sh
# 从官方 Release 下载后放到 app/src/main/jniLibs/<abi>/libnode.so
# ---------------------------------------------------------------------------
set(NODEJS_MOBILE_LIB
    "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")

if(NOT EXISTS "${NODEJS_MOBILE_LIB}")
  message(FATAL_ERROR
    "缺少 libnode.so：${NODEJS_MOBILE_LIB}\n"
    "请先运行：bash scripts/fetch-nodejs-mobile.sh")
endif()

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION "${NODEJS_MOBILE_LIB}")

# ---------------------------------------------------------------------------
# 构建标记：把「这份 libnode.so 到底是哪一个文件」编译进桥接库。
#
# 排查「Node 起不来」时最容易踩的坑就是：源码树里的 .so 和 APK 里的 .so
# 不是同一个文件（或者某个 ABI 忘了换）。把 SHA256 编进去之后，
# 应用内导出的诊断报告能直接对上号，不用再猜。
# ---------------------------------------------------------------------------
set(MINERADIO_ABI_HASHES "")
foreach(abi arm64-v8a armeabi-v7a x86_64)
  set(lib "${CMAKE_SOURCE_DIR}/../jniLibs/${abi}/libnode.so")
  if(EXISTS "${lib}")
    file(SHA256 "${lib}" hash)
    string(SUBSTRING "${hash}" 0 16 short)
    string(APPEND MINERADIO_ABI_HASHES "${abi}=${short} ")
  else()
    string(APPEND MINERADIO_ABI_HASHES "${abi}=(缺失) ")
  endif()
endforeach()

set(MINERADIO_BUILD_NOTE "abi=${ANDROID_ABI} libnode[${MINERADIO_ABI_HASHES}]")

message(STATUS "Mineradio 构建标记：${MINERADIO_BUILD_NOTE}")

# ---------------------------------------------------------------------------
# 我们自己的 JNI 桥接库
#
# 说明：这里刻意不引入 nodejs-mobile 的 include/ 头文件包（约 12MB、634 个文件）。
# 桥接层唯一需要的外部符号是 node::Start(int, char**)，其导出符号名固定为
# _ZN4node5StartEiPPc（已在 libnode.so 的动态符号表中确认为 GLOBAL/FUNC/已定义），
# 因此用 asm 标签直接绑定，省掉整包头文件。
# ---------------------------------------------------------------------------
add_library(mineradio_node SHARED mineradio_node.cpp)

find_library(log-lib log)

target_compile_definitions(mineradio_node PRIVATE
    MINERADIO_BUILD_NOTE="${MINERADIO_BUILD_NOTE}"
)

# 显式要求 16KB 段对齐。
# NDK r27 起默认就是这个值，这里写死是为了避免将来换回旧 NDK 时
# 又出现「16KB 内存页机型上加载失败」这种只在真机上才暴露的问题。
target_link_options(mineradio_node PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(mineradio_node
    node
    ${log-lib}
)
