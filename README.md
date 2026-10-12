# AiCast（爱投屏）

把闲置手机 / 平板 / Android TV 变成 AI 的窗口：局域网内投送任意 HTML、读取设备画像、控制显示。
**无中间件、无长连接、单向（agent → app）、即用即开。**

> 设计文档见 [docs/DESIGN.md](docs/DESIGN.md)（当前 v7）。
> 中文名「爱投屏」，命令行与包名一律用 `AiCast` / `com.aicast.app`。

## 它解决什么

AI 助手会写会想，但它没有「屏幕」——结果只能停在聊天窗口里。而家里吃灰的安卓电视、旧手机、
闲置平板，恰好是现成的显示屏：有电、有网、挂着不占地方。缺的只是**一条让 AI 把内容放上去的路**。

AiCast 的做法很直接：设备上装一个 app，它在局域网里开一个 HTTP server。agent 想显示什么，
就把一整页 HTML `POST` 过去，屏幕立刻显示。没有 SDK、没有账号、没有云端中转、没有长连接，
和你 `curl` 一台局域网机器没有区别。

```
                       局域网 HTTP（一发一收，无长连接）
┌──────────────────────────┐                        ┌──────────────────────────┐
│  Agent 主机               │  ── discover (mDNS) ─▶ │  闲置手机 / 平板 / TV      │
│  Claude / Cursor / 自研   │  ── POST /render ───▶  │  · WebView 渲染任意 HTML  │
│                          │  ◀── GET /events ────  │  · 内置 HTTP server(Ktor) │
│  AiCast Skill（CLI）      │      (long-poll 事件)  │  · mDNS 广播 _aicast._tcp │
└──────────────────────────┘                        │  · 公开/认证 + 投送总开关  │
        ▲ 只有 agent 发起                            │  · 待机时钟大屏（响应式）  │
        └─ app 永不主动联系 agent                    └──────────────────────────┘
```

## 三个设计取舍

| 取舍 | 说明 |
|---|---|
| **无中间件、单向连接** | app 是纯服务端，**永不主动联系 agent**；全部由 agent 发起一发一收的 HTTP。页面交互进设备侧环形缓冲，agent 用 `GET /events` long-poll 拉走 |
| **内容不写死** | app 不内置任何业务模板，只渲染你投来的 HTML。待办、仪表盘、简报、投票都由 agent 现场生成；待机时钟只是没内容时的默认皮肤 |
| **app 不按设备类型分支** | 同一套 app 跑在手机 / 平板 / 电视上，只负责①如实上报设备画像 ②让内容能被遥控器 D-pad 导航 ③原样渲染。**怎么适配设备，全部由 agent 决定** |

代价也很明确：它只在局域网内工作（想远程就两端各装 Tailscale 组私网），且需要 agent 主动轮询事件——
换来的是零部署、零依赖、零账号，以及「任何能发 HTTP 的东西都能投」。

## 设备画像：先问「你是谁」，再决定投什么

投内容前先 `GET /info`，app 会如实报告这台设备是什么、能怎么交互。这是 agent 生成「对的内容」的依据：

| 设备画像 | `interactionModel` | agent 应该生成 |
|---|---|---|
| 手机 · 竖屏 · 可触摸 | `touch` | 单列、可点按；按钮、表单、勾选待办；用 `AiCast.emit` 回传交互 |
| 平板 · 可触摸 | `touch` | 多列 / 网格仪表盘、卡片墙；更大点击区 |
| 电视 · 不可触摸 | `auto` | **自动播放**：轮播 / 滚动 / 动画，无需点击；巨字号、远距离可读、深色高对比 |
| 电视 · 有遥控器 | `remote` | 自动播放为主 + 可选 D-pad 焦点导航 |

画像包含 `class`（phone/tablet/tv）、`input`（touch/dpad/keyboard）、`screen`（分辨率 / dp / 横竖屏 /
刷新率）、`appearance`、`system` 等。**横竖屏切换、息屏、低电量**都会作为事件回流，agent 可据此重渲染。

## 模式与开关

| 模式 | 行为 |
|---|---|
| **公开 Public** | 局域网内任何 agent 无需凭据即可投送（适合客厅大屏这类「谁都能投」的场景） |
| **认证 Authenticated** | 需密码换 30 天 token（连错 5 次锁 60s），适合放在卧室 / 工位的设备 |

两个模式都受**投送总开关**约束：关掉后设备仍会被发现、仍上报画像，但 `/info` 声明 `accepting:false`，
`POST /render` 直接 403。亮度 / 主题 / 字号 / 音量四项参数读写常开（不再单独提供开关，但会如实上报）。
**只申请普通运行时权限**——无障碍、通知读取、设备管理员、悬浮窗一律不做，装上即用。

## 构建与运行

环境：**Android Studio（推荐）** 或 JDK 17 + Android SDK 35。

1. 用 Android Studio 打开本目录（会自动生成 gradle wrapper）。
2. 连接手机（开发者选项 → USB 调试）→ Run ▶ 安装 `app`。
   - 或命令行：`./gradlew :app:assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`，`adb install -r <apk>`。
3. 手机上打开「爱投屏」→ 显示待机时钟大屏（顶栏名称+公开徽标，底栏 IP/ID/开放权限）。
4. 首次运行点「允许通知」权限（前台服务需要，Android 13+）。

> 签名：debug/release 暂借用本机 `~/.android/debug.keystore`（Studio 首次构建自动生成）。没有该文件时先在 Studio 里构建一次，或删掉 `app/build.gradle.kts` 里的 `signingConfigs` 用默认 debug 签名。

## 快速验证（手机与电脑同一 WiFi）

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

# 改亮度（param 常开，公开模式也可直接调）
curl -X POST http://<IP>:43210/param -H 'Content-Type: application/json' -d '{"key":"brightness","value":"0.3"}'

# 想验证权限拦截：在设备上关掉「访问模式 → 投送」，再投一次
curl -X POST http://<IP>:43210/render -H 'Content-Type: application/json' -d '{"html":"<b>hi</b>"}'
# → 403 {"ok":false,"code":"cast_disabled","message":"该设备已关闭投送开关…"}
```

预期：待机页 → 投送后显示卡片 → 点按钮事件进 `/events` → 关掉投送总开关后 `/render` 被 403 拦下。

## 用 CLI 投送（推荐）

```bash
A="node skill/bin/aicast"
$A discover                                   # mDNS 发现（带画像摘要 + accepting）
$A info <dev>                                 # 取完整画像 —— 生成内容前必做
$A render <dev> --html-file page.html --wake  # 投送（中文内容必须先写 UTF-8 文件）
$A events <dev> --since 0                     # 拉页面回传的交互事件
```

设备清单与 token 存在 `~/.aicast/devices.json`。详细铁律见 [skill/SKILL.md](skill/SKILL.md)。

## HTTP 端点

| 方法 & 路径 | 作用 |
|---|---|
| `GET /health` | 存活探针 |
| `GET /info` | 名称 / 模式 / 能力 / 开放权限 / 完整设备画像 |
| `POST /auth` | 认证模式：密码换长期 token |
| `POST /render` | 投送整页 HTML 或加载 URL（界面不在前台时 202 排队） |
| `POST /message` | 向当前页注入增量数据 |
| `GET /state` | 当前页面与参数状态 |
| `GET/POST /param` | 读写 `brightness` / `theme` / `fontScale` / `volume` |
| `GET /history` | 投送历史 |
| `GET /themes` `POST /theme` `DELETE /theme` | 待机主题：内置 + agent 注册 |
| `POST /media` `GET /media/{id}` | 上传音频等（32MB 环形配额 + 72h TTL），支持 Range 流式播放 |
| `GET /events` | long-poll 拉交互 / 系统事件 |

端口默认 **43210**，被占用则顺延。**上表即当前代码真实实现的全部端点**，协议细节见 DESIGN.md §5。

## 页面 JS SDK

每个投送页都会被注入 `window.AiCast`，只做通信桥，不限制你用什么框架：

```js
AiCast.emit(type, data)      // 页面 → 事件缓冲（agent long-poll 拉走）
AiCast.onMessage(cb)         // 收 POST /message 注入的增量数据
await AiCast.getInfo()       // 页面内查设备画像
```

```html
<button onclick="AiCast.emit('approve',{id:42})">同意</button>
<div id="list"></div>
<script>AiCast.onMessage(d => list.innerHTML = d.html)</script>
```

## 目录

```
app/        Android 端（Kotlin + Compose + Ktor + WebView）
skill/      AiCast Skill：CLI + SKILL.md，给 agent 用
scripts/    本机验证脚本（smoke.sh / ui-text.py / icon-probe.py / cap.py 等）
examples/   演示 HTML（非协议内容，agent 可自由仿写）
docs/       DESIGN.md 设计文档、字体许可
```

## 其他

- 字体 JetBrains Mono 依 SIL Open Font License 使用，许可见 `docs/licenses/`。
- 不做 SSDP / UPnP / DLNA：只有装了 AiCast Skill 的 agent 会浏览 `_aicast._tcp`，不会被通用投屏软件搜到。
