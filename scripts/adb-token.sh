#!/usr/bin/env bash
# 从设备上正在运行的 AiCast 里取一个有效 token，写进 ~/.aicast/devices.json。
#
#   用法: ./scripts/adb-token.sh [设备号]        # adb devices 只连了一台时可省略
#         ./scripts/adb-token.sh --show         # 只打印不写入
#
# 什么时候用：
#   ① 不知道上层设置的密码，又要跑 smoke.sh / verify-fixes.sh / aicast CLI；
#   ② 「明明昨天还通」突然 401 —— 多半是 token 被 Auth.issue() 淘汰了（见 Auth.kt）。
#
# 前提（和本项目其它真机脚本一样）：
#   · 装的是 **debug 包**（release 非 debuggable，run-as 进不去）
#   · Git Bash 下必须先 export MSYS_NO_PATHCONV=1，否则 /data/... 会被改成 Windows 路径
set -u

PKG="com.aicast.app"
PREF="/data/user/0/$PKG/shared_prefs/aicast_auth.xml"
STORE="${HOME}/.aicast/devices.json"

WRITE=1
DEV=""
DEVICE_ID=""
for a in "$@"; do
  case "$a" in
    --show)     WRITE=0 ;;
    --device=*) DEVICE_ID="${a#--device=}" ;;
    *)          DEV="-s $a" ;;
  esac
done

die() { printf '\033[31m%s\033[0m\n' "$1"; exit 1; }

command -v adb  >/dev/null || die "找不到 adb（把它加进 PATH，通常是 <Sdk>/platform-tools）"
command -v node >/dev/null || die "找不到 node（写 devices.json 要用）"

# 取过期时刻最大的那条：<token>:<过期时刻>[:<签发时刻>]
RAW="$(adb $DEV shell run-as $PKG cat "$PREF" 2>/dev/null | tr -d '\r' \
        | tr ';' '\n' | grep -o '[0-9a-f]\{32\}:[0-9]\{13\}' | sort -t: -k2 -rn | head -1)"
[ -n "$RAW" ] || die "没取到 token。检查：① 装的是不是 debug 包 ② adb shell run-as $PKG 能否连通 ③ 是否至少 POST /auth 过一次"

TOK="${RAW%%:*}"; EXP="${RAW##*:}"
NOW="$(date +%s000)"
[ "$EXP" -gt "$NOW" ] || die "设备上的 token 已过期（expiresAt=$EXP），先重新鉴权再取"

printf 'token      %s\n' "${TOK:0:8}…${TOK: -4}"
printf 'expiresAt  %s（还剩 %s 天）\n' "$EXP" "$(( (EXP - NOW) / 86400000 ))"

[ "$WRITE" = 1 ] || exit 0

export TOK EXP STORE DEVICE_ID
node <<'EOF' || { echo "写入 devices.json 失败"; exit 1; }
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.env.STORE, "utf8"));
const ids = Object.keys(j.devices);
const id = process.env.DEVICE_ID
  || ids.find(k => j.devices[k].token)   // 之前持过 token 的那个
  || ids[0];
if (!id || !j.devices[id]) {
  console.error("devices.json 里没有设备，先跑: node skill/bin/aicast register <ip:port>");
  process.exit(1);
}
j.devices[id].token = process.env.TOK;
j.devices[id].tokenExp = Number(process.env.EXP);
fs.writeFileSync(process.env.STORE, JSON.stringify(j, null, 2));
console.log(`已写入 ${id} (${j.devices[id].name}) -> ${process.env.STORE}`);
EOF
