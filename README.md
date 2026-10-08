# aiface

把闲置手机 / 平板 / Android TV 变成 AI 的窗口：局域网内投送任意 HTML、读取设备画像、控制显示。
**无中间件、无长连接、单向（agent → app）、即用即开。**

> 设计文档见 [docs/DESIGN.md](docs/DESIGN.md)（当前 v7）。本仓库处于 **M1**：app 内置 server 骨架。

## 当前状态（M1）

| 已实现 | 计划中 |
|---|---|
| ✅ Ktor 内置 HTTP server（端口 43210） | ⏳ 密码鉴权 `/auth`（M3） |
| ✅ `/health` `/info`（完整设备画像） | ⏳ 设置页：命名/模式/权限开关（M2） |
| ✅ `/render`（HTML/URL/回待机） | ⏳ 双向 `wait_for_event` 打磨、截图（M5） |
| ✅ `/message`（JS 桥增量注入） | ⏳ 系统控制 TTS/手电等（M7） |
| ✅ `/param` `/state` `/events`(long-poll) | ⏳ 屏幕/作息定时（M8） |
| ✅ JS SDK `window.Aiface`（含遥控按键上报） | |
| ✅ mDNS 广播 `_aiface._tcp`（TXT 带画像摘要） | |
| ✅ 前台 Service + WakeLock（开机自启尽力而为：MIUI TV 实测拦截三方自启，重启后手动打开一次即可） | |
| ✅ 待机时钟大屏（Compose 响应式） | |

## 构建与运行

环境：**Android Studio（推荐）** 或 JDK 17 + Android SDK 35。

1. 用 Android Studio 打开本目录（会自动生成 gradle wrapper）。
2. 连接手机（开发者选项 → USB 调试）→ Run ▶ 安装 `app`。
   - 或命令行：`./gradlew :app:assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`，`adb install -r <apk>`。
3. 手机上打开 aiface → 显示待机时钟大屏（顶栏名称+公开徽标，底栏 IP/ID/开放权限）。
4. 首次运行点「允许通知」权限（前台服务需要，Android 13+）。

> 签名：debug/release 暂借用本机 `~/.android/debug.keystore`（Studio 首次构建自动生成）。没有该文件时先在 Studio 里构建一次，或删掉 `app/build.gradle.kts` 里的 `signingConfigs` 用默认 debug 签名。

## M1 验收（手机与电脑同一 WiFi）

```bash
# 把 <IP> 换成待机页底栏显示的 IP
curl http://<IP>:43210/health                 # → ok
curl http://<IP>:43210/info | jq .device      # → class/touch/dpad/interactionModel
curl http://<IP>:43210/info | jq .screen      # → 分辨率/dp/横竖屏

# 投送演示卡片（把 demo 页面变成 JSON 包装）
python - <<'EOF'
import json,urllib.request
html=open('examples/demo-card.html',encoding='utf-8').read()
req=urllib.request.Request('http://<IP>:43210/render',
    data=json.dumps({'html':html}).encode(),headers={'Content-Type':'application/json'})
print(urllib.request.urlopen(req).read().decode())
EOF

# 手机上点「同意」，然后拉事件：
curl "http://<IP>:43210/events?since=0&timeout=2"   # → [{"seq":...,"kind":"page.approve",...}]

# 增量消息（页面会显示收到的 JSON）
curl -X POST http://<IP>:43210/message -H 'Content-Type: application/json' -d '{"html":"<b>hello</b>"}'

# 改亮度（默认未开放 param → 应返回 403 permission_denied；这正是逐项权限的验证）
curl -X POST http://<IP>:43210/param -H 'Content-Type: application/json' -d '{"key":"brightness","value":"0.3"}'
```

预期：待机页 → 投送后显示卡片 → 点按钮事件进 `/events` → `/param` 演示逐项权限拦截。

## 目录

```
app/        Android（Kotlin + Compose + Ktor + WebView）
examples/   演示 HTML（非协议内容，agent 可自由仿写）
skill/      aiface Skill（CLI 形态，M4 实现）
protocol/   REST/事件 schema（M3 固化）
docs/       DESIGN.md
```

## 协议速览

见 DESIGN.md §5（REST 端点）、§7（设备画像）、§9（JS SDK）。要点：内容**不写死**，`render` 接受任意 HTML；待机时钟只是默认皮肤。
