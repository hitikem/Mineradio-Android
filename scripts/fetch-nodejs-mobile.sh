#!/usr/bin/env bash
#
# 下载 nodejs-mobile 的安卓版预编译 Node 运行时，并把 libnode.so 放到 jniLibs。
#
# 为什么不把 libnode.so 提交进仓库：
#   三个 ABI 合计约 180MB（arm64 62MB / armv7 58MB / x86_64 65MB），
#   塞进 git 会让仓库臃肿且克隆极慢。这里改为构建前按需下载。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS="$ROOT_DIR/app/src/main/jniLibs"

NODEJS_MOBILE_VERSION="${NODEJS_MOBILE_VERSION:-v18.20.4}"
ARCHIVE="nodejs-mobile-${NODEJS_MOBILE_VERSION}-android.zip"
URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/${NODEJS_MOBILE_VERSION}/${ARCHIVE}"

CACHE_DIR="${NODEJS_MOBILE_CACHE:-$ROOT_DIR/.cache/nodejs-mobile}"
ABIS=(arm64-v8a armeabi-v7a x86_64)

need_download=0
for abi in "${ABIS[@]}"; do
  [ -f "$JNI_LIBS/$abi/libnode.so" ] || need_download=1
done

if [ "$need_download" -eq 0 ]; then
  echo "libnode.so 已就位，跳过下载。"
  ls -la "$JNI_LIBS"/*/libnode.so
  exit 0
fi

mkdir -p "$CACHE_DIR"
if [ ! -f "$CACHE_DIR/$ARCHIVE" ]; then
  echo "下载 nodejs-mobile $NODEJS_MOBILE_VERSION ..."
  curl -fL --retry 8 --retry-delay 3 --retry-all-errors \
    -C - -o "$CACHE_DIR/$ARCHIVE" "$URL"
fi

echo "解压 ..."
rm -rf "$CACHE_DIR/extracted"
mkdir -p "$CACHE_DIR/extracted"
unzip -q -o "$CACHE_DIR/$ARCHIVE" -d "$CACHE_DIR/extracted"

# 不同版本的压缩包顶层结构不一样（有的直接是 bin/，有的套一层 nodejs-mobile/），
# 这里两种都兼容，找到含 bin/ 的那一层。
SRC_ROOT="$CACHE_DIR/extracted"
if [ ! -d "$SRC_ROOT/bin" ] && [ -d "$SRC_ROOT/nodejs-mobile/bin" ]; then
  SRC_ROOT="$SRC_ROOT/nodejs-mobile"
fi

if [ ! -d "$SRC_ROOT/bin" ]; then
  echo "解压结构不符合预期，请检查 $CACHE_DIR/extracted"
  find "$CACHE_DIR/extracted" -maxdepth 2 | head -20
  exit 1
fi

for abi in "${ABIS[@]}"; do
  mkdir -p "$JNI_LIBS/$abi"
  cp -f "$SRC_ROOT/bin/$abi/libnode.so" "$JNI_LIBS/$abi/libnode.so"
  echo "  + jniLibs/$abi/libnode.so"
done

echo
echo "完成。libnode.so 来源：$URL"
ls -la "$JNI_LIBS"/*/libnode.so
