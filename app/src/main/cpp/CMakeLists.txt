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
# 我们自己的 JNI 桥接库
#
# 说明：这里刻意不引入 nodejs-mobile 的 include/ 头文件包（约 12MB、634 个文件）。
# 桥接层唯一需要的外部符号是 node::Start(int, char**)，其导出符号名固定为
# _ZN4node5StartEiPPc（已在 libnode.so 的动态符号表中确认为 GLOBAL/FUNC/已定义），
# 因此用 asm 标签直接绑定，省掉整包头文件。
# ---------------------------------------------------------------------------
add_library(mineradio_node SHARED mineradio_node.cpp)

find_library(log-lib log)

target_link_libraries(mineradio_node
    node
    ${log-lib}
)
