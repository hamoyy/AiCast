#!/usr/bin/env bash
# aiface 协议冒烟脚本（对真机跑一遍 §5 端点）
#   用法: ./scripts/smoke.sh <ip[:port]> [token]
#   无 token 时只跑公开部分（health/info）
# 需要：curl、python3
set -u
# 只跟局域网设备通信，绕开本机 http_proxy —— 否则所有探测都拿不到连接（http_code=000），
# 而 000 != 404 会被判为通过，检查形同虚设（第一次在带代理的机器上跑就是这样）。
export no_proxy='*'
export NO_PROXY='*'
ADDR="${1:?用法: $0 <ip[:port]> [token]}"
TOK="${2:-}"
case "$ADDR" in
  *:*) BASE="http://$ADDR"; PORT="${ADDR#*:}"; HOST="${ADDR%:*}" ;;
  *)   BASE="http://$ADDR:43210"; PORT=43210; HOST="$ADDR" ;;
esac
BODY_FILE=".smoke-body.tmp"
rm -f "$BODY_FILE"
trap 'rm -f "$BODY_FILE"' EXIT

PASS=0; FAIL=0
# check <用例名> <期望状态码> <curl 参数...>
check() {
  local name="$1" want="$2"; shift 2
  local code
  code="$(curl -s -m 20 -o "$BODY_FILE" -w '%{http_code}' "$@")"
  if [ "$code" = "$want" ]; then
    PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-52s %s\n' "$name" "$code"
  else
    FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-52s got=%s want=%s\n      %s\n' \
      "$name" "$code" "$want" "$(head -c 220 "$BODY_FILE")"
  fi
}
# assertJson <说明> <python 表达式（用 d 表示响应对象）>
assertJson() {
  local name="$1" expr="$2"
  local out; out="$(BODY="$BODY_FILE" python3 -c "
import json,os
d=json.load(open(os.environ['BODY']))
print(bool($expr))
" 2>/dev/null)"
  if [ "$out" = "True" ]; then
    PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %-52s %s\n' "$name" "ok"
  else
    FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %-52s %s\n      %s\n' \
      "$name" "$out" "$(head -c 220 "$BODY_FILE")"
  fi
}
A=(-H "Authorization: Bearer $TOK")
J=(-H 'Content-Type: application/json')

echo "== 目标 $BASE =="
echo "-- 公开端点 --"
check "GET /health → 200" 200 "$BASE/health"
check "GET /info → 200"   200 "$BASE/info"
assertJson "/info 上报真实端口" "d['app']['port'] == $PORT"

if [ -z "$TOK" ]; then
  echo "-- 未给 token，跳过鉴权与写操作用例 --"
  echo "通过 $PASS / 失败 $FAIL"; [ "$FAIL" = 0 ]; exit $?
fi

echo "-- 鉴权门禁（auth 模式）--"
check "GET /state 带头 → 200"                   200 "${A[@]}" "$BASE/state"
check "GET /state 无 token → 401"               401 "$BASE/state"
check "GET /state ?token= → 401（禁用 query 传票）" 401 "$BASE/state?token=$TOK"
check "GET /history 无 token → 401（不再公开）"  401 "$BASE/history"
check "GET /themes 带头 → 200"                  200 "${A[@]}" "$BASE/themes"

echo "-- CSRF / 写操作内容类型 --"
check "POST /render text/plain → 415"           415 -X POST -H 'Content-Type: text/plain' -d 'x=1' "$BASE/render"
check "POST /render 跨站 Origin → 403"          403 -X POST "${J[@]}" -H 'Origin: https://evil.example' -d '{}' "$BASE/render"
check "DELETE /media 无 JSON 头 → 415"          415 -X DELETE "${A[@]}" "$BASE/media?id=nope"
check "OPTIONS 跨站预检 → 403（不给 CORS 头）"   403 -X OPTIONS -H 'Origin: https://evil.example' "$BASE/render"
check "OPTIONS 同源预检 → 204"                    204 -X OPTIONS -H "Origin: http://$HOST" "$BASE/render"

echo "-- 投送与 URL 安全 --"
check "POST /render（agent 正常投送）→ 200"     200 -X POST "${A[@]}" "${J[@]}" -d '{"html":"<b>ok</b>"}' "$BASE/render"
check "POST /render url=javascript: → 400"      400 -X POST "${A[@]}" "${J[@]}" -d '{"url":"javascript:alert(1)"}' "$BASE/render"
check "POST /message 非法 JSON → 400"           400 -X POST "${A[@]}" "${J[@]}" -d '{oops' "$BASE/message"
check "POST /param brightness → 200"            200 -X POST "${A[@]}" "${J[@]}" -d '{"key":"brightness","value":"0.6"}' "$BASE/param"
check "POST /param rotationLock → 400（未实现不假装成功）" 400 -X POST "${A[@]}" "${J[@]}" -d '{"key":"rotationLock","value":"1"}' "$BASE/param"

echo "-- 事件流（新结构 {ok,lastSeq,reset,events[]}）--"
check "GET /events → 200" 200 "${A[@]}" "$BASE/events?since=0&timeout=1"
assertJson "events 是对象且带 lastSeq/reset/events" "isinstance(d,dict) and {'events','lastSeq','reset'} <= set(d)"
check "GET /events since=极大 → reset 提示" 200 "${A[@]}" "$BASE/events?since=999999999&timeout=1"
assertJson "since 超界时返回 reset=true" "d.get('reset') is True"

echo "-- 能力清单真实性（capabilities 每项必须有对应端点）--"
# P1-16 回归：/info.capabilities 自称「已实现的端点」，一旦混入没有端点的项，
# agent 照着画像去调必然 404。这里逐项探测，404 或没映射都算失败。
PROBE="$(curl -s -m 8 "${A[@]}" "$BASE/info" | python3 -c '
import json,os,sys
m={"render":"GET /render","themes":"GET /themes","media":"GET /media",
   "message":"POST /message","param":"GET /param","theme":"GET /themes",
   "volume":"GET /param","brightness":"GET /param","fontScale":"GET /param",
   "state":"GET /state","events":"GET /events?since=0&timeout=1","history":"GET /history"}
for c in json.load(sys.stdin).get("capabilities",[]):
    s=m.get(c)
    print(c, *(s.split(" ") if s else ["?","?"]))
' | tr -d '\r')"   # Windows 上 python 的 stdout 会把 \n 变成 \r\n，不strip掉的话
                   # path 末尾带 \r → curl 拿到非法 URL → 一律 000（检查形同虚设）
while read -r cap meth path; do
  [ -z "$cap" ] && continue
  if [ "$meth" = "?" ]; then
    FAIL=$((FAIL+1))
    printf '  \033[31m✗\033[0m %-52s %s\n' "能力 $cap 有对应端点" "got=未上报任何已知端点"
    continue
  fi
  code="$(curl -s -m 20 -o "$BODY_FILE" -w '%{http_code}' -X "$meth" "${A[@]}" "$BASE$path")"
  if [ "$code" = "404" ]; then
    FAIL=$((FAIL+1))
    printf '  \033[31m✗\033[0m %-52s %s\n' "能力 $cap → $meth $path 可达" "got=404（上报了未实现的能力）"
  else
    PASS=$((PASS+1))
    printf '  \033[32m✓\033[0m %-52s %s\n' "能力 $cap → $meth $path 可达" "$code"
  fi
done <<< "$PROBE"

echo
echo "通过 $PASS / 失败 $FAIL"
[ "$FAIL" = 0 ]
