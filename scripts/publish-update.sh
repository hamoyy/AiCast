#!/usr/bin/env bash
# 把某一版的 update.json（以及可选的 APK）同步到官网仓库，再推上去让 EdgeOne 自动部署。
#
# 为什么需要这一步：CI 只能往自己的仓库发 Release 资产，没有别的仓库的写权限
# （GitHub 的 GITHUB_TOKEN 仅限本仓库）。而 app 优先读的清单是
# https://aicast.lvchen.fun/update.json —— 国内访问这个比 GitHub 稳。
# 所以打 tag 之后跑一次本脚本，官网就有了最新清单。
#
# 用法：
#   scripts/publish-update.sh v0.2.0              # 只同步 update.json（推荐）
#   scripts/publish-update.sh v0.2.0 --with-apk   # 连 APK 一起放官网（官网仓库会变大）
#
# 前提：~/.local/bin/gh 已登录，且本机有 AiCast-website 的 git 权限。
set -euo pipefail

TAG="${1:-}"
WITH_APK="${2:-}"
if [ -z "$TAG" ]; then
  echo "用法: $0 <tag> [--with-apk]" >&2
  exit 2
fi

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SITE_DIR="${AICAST_SITE_DIR:-$HOME/AiCast-website}"
GH="${GH_BIN:-$HOME/.local/bin/gh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> 从 $TAG 的 Release 下载 update.json"
"$GH" release download "$TAG" --repo hamoyy/AiCast -n update.json -D "$TMP" 2>/dev/null \
  || "$GH" release download "$TAG" --repo hamoyy/AiCast -p 'update.json' -D "$TMP"

MANIFEST="$TMP/update.json"
[ -s "$MANIFEST" ] || { echo "没拿到 update.json（该 tag 的 CI 跑完了吗？）" >&2; exit 1; }

# 守住底线：清单里的下载地址必须指向这个 tag，避免发布时串版
grep -q "/download/$TAG/" "$MANIFEST" || {
  echo "清单里的下载地址与 tag 不一致：" >&2; cat "$MANIFEST" >&2; exit 1
}

echo "==> 写入 $SITE_DIR/update.json"
install -m 644 "$MANIFEST" "$SITE_DIR/update.json"

if [ "$WITH_APK" = "--with-apk" ]; then
  echo "==> 同步 APK 到官网（3MB 级别，历史里会累积）"
  mkdir -p "$SITE_DIR/download"
  "$GH" release download "$TAG" --repo hamoyy/AiCast -n "aicast-$TAG.apk" -D "$TMP"
  install -m 644 "$TMP/aicast-$TAG.apk" "$SITE_DIR/download/aicast-$TAG.apk"
fi

cd "$SITE_DIR"
if [ -z "$(git status --porcelain)" ]; then
  echo "官网已是这一版，无需推送"
  exit 0
fi
git add -A
git commit -q -m "更新清单：$TAG"
git push -q origin main
echo "==> 已推送，EdgeOne 约 1 分钟后生效：https://aicast.lvchen.fun/update.json"
