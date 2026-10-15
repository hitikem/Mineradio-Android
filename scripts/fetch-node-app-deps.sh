#!/usr/bin/env bash
#
# 安装 node-app 的生产依赖。
#
# 只装运行时真正需要的包（--omit=dev），并且跳过 install 脚本 ——
# 上游的 devDependencies 里全是 electron / electron-builder 这类桌面构建工具，
# 在安卓端毫无用处，装上只会让资源包从 42MB 涨到 120MB。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NODE_APP="$SCRIPT_DIR/../node-app"

cd "$NODE_APP"

if [ ! -f package.json ]; then
  echo "缺少 $NODE_APP/package.json"
  exit 1
fi

echo "安装生产依赖到 $NODE_APP ..."
for attempt in 1 2 3 4 5; do
  echo "--- 第 $attempt 次尝试 ---"
  if npm install --omit=dev --ignore-scripts --no-audit --no-fund; then
    break
  fi
  echo "网络中断，3 秒后重试"
  sleep 3
done

missing=()
for pkg in NeteaseCloudMusicApi qrcode music-metadata electron; do
  [ -d "node_modules/$pkg" ] || missing+=("$pkg")
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "以下依赖仍缺失：${missing[*]}"
  exit 1
fi

echo
echo "依赖安装完成："
du -sh node_modules
echo "（其中 electron 是本仓库提供的兼容桩，不是官方包）"
