#!/usr/bin/env bash
# AiCast 修复项真机验证脚本
#   用法: ./scripts/verify-fixes.sh <ip[:port]> <token>
#   A) P0-6 删除类端点权限门禁   B) P0-7 删除当前主题自动回退 native
#   C) P0-1 媒体配额环形淘汰      D) 单文件超限拒绝   E) P1-14 密码加盐迁移
#   F) token 封顶 32 时不该淘汰「最早签发」的长期会话
#   需要：curl、python3、adb（且本机连着目标设备）
set -u
ADDR="${1:?用法: $0 <ip[:port]> <token>}"
TOK="${2:?用法: $0 <ip[:port]> <token>}"
case "$ADDR" in
  *:*) BASE="http://$ADDR" ;;
  *)   BASE="http://$ADDR:43210" ;;
esac
PKG=com.aicast.app
TMP=".verify.tmp"; rm -rf "$TMP"; mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT
BODY="$TMP/body"
PASS=0; FAIL=0

# check <用例名> <期望状态码> <curl 参数...>
check() {
  local name="$1" want="$2"; shift 2
  local code
  code="$(curl -s -m 30 -o "$BODY" -w '%{http_code}' "$@")"
  if [ "$code" = "$want" ]; then
    PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-54s %s\n' "$name" "$code"
  else
    FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-54s got=%s want=%s\n      %s\n' \
      "$name" "$code" "$want" "$(head -c 200 "$BODY")"
  fi
}
# assert <用例名> <python 表达式，d 为响应 JSON>
assert() {
  local name="$1" expr="$2"
  local out; out="$(BODY="$BODY" python3 -c "
import json,os
try: d=json.load(open(os.environ['BODY']))
except Exception: d={}
print(bool($expr))
" 2>/dev/null)"
  if [ "$out" = "True" ]; then
    PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-54s ok\n' "$name"
  else
    FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-54s %s\n      %s\n' \
      "$name" "$out" "$(head -c 200 "$BODY")"
  fi
}
# Git Bash 会把 /data/... 转成 Windows 路径，必须关掉路径转换
export MSYS_NO_PATHCONV=1
SP_DIR=/data/user/0/$PKG/shared_prefs
sp_get() { adb shell run-as $PKG cat "$SP_DIR/$1" 2>/dev/null | tr -d '\r'; }
# sp_put <目标 pref 文件名> <本地文件路径>
# 注：Android 12+ 下 run-as 里用 shell 重定向写文件会被 SELinux 拒绝，
#     只能先 adb push 到 /data/local/tmp，再由 app 身份 cp 进去（目标名必须是真 pref 名）
sp_put() {
  local dest="$1" src="$2"
  adb push "$src" /data/local/tmp/sp.tmp >/dev/null 2>&1 || { echo "push 失败: $src"; return 1; }
  adb shell run-as $PKG cp /data/local/tmp/sp.tmp "$SP_DIR/$dest" || { echo "cp 失败"; return 1; }
  adb shell rm /data/local/tmp/sp.tmp
}
restart_app() { adb shell am force-stop $PKG; sleep 1; adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3; }

A=(-H "Authorization: Bearer $TOK")
J=(-H 'Content-Type: application/json')

echo "== 目标 $BASE =="

# ---------------- A) P0-6 删除类端点权限门禁 ----------------
echo "-- A) 删除/上传端点权限门禁（关闭 render 后应全部 403）--"
sp_get aicast.xml > "$TMP/aicast.orig.xml"
cp "$TMP/aicast.orig.xml" "$TMP/aicast.off.xml"
python3 - "$TMP/aicast.off.xml" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
s=re.sub(r'(<boolean name="allow_render" value=")[^"]*(")', r'\1false\2', s)
open(p,'w',encoding='utf-8',newline='\n').write(s)
PY
if ! grep -q 'allow_render" value="false' "$TMP/aicast.off.xml"; then
  echo "  本地改写 allow_render 失败，跳过本组用例"; exit 1
fi
sp_put aicast.xml "$TMP/aicast.off.xml"; restart_app
check "DELETE /theme 无 render 权限 → 403" 403 -X DELETE "${A[@]}" "${J[@]}" "$BASE/theme?id=whatever"
assert "403 原因为 permission_denied" "d.get('code')=='permission_denied'"
check "DELETE /media 无 render 权限 → 403" 403 -X DELETE "${A[@]}" "${J[@]}" "$BASE/media?id=whatever"
check "POST /media 无 render 权限 → 403"   403 -X POST "${A[@]}" -H 'Content-Length: 8' --data-binary 'testdata' "$BASE/media?name=x.bin"
check "POST /theme 无 render 权限 → 403"   403 -X POST "${A[@]}" "${J[@]}" -d '{"html":"<b>x</b>"}' "$BASE/theme"
sp_put aicast.xml "$TMP/aicast.orig.xml"; restart_app
check "恢复 render 权限后再读 /state → 200" 200 "${A[@]}" "$BASE/state"

# ---------------- B) P0-7 删除当前主题自动回退 ----------------
echo "-- B) 删除「正在使用的首页主题」应回退 native --"
check "POST /theme（注册并激活）→ 200" 200 -X POST "${A[@]}" "${J[@]}" \
  -d '{"name":"临时验证主题","activate":true,"html":"<html><body style=\"background:#123\"><h1>t</h1></body></html>"}' "$BASE/theme"
assert "返回 activated=true" "d.get('activated') is True"
TID="$(python3 -c "import json;print(json.load(open('$BODY'))['id'])")"
echo "    主题 id = $TID"
check "GET /state → 200" 200 "${A[@]}" "$BASE/state"
assert "当前首页主题 = 刚注册的主题" "d.get('theme')=='$TID'"
check "DELETE /theme?id=$TID → 200" 200 -X DELETE "${A[@]}" "${J[@]}" "$BASE/theme?id=$TID"
assert "删除成功 removed 回显" "d.get('removed')=='$TID'"
check "GET /state → 200" 200 "${A[@]}" "$BASE/state"
assert "主题已回退为 native（不再是悬空 id）" "d.get('theme')=='native'"

# ---------------- C) P0-1 媒体配额环形淘汰 ----------------
echo "-- C) 媒体配额：超配额应淘汰最旧，且新上传的永不自淘汰 --"
check "GET /media → 200" 200 "${A[@]}" "$BASE/media"
for id in $(BODY="$BODY" python3 -c "
import json,os
d=json.load(open(os.environ['BODY']))
print(' '.join(i['id'] for i in (d.get('media') or [])))
"); do curl -s -o /dev/null -X DELETE "${A[@]}" "${J[@]}" "$BASE/media?id=$id"; done
echo "    已清理历史媒体，配额从 0 开始计"
python3 - <<'PY'
import os
p='.verify.tmp/blob11mb.bin'
with open(p,'wb') as f:
    f.write(b'0123456789abcdef' * (11*1024*1024//16))
PY
for n in 1 2 3; do
  # Expect: 100-continue 会让 curl 空等 1s，本用例不需要，显式关掉
  check "POST /media 第 $n 个 11MB → 200" 200 -X POST "${A[@]}" -H 'Expect:' \
    -H 'Content-Type: application/octet-stream' --data-binary "@$TMP/blob11mb.bin" "$BASE/media?name=q$n.bin"
done
check "GET /media → 200" 200 "${A[@]}" "$BASE/media"
read CNT TOTAL <<<"$(BODY="$BODY" python3 -c "
import json,os
d=json.load(open(os.environ['BODY']))
items=d.get('media') or d.get('items') or []
print(len(items), sum(i.get('size',0) for i in items))
")"
echo "    现存 $CNT 项 / 合计 $TOTAL 字节（配额 33554432）"
if [ "$CNT" -lt 3 ]; then
  PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-54s %s\n' "超配额后触发了环形淘汰（未无限增长）" "$CNT 项"
else
  FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-54s %s\n' "超配额后触发了环形淘汰" "仍为 $CNT 项"
fi
assert "最新上传的 q3.bin 仍在列表（新文件不自淘汰）" "any(i.get('name')=='q3.bin' for i in (d.get('media') or []))"
assert "最旧的 q1.bin 已被淘汰" "not any(i.get('name')=='q1.bin' for i in (d.get('media') or []))"
rm -f "$TMP/blob11mb.bin"

# ---------------- D) 单文件超限 ----------------
echo "-- D) 单文件超过 min(64MB,配额) 应被 Content-Length 预检拒绝 --"
check "POST /media 声明 40MB 空 body → 413" 413 -X POST "${A[@]}" -H 'Content-Length: 41943040' \
  -H 'Content-Type: application/octet-stream' --data-binary '' "$BASE/media?name=too-big.bin"
assert "错误码 payload_too_large" "d.get('code')=='payload_too_large'"

# ---------------- E) P1-14 密码加盐 + 迁移 ----------------
echo "-- E) 密码加盐存储与旧格式自动迁移 --"
sp_get aicast.xml > "$TMP/aicast.before-pwd.xml"
TESTPWD='AiCast@Verify123'
LEGACY="$(python3 -c "import hashlib;print(hashlib.sha256('$TESTPWD'.encode()).hexdigest())")"
python3 - "$TMP/aicast.legacy.xml" "$TMP/aicast.before-pwd.xml" "$LEGACY" <<'PY'
import re,sys
out,src,legacy=sys.argv[1],sys.argv[2],sys.argv[3]
s=open(src,encoding='utf-8').read()
# 若设备上的密码已迁移成加盐格式，必须先摘掉 pwd_salt/pwd_hash，
# 否则 Prefs.checkPassword 只走加盐分支（见 Prefs.kt），注入的旧格式 pwd 会被无视
for tag in ('pwd_salt','pwd_hash'):
    s=re.sub(r'<string name="%s">[^<]*</string>\n?'%tag, '', s)
s=re.sub(r'<string name="pwd">[^<]*</string>', '<string name="pwd">%s</string>'%legacy, s)
if '<string name="pwd">' not in s:
    s=s.replace('</map>', '    <string name="pwd">%s</string>\n</map>'%legacy)
open(out,'w',encoding='utf-8').write(s)
PY
sp_put aicast.xml "$TMP/aicast.legacy.xml"
sp_get aicast_auth.xml > "$TMP/auth.orig.xml"
python3 - "$TMP/auth.orig.xml" "$TMP/auth.clean.xml" <<'PY'
import re,sys
src,out=sys.argv[1],sys.argv[2]
s=open(src,encoding='utf-8').read()
for tag in ('wrong','lockUntil','wrongSince'):
    s=re.sub(r'<(int|long) name="%s"[^/]*/>'%tag, '', s)
open(out,'w',encoding='utf-8').write(s)
PY
sp_put aicast_auth.xml "$TMP/auth.clean.xml"
restart_app
check "POST /auth 错误密码 → 401" 401 -X POST "${J[@]}" -d '{"password":"definitely-wrong"}' "$BASE/auth"
assert "错误码 wrong_password" "d.get('code')=='wrong_password'"
check "POST /auth 正确密码（旧无盐格式）→ 200" 200 -X POST "${J[@]}" -d "{\"password\":\"$TESTPWD\"}" "$BASE/auth"
assert "返回 type=token" "d.get('type')=='token'"
sleep 1
sp_get aicast.xml > "$TMP/aicast.migrated.xml"
if grep -q 'pwd_salt' "$TMP/aicast.migrated.xml" && grep -q 'pwd_hash' "$TMP/aicast.migrated.xml" \
   && ! grep -q '<string name="pwd">' "$TMP/aicast.migrated.xml"; then
  PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-54s %s\n' "旧哈希已迁移为 pwd_salt + pwd_hash" "ok"
else
  FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-54s\n      %s\n' "旧哈希已迁移为 pwd_salt + pwd_hash" \
    "$(grep -E 'pwd' "$TMP/aicast.migrated.xml" | tr '\n' ' ')"
fi
# 还原用户原本的密码与 auth 状态
sp_put aicast.xml "$TMP/aicast.before-pwd.xml"
sp_put aicast_auth.xml "$TMP/auth.orig.xml"
restart_app
check "还原后 GET /state → 200（环境复原）" 200 "${A[@]}" "$BASE/state"
adb shell run-as $PKG rm "$SP_DIR/aicast.orig.xml" "$SP_DIR/aicast.off.xml" \
  "$SP_DIR/aicast.legacy.xml" "$SP_DIR/aicast.before-pwd.xml" "$SP_DIR/auth.clean.xml" \
  "$SP_DIR/auth.orig.xml" "$SP_DIR/aicast.migrated.xml" >/dev/null 2>&1

# ---------------- F) token 封顶不应挤掉长期在线的会话 ----------------
# 复现场景：agent 昨天领了 token（还有 29 天才过期），今天有人反复调 /auth
# （测试脚本、写坏的重连逻辑）把 32 个名额刷满。旧实现按「最快过期」淘汰，
# 等价于按签发时间从老往新杀，长期在线的 agent 第一个掉线。
echo "-- F) 反复 POST /auth 不应把长期在线的会话挤下线 --"
sp_get aicast.xml > "$TMP/aicast.before-f.xml"
sp_get aicast_auth.xml > "$TMP/auth.before-f.xml"
# 灌一个已知密码 + 清空 token / 错误计数，保证这组用例能反复跑
python3 - "$TMP/aicast.F.xml" "$TMP/aicast.before-f.xml" "$LEGACY" <<'PY'
import re,sys
out,src,legacy=sys.argv[1],sys.argv[2],sys.argv[3]
s=open(src,encoding='utf-8').read()
for tag in ('pwd_salt','pwd_hash'):  # 同上：先摘掉加盐格式，保证注入的 pwd 生效
    s=re.sub(r'<string name="%s">[^<]*</string>\n?'%tag, '', s)
s=re.sub(r'<string name="pwd">[^<]*</string>', '<string name="pwd">%s</string>'%legacy, s)
if '<string name="pwd">' not in s:
    s=s.replace('</map>', '    <string name="pwd">%s</string>\n</map>'%legacy)
open(out,'w',encoding='utf-8',newline='\n').write(s)
PY
python3 - "$TMP/auth.before-f.xml" "$TMP/auth.F.xml" <<'PY'
import re,sys
src,out=sys.argv[1],sys.argv[2]
s=open(src,encoding='utf-8').read()
for tag in ('wrong','lockUntil','wrongSince'):
    s=re.sub(r'<(int|long) name="%s"[^/]*/>'%tag, '', s)
s=re.sub(r'<string name="tokens">[^<]*</string>\n?', '', s)
open(out,'w',encoding='utf-8',newline='\n').write(s)
PY
sp_put aicast.xml "$TMP/aicast.F.xml"
sp_put aicast_auth.xml "$TMP/auth.F.xml"
restart_app
check "POST /auth 领 token（模拟长期在线的 agent）" 200 "${J[@]}" \
  -d "{\"password\":\"$TESTPWD\"}" "$BASE/auth"
T0="$(BODY="$BODY" python3 -c "import json,os;print(json.load(open(os.environ['BODY'])).get('token',''))")"
if [ -z "$T0" ]; then
  echo "  取不到 token，F 组跳过"
else
  # 连打 40 次：远超 MAX_ACTIVE_TOKENS=32
  i=0; while [ $i -lt 40 ]; do
    curl -s -m 20 -o /dev/null "${J[@]}" -d "{\"password\":\"$TESTPWD\"}" "$BASE/auth"; i=$((i+1))
  done
  check "刷新 40 次 /auth 后老 token 仍可用" 200 -H "Authorization: Bearer $T0" "$BASE/state"
  sleep 1
  sp_get aicast_auth.xml > "$TMP/auth.F.after.xml"
  if tr ';' '\n' < "$TMP/auth.F.after.xml" | grep -qE '^[0-9a-f]{32}:[0-9]+:[0-9]+'; then
    PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-54s ok\n' "已落盘为新格式 <token>:<过期>:<签发>"
  else
    FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-54s\n' "已落盘为新格式 <token>:<过期>:<签发>"
  fi
  ACT="$(python3 - "$TMP/auth.F.after.xml" <<'PY'
import sys,re,time
s=open(sys.argv[1],encoding='utf-8').read()
m=re.search(r'<string name="tokens">([^<]*)</string>', s)
now=int(time.time()*1000)
print(sum(1 for e in (m.group(1).split(';') if m else []) if e and int(e.split(':')[1])>now))
PY
)"
  if [ "$ACT" -le 32 ]; then
    PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-54s %s 个\n' "活跃 token 数不超过上限 32" "$ACT"
  else
    FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-54s %s 个\n' "活跃 token 数不超过上限 32" "$ACT"
  fi
fi
sp_put aicast.xml "$TMP/aicast.before-f.xml"
sp_put aicast_auth.xml "$TMP/auth.before-f.xml"
restart_app
check "F 组还原后 GET /state → 200" 200 "${A[@]}" "$BASE/state"

echo
echo "通过 $PASS / 失败 $FAIL"
[ "$FAIL" = 0 ]
