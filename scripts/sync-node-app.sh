#!/usr/bin/env bash
#
# 从上游 Mineradio 源码同步安卓端需要的 Node 运行时文件到 node-app/
#
# 用法：
#   bash scripts/sync-node-app.sh [上游源码目录]
#
# 默认从 ./upstream/Mineradio 读取；也可以传入任意本地克隆路径。
# 只复制服务端真正会加载的文件，桌面专有代码（desktop/、scripts/、tests/）不参与。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="${1:-$ROOT_DIR/upstream/Mineradio}"
DST="$ROOT_DIR/node-app"

if [ ! -f "$SRC/server.js" ]; then
  echo "找不到上游源码：$SRC"
  echo "请先克隆：git clone --depth 1 https://github.com/XxHuberrr/Mineradio.git upstream/Mineradio"
  exit 1
fi

echo "上游源码：$SRC"
echo "目标目录：$DST"

# 服务端直接加载的 JS 模块
JS_FILES=(
  server.js
  dj-analyzer.js
  kugou-api.js
  qishui-api.js
  qishui-auth-v6.js
  qishui-qr-login.js
  qq-vip-api.js
  spotify-api.js
)

# 服务端需要的目录（前端资源 + 子模块）
DIRS=(
  cuefield
  qishui-auth-v6
  qishui-audio-decryptor
  public
)

for f in "${JS_FILES[@]}"; do
  if [ -f "$SRC/$f" ]; then
    cp -f "$SRC/$f" "$DST/$f"
    echo "  + $f"
  else
    echo "  ! 缺少 $f（上游可能已改名）"
  fi
done

for d in "${DIRS[@]}"; do
  if [ -d "$SRC/$d" ]; then
    rm -rf "${DST:?}/$d"
    cp -R "$SRC/$d" "$DST/$d"
    echo "  + $d/"
  else
    echo "  ! 缺少目录 $d/"
  fi
done

# 记录同步来源，便于追溯
cat > "$DST/UPSTREAM_SYNC.json" <<EOF
{
  "source": "$SRC",
  "syncedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "upstreamRepo": "https://github.com/XxHuberrr/Mineradio",
  "license": "GPL-3.0-only"
}
EOF

echo
echo "同步完成。接下来安装生产依赖："
echo "  cd node-app && npm install --omit=dev --ignore-scripts"
