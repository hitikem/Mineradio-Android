#!/usr/bin/env bash
#
# 把 jniLibs 下所有 libnode.so 对齐到 16KB，使其能在 16KB 内存页的安卓设备上加载。
#
# nodejs-mobile 官方预编译的 libnode.so 是 4KB 对齐的，在 16KB 页机型
# （Pixel 8/9、部分新旗舰）上 dlopen 会直接失败，表现是「能装上，点开闪退」。
#
# 用法：
#   bash scripts/patch-libnode-16kb.sh            # 就地修补
#   bash scripts/patch-libnode-16kb.sh --check    # 只检查，不改文件
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS="$ROOT_DIR/app/src/main/jniLibs"

# 找一个可用的 Python（脚本只用标准库，不需要装任何包）
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for cand in python3 python; do
    if command -v "$cand" >/dev/null 2>&1; then PY="$cand"; break; fi
  done
fi
if [ -z "$PY" ] && [ -x "C:/Users/Administrator/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" ]; then
  PY="C:/Users/Administrator/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"
fi
if [ -z "$PY" ]; then
  echo "找不到 Python，无法修补 libnode.so"
  exit 1
fi
echo "使用 Python: $PY"

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

ABIS=(arm64-v8a armeabi-v7a x86_64)

fail=0
for abi in "${ABIS[@]}"; do
  lib="$JNI_LIBS/$abi/libnode.so"
  echo "=============================================================="
  echo "ABI: $abi"
  if [ ! -f "$lib" ]; then
    echo "  跳过：文件不存在（先跑 scripts/fetch-nodejs-mobile.sh）"
    continue
  fi

  if [ "$CHECK_ONLY" -eq 1 ]; then
    "$PY" "$SCRIPT_DIR/patch-elf-16kb.py" "$lib" --out /dev/null 2>&1 | head -12 || true
    continue
  fi

  # 输出到临时文件，校验全过才替换原文件（校验不过脚本不会写出文件）
  if "$PY" "$SCRIPT_DIR/patch-elf-16kb.py" "$lib" --out "$lib.16kb" 2>&1 | tail -12; then
    if [ -s "$lib.16kb" ]; then
      mv -f "$lib.16kb" "$lib"
      echo "  [$abi] 已应用 16KB 对齐"
    else
      echo "  [$abi] 已满足要求，无需修补"
    fi
  else
    echo "  [$abi] 修补失败，保留原始文件"
    rm -f "$lib.16kb"
    fail=1
  fi
done

echo "=============================================================="
if [ "$fail" -eq 0 ]; then
  echo "全部完成。"
else
  echo "有 ABI 修补失败，请检查上面的输出。"
  exit 1
fi
