#!/usr/bin/env bash
# 拉取 mihomo 内核二进制，解压并重命名为 libmihomo.so 放进 jniLibs。
# 仅在内置 .so 缺失时需要运行（首次克隆后 / 升级内核版本时）。
# 镜像走 ghfast.top，避免直连 github 被网络拦截。
set -e

VER="v1.19.32"
BASE="https://ghfast.top/https://github.com/MetaCubeX/mihomo/releases/download/${VER}"
DIR="$(cd "$(dirname "$0")" && pwd)/src/main/jniLibs"

dl() {
  local url="$1" abi="$2"
  local out="$DIR/$abi/libmihomo.so"
  mkdir -p "$DIR/$abi"
  echo "→ $abi"
  curl -sL -m 240 "$url" | gunzip -c > "$out"
  echo "  $(stat -c%s "$out" 2>/dev/null || echo '?') bytes"
}

dl "$BASE/mihomo-android-arm64-v8-v1.19.32.gz" arm64-v8a
dl "$BASE/mihomo-android-armv7-v1.19.32.gz"    armeabi-v7a

echo "完成。现在可执行: ./gradlew :mihomotv:assembleRelease"
