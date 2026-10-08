#!/usr/bin/env python3
"""双端联动编排：「今晚吃什么」家庭投票。

手机(touch)点票 → emit vote.cast → 本脚本拉到事件 → POST /message 给电视(remote)
→ 电视票数 +1 并回传 tv.updated → 本脚本再校验一遍，闭环完成。

用法:
  python scripts/dual-orchestrate.py                 # 投两端并跑 60s 转发循环
  python scripts/dual-orchestrate.py --once          # 只投两端，不进循环
  python scripts/dual-orchestrate.py --seconds 30
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

PHONE = "dev_4dfd12b8"          # Xiaomi Mi 10 · touch · auth 模式
TV = "dev_3262e229"             # MiTV-MFTP0 · remote · public 模式
PHONE_SERIAL = "14444ae5"       # 手机的 adb  serial，仅 --auto 模拟点按时用到
EX = "C:/Users/wapse/Documents/code/aiface/examples"
APK_ROOT = "C:/Users/wapse/Documents/code/aiface"
DEBUG = False
ADB = r"C:\Users\wapse\AppData\Local\Android\Sdk\platform-tools\adb.exe"
CLI = ["node", "C:/Users/wapse/Documents/code/aiface/skill/bin/aiface"]


def run(args, **kw):
    return subprocess.run(CLI + args, capture_output=True, text=True,
                          encoding="utf-8", cwd="C:/Users/wapse/Documents/code/aiface", **kw)


def cli_json(args):
    p = run(args)
    try:
        return json.loads(p.stdout)
    except Exception:
        raise SystemExit(f"命令失败: {args}\n{p.stdout}\n{p.stderr}")


def http_post_json(url, obj, timeout=8):
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def events(dev, since, wait=1):
    """$A events 输出是 NDJSON（一行一个 JSON），逐行解析。

    ⚠ wait 默认给 1：CLI 的 --timeout 默认 25 秒长轮询，一次调用要阻塞 26s
      （实测 25.9s）。循环里必须用短超时，否则整脚本看起来像卡死，
      而且等到它返回时手机早就自动息屏，后续 adb tap 全打在锁屏上、静默无效。
    """
    p = run(["events", dev, "--since", str(since), "--timeout", str(wait)])
    out = []
    for line in p.stdout.splitlines():
        line = line.strip()
        if not line or '"kind"' not in line:
            continue
        try:
            out.append(json.loads(line))
        except Exception:
            pass
    return out


def addr(dev):
    for d in cli_json(["list"])["devices"]:
        if d["deviceId"] == dev:
            return d["address"]
    raise SystemExit(f"找不到设备 {dev}")


def tap_coords():
    """从手机页回传的 vote.layout 拿卡片中心点（CSS px），换算成 adb 的物理像素坐标。"""
    for e in events(PHONE, 0):
        if e.get("kind") != "vote.layout":
            continue
        d = e["data"]["data"]
        dpr = d.get("dpr") or 1
        taps = {k: (int(v["cx"] * dpr), int(v["cy"] * dpr)) for k, v in d.get("taps", {}).items()}
        print(f"   手机布局：视口 {d.get('viewport')} · dpr {dpr} · 纵向溢出 {d.get('overflowY')}px")
        return taps
    raise SystemExit("没拿到 vote.layout —— 手机页面可能还写着旧版本，重新 render 一次")


def wake(serial):
    """KEYCODE_WAKEUP(224)：只点亮屏幕，不像 POWER(26) 那样把亮着的屏又按灭。"""
    subprocess.run([ADB, "-s", serial, "shell", "input", "keyevent", "224"], capture_output=True)
    time.sleep(0.6)


def tap(cid, taps):
    x, y = taps[cid]
    wake(PHONE_SERIAL)          # 息屏时的 tap 会被吞掉，且不报错，很难查
    r = subprocess.run([ADB, "-s", PHONE_SERIAL, "shell", "input", "tap", str(x), str(y)], capture_output=True)
    print(f"   tap {cid:<9} @({x},{y}) rc={r.returncode}"
          + (f" err={r.stderr.decode('utf-8','ignore').strip()[:120]}" if r.returncode else ""))
    time.sleep(2.2)


def main():
    once = "--once" in sys.argv
    global DEBUG
    DEBUG = "--debug" in sys.argv
    secs = 60
    if "--seconds" in sys.argv:
        secs = int(sys.argv[sys.argv.index("--seconds") + 1])

    tv_addr = addr(TV)
    ph_name = addr(PHONE)

    print("== 1) 并发投送两端 ==")
    for dev, f in ((PHONE, f"{EX}/dual-phone-vote.html"), (TV, f"{EX}/dual-tv-tally.html")):
        r = cli_json(["render", dev, "--html-file", f, "--wake"])
        print(f"   {dev:<14} {r.get('type')} ok={r.get('ok')}")

    time.sleep(2.5)
    base_ph = max([e["seq"] for e in events(PHONE, 0)] or [0])
    base_tv = max([e["seq"] for e in events(TV, 0)] or [0])
    print(f"   事件基线 seq：手机 {base_ph} · 电视 {base_tv}")
    if once:
        return

    state = {"ph": base_ph, "tv": base_tv, "fwd": 0}

    def once_poll():
        """拉一轮：手机端 vote.cast → POST /message 转发给电视；同时打印电视回传。"""
        ph = events(PHONE, state["ph"])
        tv_e = events(TV, state["tv"])
        if DEBUG:
            print(f"   [dbg] since ph={state['ph']} → {len(ph)} 条 | tv={state['tv']} → {len(tv_e)} 条")
        for e in ph:
            state["ph"] = max(state["ph"], e["seq"])
            if e.get("kind") != "vote.cast":
                continue
            d = e.get("data", {}).get("data", {})
            msgs = []
            if d.get("prev"):                       # 改票：先给旧选项 −1，再给新选项 +1
                msgs.append({"action": "vote", "id": d["prev"], "delta": -1})
            msgs.append({"action": "vote", "id": d["id"]})
            for m in msgs:
                http_post_json(f"http://{tv_addr}/message", m)
            state["fwd"] += len(msgs)
            tail = f"（旧票 {d['prev']} −1）" if d.get("prev") else ""
            print(f"   seq {e['seq']:>3}  手机: {d.get('name')} +1 → 转发电视 {tail}")
        for e in tv_e:
            state["tv"] = max(state["tv"], e["seq"])
            if not e.get("kind", "").startswith("tv."):
                continue
            d = e.get("data", {}).get("data", {})
            extra = f"{d.get('name')}={d.get('count')}票 / 共{d.get('total')}票" if "name" in d else ""
            print(f"           ← 电视回传 {e['kind']} {extra}")

    def sweep(rounds=5, gap=0.7):
        for _ in range(rounds):
            once_poll()
            time.sleep(gap)

    print("\n== 2) 联动：手机 vote.cast → 电视 /message ==")
    if "--auto" in sys.argv:
        # 顺序执行（不用后台线程）：点一次 → 收一轮 → 转发一次，因果链看得清楚
        taps = tap_coords()
        for cid in ("hotpot", "noodle", "takeout"):
            tap(cid, taps)
            sweep()
    else:
        print("   （请在手机上点候选；轮询中…）")
        end = time.time() + secs
        while time.time() < end:
            once_poll()
            time.sleep(1.0)

    print("\n== 3) 汇总 ==")
    print(f"   转发到电视的消息数: {state['fwd']}")
    tv_evs = [e for e in events(TV, base_tv) if e.get("kind") == "tv.updated"]
    print(f"   电视端 tv.updated 回传数: {len(tv_evs)}")
    if tv_evs:
        last = tv_evs[-1]["data"]["data"]
        print(f"   电视最后状态: {json.dumps(last, ensure_ascii=False)}")
    ph_evs = [e for e in events(PHONE, base_ph) if e.get("kind") == "vote.cast"]
    print(f"   手机端 vote.cast 数: {len(ph_evs)}")


if __name__ == "__main__":
    main()
