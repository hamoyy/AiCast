# aiface 开发文档 (v7 · app 统一无分支 · Skill 无 MCP · 单向连接)

> 把闲置手机/平板/Android TV 变成 AI 的「窗口」，也变成一块可公开投送的「展示大屏」。
> 装 app 打开即用；agent 端一键装 **aiface Skill**：**搜索 → 取设备画像 → 按画像生成 → 投内容**，无长连接、无中间件、无需技术背景。

**两条铁律（v7 确立）**
- **app 不区分设备类型**：同一套 app 跑在手机/平板/电视上，只负责①如实上报设备画像 ②兼容遥控器(D-pad)操作 ③渲染 agent 投来的任意 HTML。**内容如何适配设备，全部由 agent 决定**，app 不做任何分支判断。
- **单向连接**：app 是纯服务端，**永不主动联系 agent**；只有 agent 发起请求。事件/截图通过 agent 主动 long-poll 拉取。

---

## 0. v7 相对 v6 的变更（响应你的反馈）

| 变更 | 说明 |
|---|---|
| 电视只支持 **Android TV** | 不做其他 TV 系统 |
| **app 端不按设备类型分支** | app 统一；只保证 D-pad/遥控兼容 + 画像上报 + 渲染；适配交给 agent（§7.1 移到 agent/Skill 侧） |
| **Skill 去掉 MCP** | 改为 CLI/脚本形态：mDNS 发现 + HTTP 调用，更简单、依赖更少 |
| 明确 **单向连接** | app 永不主动连 agent；事件靠 agent long-poll |

---

## 0.1 v6 相对 v5 的变更

| 变更 | 说明 |
|---|---|
| **多形态终端**：手机 / 平板 / Android TV | app 自适应不同设备 |
| **设备画像 (device profile)** | `/info` 与发现结果带：设备类型、**是否可触摸**、是否有遥控器/D-pad、**横竖屏**、大屏/观看距离等 |
| **画像驱动内容生成** | 可触摸 → 交互内容；电视(不可触摸) → **自动播放**；由 **agent** 据此分支生成（§7.1） |
| **强化 Skill 职责** | Skill 内置"按画像生成"的策略与脚手架，决定 AI 能发挥多大能力（§12） |

---

## 0.2 v5 相对 v4 的变更

| 变更 | 说明 |
|---|---|
| 公开模式 **完全开放，但逐项权限可配** | app 内对每类操作单独开关（§2.2） |
| 新增 **设备名称/备注/昵称** | 用户命名，发现与 info 都带上 |
| **发现结果带开放情况** | 返回 `name + mode + 开放权限清单` |
| **砍掉全部特殊权限** | 无障碍/通知读取/设备管理员/设为桌面/悬浮窗 都不做 |
| **发现协议定为仅 mDNS** | 不做 SSDP/UPnP，不会被通用投屏软件搜到 |
| 非公开设备 **去二维码、改密码** | app 内设密码；agent 索取并长期保存 |
| 新增 **待机时钟大屏 + 美化** | 未投内容前显示全屏时钟 + 信息条 |

---

## 1. 架构（两层 · 点对点 · 无状态 · DLNA 式）

```
┌────────────────────────────┐   局域网 HTTP（请求/响应，无长连接）   ┌──────────────────────────┐
│  Agent 主机                 │ ──────────────────────────────────▶ │  闲置手机/平板/TV · app   │
│  (Claude / Cursor / 自研)   │ ◀────────────────────────────────── │  ├─ WebView 渲染          │
│                            │   响应：info / 截图 / 事件(long-poll)│  ├─ 内置 HTTP server(Ktor)│
│  aiface Skill（一键安装）   │                                      │  ├─ mDNS 广播 (NSD)       │
│   ├─ mDNS 发现 _aiface._tcp │                                      │  ├─ 公开/认证 + 权限开关   │
│   └─ CLI/脚本（无 MCP）     │                                      │  ├─ 待机时钟大屏(响应式)  │
└────────────────────────────┘                                      │  └─ 前台 Service 保活     │
        ▲ 仅 agent 发起 ────────────────────────────────────────┐  └──────────────────────────┘
        └─ app 永不主动连 agent（单向）                          └─ D-pad/遥控可导航
```

**交互模型**（agent 永远发起，单向）：`discover（mDNS）→ GET /info → POST /render`，需要时 `GET /events`(long-poll)、`GET /screenshot`。
- **无长连接 / 单向**：app 是纯服务端，不会主动联系 agent；全部由 agent 发起一发一收的 HTTP。
- **增量刷新**：`POST /message` → app 经本地 JS 桥注入当前页面。
- **事件回流**：页面交互进 app 环形缓冲；agent `GET /events?timeout=30` long-poll 拉取（仍是 agent 发起）。
- **远程（可选，无中间件）**：手机 + agent 主机各装 Tailscale 组私网即可。

---

## 2. 模式 · 权限 · 命名

### 2.1 两种模式
| 模式 | 行为 | 凭据 |
|---|---|---|
| **公开 Public** | 局域网内任何 agent 无需凭据即可调用「已开放」的操作 | 无 |
| **认证 Authenticated** | 需密码；agent 发现后向用户索取密码并**长期保存** | 密码 → 换长期 token |

> **非公开设备不再用二维码/配对码**（没有 agent 能扫码）。改为：app 设置里**设一个密码**；agent 发现该设备时提示"该设备需要密码，请输入"，验证通过后 Skill 把密码/token 存本地，后续免输。

### 2.2 公开模式的逐项权限开关（核心）
公开 = 完全开放，但**开放哪些由用户在 app 里逐项勾选**。每项独立开关：

| 权限项 | 默认 | 含义 |
|---|---|---|
| `allowRender` | ✅ | 允许投送 HTML / 加载 URL |
| `allowMessage` | ✅ | 允许向页面增量注入数据 |
| `allowParam` | ⬜ | 允许改参数（亮度/主题/音量/字号/旋转） |
| `allowScreen` | ⬜ | 允许屏幕控制（亮/息/调暗/夜间） |
| `allowSystem` | ⬜ | 允许系统动作（TTS/音量/手电/震动/通知…） |
| `allowScreenshot` | ⬜ | 允许截屏回传 |
| `allowEval` | ⬜ | 允许页面执行 JS（高权限，默认关） |

- 认证模式下，密码通过 = 拥有上面所有「已启用」的权限。
- 公开模式下，匿名 agent 只能调用「已开放(✅)」的项；调用未开放项返回 `403 + 提示去 app 开启`。

### 2.3 设备名称/备注
- app 设置里可填**名称/昵称**（如「客厅大屏」「卧室面板」「工位副屏」）。
- 名称随 mDNS TXT 与 `/info`、发现结果一起返回，agent 据此识别"这是哪台/在哪"。
- 另有不可改的**唯一标识** `deviceId`（用于精确寻址）。

---

## 3. 发现协议：仅 mDNS（已决定）

- 用 Android **NSD** 注册 `_aiface._tcp`，**只有 aiface Skill 会浏览这个服务类型**。
- **不实现 SSDP/UPnP/DLNA 标准协议** → 不会被系统投屏、电视助手等通用软件发现，满足"不想被其他软件搜到"。
- mDNS **TXT 记录**携带轻量信息，让发现列表无需逐个连接即可展示：
  ```
  deviceId=dev_xxx · name=客厅大屏 · class=tv · touch=0 · orient=landscape
   · mode=public · open=render,message · authRequired=false
  ```
  （`class`：phone/tablet/tv；`touch`：1/0；详细画像仍需 `GET /info`，TXT 只放摘要用于快速筛选）
- **发现回退**：个别路由/手机屏蔽 mDNS 时，Skill 支持手动输入 app 显示的地址（`GET /health` 校验）。

---

## 4. 零门槛上手（用户视角）

```
1. 手机：安装 aiface → 打开
   → 默认进入待机时钟大屏（显示名称/IP/deviceId/公开状态）
   → 设置里：填名称；选「公开」或「认证」；公开则逐项勾选开放权限；认证则设密码
2. agent 主机：技能商店装「aiface」Skill
3. 对 agent："连接 aiface" → Skill mDNS 发现，列出：
      · 客厅大屏（公开，可投送）  · 卧室面板（需密码）
   → 公开设备直接连；需密码的设备，agent 向你要密码并保存
4. 完成："把今天待办投到客厅大屏"
```
不开端口、不装 Docker、不碰云端、不写配置、不扫码。

---

## 5. HTTP 协议（app 内置 server · Ktor）

> 统一 JSON。认证模式除 `/discover-info`、`/auth` 外需带 `Authorization: Bearer <token>`。

| 方法 & 路径 | body / query | 作用 |
|---|---|---|
| `GET /health` | — | 存活探针（发现回退） |
| `GET /info` | — | 名称/模式/能力/开放权限清单/屏幕信息（§6） |
| `POST /auth` | `{ password }` | 认证模式：密码换长期 token |
| `POST /render` | `{ html }` 或 `{ url, wake? }` | 投送整页 HTML / 加载 URL |
| `POST /message` | `{ data }` | 注入当前页面 `Aiface.onMessage`（增量） |
| `POST /eval` | `{ code }` | 页面执行 JS（需 `allowEval`） |
| `GET /state` | — | 当前页面 title/url、参数、屏幕状态 |
| `GET/POST /param` | `{ key, value }` | 读写参数（需 `allowParam`） |
| `POST /screen` | `{ action:"on/off/dim/night" }` | 屏幕控制（需 `allowScreen`） |
| `POST /system` | `{ action, ... }` | TTS/音量/手电/震动/通知（需 `allowSystem`，§8） |
| `GET /screenshot` | `?format&quality&maxW` | base64 image（需 `allowScreenshot`） |
| `GET /events` | `?since&timeout` | **long-poll** 取交互/系统事件（§9） |
| `POST /media` | raw body（需 `Content-Length`），`?name=` 原名 | 上传音频等到 `filesDir/media/`（复用 `render` 权限），返回 `{id,url}`；单文件 ≤64MB |
| `GET /media/{id}` | 支持 `Range` | **公开读**，流式回文件（206 + `Accept-Ranges`）——页面 `<audio src>` 用 `http://<设备IP>:43210/media/<id>` |
| `GET /media` / `DELETE /media?id=` | — | 列目录（token） / 删除 |

- **媒体配额（低配电视的存储保护）**：`filesDir/media/` 32MB 环形上限 + 72h TTL，超配额丢最旧；
  上传前 StatFs 检查（存完至少给系统盘留 64MB），不足回 `507 { code:"no_space" }`。
  **媒体只上传到"即将播放它的那台设备"，该设备从自己的 `/media` 流式播放——每台设备只与 agent 联络，
  不把另一台当网盘跨设备拉流。** 低配电视空间紧张是常态，靠设备端配额自保护 + 收到 507 时瘦身
  （降码率/转 80kbps 单声道/裁时长/`--remove` 旧项）应对，而非转投他台。app 不按设备类型分支。
  实测：设备本机 320kbps 整曲（11MB）边下边播 4:36 零卡顿。
- 混合内容前提：首页 baseURL 是 `https://aiface.local`，媒体走 `http://ip:43210` → WebView 需
  `MIXED_CONTENT_ALWAYS_ALLOW`（已设）。

- 权限不足统一返回 `403 { code:"permission_denied", need:"allowParam" }`。
- **无离线队列**；事件环形缓冲（默认最近 200 条 / 10 分钟），按 `since` 补拉。

---

## 6. Skill 命令 / 能力（CLI，无 MCP）

> Skill 通过内置 CLI/脚本（agent 用 Bash 调用）暴露下列能力，每个命令直接映射 §5 的 REST 端点。不依赖 MCP。

| 命令 | 参数 | 映射 |
|---|---|---|
| `aiface discover` | — | mDNS 浏览，返回 **name/class/touch/mode/开放权限/address** |
| `aiface list` | — | 已发现/已保存设备 |
| `aiface auth` | `address?, password` | `POST /auth`，保存 token |
| `aiface info` | `deviceId` | `GET /info`（完整设备画像） |
| `aiface render` | `deviceId, html?, url?, wake?` | `POST /render` |
| `aiface message` | `deviceId, data` | `POST /message` |
| `aiface eval` | `deviceId, code` | `POST /eval` |
| `aiface state` | `deviceId` | `GET /state` |
| `aiface param` | `deviceId, key, value?` | `/param` |
| `aiface screen` | `deviceId, action` | `POST /screen` |
| `aiface system` | `deviceId, action, args` | `POST /system` |
| `aiface screenshot` | `deviceId` | `GET /screenshot` |
| `aiface events` | `deviceId, since?, timeout?` | `GET /events`（long-poll） |
| `aiface media` | `deviceId, file, name?` | `POST /media`（上传音频等到设备本地，返回可嵌 `<audio>` 的局域网 URL；见 §5/§13） |

> `aiface discover` 返回里就带"形态+开放情况"，agent 不用连上即可知道每台能做什么、要不要密码。
> **无内容专用命令**：待办/仪表盘/表单都由 agent 生成 HTML 走 `aiface render`。

**`aiface discover` 返回示例**
```jsonc
[
  {"deviceId":"dev_a","name":"客厅大屏","address":"192.168.1.20:43210",
   "class":"tv","touch":false,"orientation":"landscape",
   "mode":"public","authRequired":false,"open":["render","message"]},
  {"deviceId":"dev_b","name":"卧室平板","address":"192.168.1.31:43210",
   "class":"tablet","touch":true,"orientation":"landscape",
   "mode":"auth","authRequired":true,"open":["render","message","param","screen"]}
]
```
> 发现阶段就能区分"客厅是台不可触摸的电视(做自动播放)"还是"卧室是可触摸平板(做交互)"。详细画像用 `aiface info`。

---

## 7. 设备画像上报（`GET /info`）

> 这是 agent 生成"对的内容"的依据。**先取画像，再决定交互还是自动播放。**

```jsonc
{
  "deviceId":"dev_a","name":"客厅大屏","mode":"public","authRequired":false,
  "open":["render","message"],          // 当前调用者可用的权限项
  "device":{
    "class":"tv",                       // phone | tablet | tv | wearable
    "manufacturer":"Sony","model":"BRAVIA","osVersion":"12",
    "input":{
      "touch":false,                    // 是否可触摸（手机/平板=true，电视多为 false）
      "dpad":true,                      // 遥控器方向键（电视=true）
      "keyboard":false,"pointer":false,"stylus":false
    },
    "interactionModel":"auto",          // touch | remote | auto —— 给 agent 的核心提示
    "largeScreen":true,                 // 大屏/远距离观看
    "recommendedFontScale":1.6          // 远看建议放大字号
  },
  "screen":{"widthPx":3840,"heightPx":2160,"widthDp":960,"heightDp":540,
            "density":4.0,"densityDpi":640,"orientation":"landscape",
            "orientationLocked":true,"refreshRate":60,
            "safeInsets":{"top":0,"bottom":0,"left":0,"right":0}},
  "appearance":{"darkMode":true,"fontScale":1.0},
  "system":{"android":"12","battery":null,"charging":true,"screenOn":true},
  "capabilities":["render","message","param","screen","tts","volume","torch",
                  "vibrate","notify","clipboard","launchApp"],
  "app":{"version":"0.1.0","evalEnabled":false}
}
```

**Android 检测方式**
- `class`：`Configuration.uiMode & UI_MODE_TYPE_MASK` → `UI_MODE_TYPE_TELEVISION`/`WATCH`；`smallestScreenWidthDp ≥ 600` 判平板，否则手机；`hasSystemFeature(FEATURE_LEANBACK)` 佐证 TV。
- `touch`：`PackageManager.hasSystemFeature(FEATURE_TOUCHSCREEN)` + 实测；`dpad`：leanback / 遥控特征。
- `orientation`/`orientationLocked`：当前 `Configuration.orientation` + 系统旋转锁；TV 通常恒为 landscape。
- `interactionModel`（派生）：`touch=true → "touch"`；`touch=false && dpad=true → "remote"`；`touch=false && dpad=false → "auto"`。

**横竖屏/暗色/低电量变化**写入事件缓冲，agent `get_events` 拉到 `capability.changed` 后重渲染。

### 7.1 画像驱动的内容生成策略（Skill 内置，agent 据此发挥）

| 设备画像 | interactionModel | agent 应生成 |
|---|---|---|
| 手机 · 竖屏 · 可触摸 | `touch` | 单列、可点按/滑动；按钮、表单、勾选待办；用 `Aiface.emit` 回传交互 |
| 平板 · 横/竖 · 可触摸 | `touch` | 多列/网格仪表盘、卡片墙；触摸交互 + 更大点击区 |
| 电视 · 横屏 · 不可触摸 | `auto` | **自动播放**：定时轮播/滚动/动画，无需点击；巨字号、远距离可读、深色高对比 |
| 电视 · 有遥控器 | `remote` | 自动播放为主 + 可选 D-pad 焦点导航（可聚焦元素、按键事件） |

- **横竖屏切换** → `capability.changed` → agent 重新按新方向生成（竖屏单列 ↔ 横屏多列）。
- 这套策略写进 **SKILL.md**（§12），让任何 agent 装上 Skill 就"懂"如何按画像发挥，而不必自己摸索。
- **强调：以上分支全在 agent/Skill 侧。app 自身不读 `class` 做任何差异化逻辑**——app 只保证①准确上报画像 ②WebView 内容可被遥控器 D-pad 导航 ③把投来的 HTML 原样渲染。

---

## 8. 权限与可控能力（仅普通权限 · 即用即开）

> **不做任何需要手动到系统设置授予的特殊权限**（无障碍/通知读取/设备管理员/设为桌面/悬浮窗一律不实现）。只保留装上即可用的普通运行时权限。

| 能力 | 权限 | AI 可做什么 |
|---|---|---|
| 网络/发现/服务 | `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE` | 内置 server、mDNS |
| 常亮/保活 | `WAKE_LOCK`、`FOREGROUND_SERVICE` | 息屏不断 server、点亮屏幕 |
| 开机自启 | `RECEIVE_BOOT_COMPLETED` | 重启自动恢复展示（手机有效；2026-09 实测小米电视开机会 force-stop 三方应用并丢弃广播，需用户在"我的应用"手动打开一次） |
| 亮度/主题/旋转 | 窗口属性（无需权限） | 亮度、夜间、字号、旋转 |
| 音频/震动 | `MODIFY_AUDIO_SETTINGS`、`VIBRATE` | 音量、TTS 播报、震动 |
| 手电筒 | `FLASHLIGHT` | 开关手电 |
| 通知 | `POST_NOTIFICATIONS` | 发通知 |
| 剪贴板 | 前台可读 | 读写剪贴板 |
| 启动 app/链接 | Intent | 打开其他 app / URL |

**`POST /system` 动作**：`speak`(TTS) · `volume`(set/up/down/mute) · `torch`(on/off) · `vibrate` · `notify` · `clipboard`(get/set) · `launchApp`(包名/URL) · `media`(play/pause)。
> WiFi/蓝牙开关、定位、相机、传感器等**暂不做**（要么需特殊权限、要么意义不大），留作未来。

---

## 9. 双向沟通：通用 JS SDK（唯一的"框架"）

app 给每个页面注入 `window.Aiface`，只做通信桥：
```js
Aiface.emit(type, data)     // 页面 → 事件缓冲（agent long-poll 拉走）
Aiface.onMessage(cb)        // 收 POST /message 注入的增量数据
await Aiface.getInfo()      // 页面内查 §7 能力
```
**纯 HTML 闭环**
```html
<button onclick="Aiface.emit('approve',{id:42})">同意</button>
<div id="list"></div>
<script>Aiface.onMessage(d => list.innerHTML = d.html)</script>
```
事件分类：`page.*`（JS 自定义）· `capability.changed` · `system.*`（息屏/亮屏/低电量/重连）。

---

## 10. 待机时钟大屏（美化重点）

未投送内容前，app 默认显示一个**全屏时钟面板**（也是公开模式的"招牌"）：

```
┌─────────────────────────────┐
│  客厅大屏            ● 公开   │ ← 顶栏：设备名 + 公开/认证状态徽标
│                             │
│         09:41               │ ← 大字号时钟（跟随 theme 明暗）
│      2026年9月21日 周一       │ ← 日期
│                             │
│  🌡 26°C  ☀ 晴   （可选）     │ ← 预留位：未来可放天气等
│                             │
│ IP 192.168.1.20:43210       │ ← 底栏信息条
│ ID dev_a   开放:投送/增量     │ ← 唯一标识 + 当前开放权限
└─────────────────────────────┘
```
- **顶栏**：设备名称 + 公开状态徽标（公开=绿点"开放投送"；认证=锁标）。
- **中部**：时钟/日期，明暗随 `theme`，夜间自动暗色低亮。
- **底栏**：IP:端口、`deviceId`、已开放权限摘要（方便人眼确认这台能干嘛）。
- **响应式而非分支**：用相对单位（vw/vh/`sp`）+ 约束布局，同一套待机页在手机竖屏、平板、电视横屏巨幕都自动撑满好看，**不写 `if(class==tv)` 这类分支**（遵守 v7 铁律）。
- **遥控器兼容**：待机页与投送内容的可聚焦元素支持 D-pad 焦点移动 + 选中（Android TV 上可用遥控操作）。
- 收到 `render` 即切到投送内容；内容页可调 `Aiface` 回到待机或 agent `render` 空内容回时钟。
- 视觉风格：极简、深色友好、大字号、圆角，Material 3。

### 10.1 待机主题系统（首页可切换）

空闲首页不再只有原生时钟一种，而是可切换的「主题」，并把自定义空间交给 agent：

| 类别 | kind | source | 呈现 | 说明 |
|---|---|---|---|---|
| 原生时钟 | native | builtin | Compose（`ClockScreen`） | 默认、零 WebView、省电常驻 |
| 极光 / 素白 | html | builtin | WebView 家模式 | 随包 `assets/themes/<id>.html` |
| agent 自定义 | html | agent | WebView 家模式 | `POST /theme` 注册，存 `filesDir/themes/`，重启保留 |

- **主题=一整个自包含 HTML 首页**，渲染进 WebView（家模式，**不计入投送内容**），同样注入 SDK，可 `emit`/`onMessage` 交互；遵守《HTML 生成铁律》全部条款。
- **分层**：投送内容(WebView) > 待机主题；`render` 内容即盖上主题，`render --clear` 回到主题（非原生）。设置/历史为 Compose 覆盖层，打开时隐藏最上层 WebView。
- **入口保证**：HTML 主题铺满整屏会盖住原生齿轮/右滑，app 在右上角浮一小对原生「历史/设置」入口；投送内容覆盖时自动隐藏。TV 待机仍可用方向键「左」开历史。
- **选择/持久化**：当前主题存 `Prefs.theme`；设置页「待机主题」点选切换，agent 侧 `param theme=<id>` 或 `POST /theme {activate:true}` 激活。
- **接口（§5）**：`GET /themes`（列出+当前）、`POST /theme {html,name?,id?,activate?}`（注册）、`DELETE /theme?id=`（删 agent 主题，内置不可删）。注册/删除复用 `render` 权限。
- **app 不预设视觉、不按设备分支**：只负责装载主题；一套机制跑全形态。


---

## 11. Android App 内部设计

```
app/src/main/java/.../aiface/
├─ MainActivity            // 全屏 WebView；setTurnScreenOn/setShowWhenLocked
├─ ClockScreen             // 待机时钟大屏（§10，Compose，响应式无分支）
├─ SettingsScreen          // 名称、模式(公开/认证)、逐项权限开关、设密码、作息
├─ AifaceService           // 前台 Service：托管 server、保活、WakeLock、作息定时
├─ server/HttpServer       // Ktor：§5 REST + long-poll /events
├─ server/NsdAdvertiser    // NSD 注册 _aiface._tcp（TXT 带 name/class/touch/mode/open）
├─ core/DeviceInfo         // 采集画像(class/touch/dpad/orientation/大屏)；onConfigurationChanged 监听变化
├─ core/AuthManager        // 模式、密码校验、token、逐项权限判定
├─ core/EventBus           // 事件环形缓冲 + long-poll 挂起队列
├─ web/AifaceWebView       // WebView 配置 + 注入 JS SDK + D-pad 焦点导航
├─ web/JsBridge            // @JavascriptInterface：emit/getInfo
├─ render/HtmlRenderer     // render/message/eval（原样渲染，不按设备分支）
├─ param/ParamController   // 亮度/常亮/主题/音量/旋转
├─ screen/ScreenController // on/off/dim/night + AlarmManager 作息
├─ system/SystemController // §8 系统动作（TTS/音量/手电/震动/通知/剪贴板/启动）
├─ capture/ScreenCapture   // PixelCopy 截图
└─ store/LocalCache        // 最后视图 + 配置，重启自恢复
```
**要点**
- **一套 app 跑全形态**：手机/平板/Android TV 用同一代码，**不读 `class` 做差异化逻辑**；`DeviceInfo` 只负责采集并上报画像。
- **Android TV / 遥控器**：manifest 声明 leanback（`android.software.leanback`、banner、`FEATURE_TOUCHSCREEN` 设为 not-required），WebView 与待机页支持 **D-pad 焦点导航**，无需触摸即可操作。
- Ktor（HTTP + long-poll 挂起）；前台 Service + `PARTIAL_WAKE_LOCK` 息屏不断网；开机自启尽力而为（电视 ROM 普遍拦截，见权限表）。
- UI 用 **Jetpack Compose + Material 3**，响应式布局（相对单位）适配竖屏/横屏/巨幕。
- WebView 禁文件域、来源白名单。

---

## 12. Skill 内部设计（agent 端 · 无 MCP · 决定 AI 能发挥多大）

Skill 不只是"传输管道"，它承载了 **让任意 agent 都会用好 aiface 的知识**。三块职责：

1. **传输与发现**：mDNS 发现、密码换 token、调 REST/long-poll、保存设备清单（由内置 CLI 完成）。
2. **画像驱动的生成策略**（最关键）：SKILL.md 里写明 §7.1 的决策表与铁律——
   - "**永远先 `aiface info`**，按 `class/touch/orientation/interactionModel` 分支生成"；
   - 电视(`auto`) → 自动播放轮播、巨字、深色高对比，**不要放需要点击的交互**；
   - 手机/平板(`touch`) → 交互内容，按钮/表单用 `Aiface.emit` 回传；
   - 遥控器(`remote`) → 自动播放为主，可加 D-pad 焦点导航；
   - 旋转/暗色 → 监听 `capability.changed` 重新生成；
   - 用 `viewport`/相对单位做响应式，按 `recommendedFontScale` 调字号。
3. **可复用脚手架**：随 Skill 附带 HTML 模板片段（自动播放轮播、触摸仪表盘、竖屏卡片流），agent 取用并填充——是"生成辅助"，不是协议里的固定内容（仍遵守"内容不写死"原则）。

```
aiface-skill/
├─ SKILL.md            // 何时用 + 画像驱动生成铁律(§7.1) + 发现/认证流程 + 模板用法
├─ bin/aiface          // CLI：discover/info/render/message/events… (§6 命令)
├─ lib/
│   ├─ discover.*      // mDNS 浏览 _aiface._tcp（回退：手动地址 /health）
│   ├─ http-client.*   // 调 §5 REST + long-poll；密码换 token
│   └─ config store    // 长期保存 deviceId→token/密码、名称备注
└─ templates/          // 自动播放/触摸交互/竖屏卡片流 等 HTML 脚手架
```

> **不依赖 MCP**：agent 直接用 Bash 调 `aiface` CLI，依赖更少、安装更轻。
> 一句话：**app 负责如实上报"我是什么设备、能做什么"，Skill 负责教会 AI"对什么设备该生成什么"**。两者结合，才能让闲置手机/平板/电视都发挥到位。

---

## 13. 安全（宽松策略，仅留必要）

- 模式 + **逐项权限开关**：公开默认只开 `render/message`，敏感项用户自行决定是否开放。
- 认证模式：密码换 token，Skill 长期保存。
- `eval_js` 默认关。
- WebView 来源白名单、禁文件域。
- 可在 app 内一键：关闭 mDNS 广播（隐身）、改密码、撤销 token、切模式。

---

## 14. 仓库结构

```
aiface/
├─ docs/DESIGN.md      // 本文件
├─ protocol/           // 共享：REST schema / 事件类型（Kotlin ↔ Skill 对齐）
├─ app/                // Android (Kotlin, Gradle, Compose)：内置 server + WebView
├─ skill/              // agent 端 Skill（SKILL.md + CLI/脚本，无 MCP）
├─ examples/           // 可选 HTML 演示：todo / dashboard / form（仅示例）
└─ README.md
```

---

## 15. 里程碑

| 阶段 | 交付 | 验收 |
|---|---|---|
| **M0** | 本开发文档 | 你确认 |
| **M1** | App 骨架：Ktor server + WebView + `/info`、`/render`、`/param`；前台 Service 保活 | 同网 curl 投 HTML 上屏；改亮度生效 |
| **M2** | 待机时钟大屏（**响应式，不按设备分支**）+ 设置页（名称/模式/权限开关/密码）+ 美化 | 同一套待机页在手机竖屏/平板/TV 横屏都撑满好看；可命名/切模式/勾权限 |
| **M3** | **设备画像采集** `GET /info`（class/touch/dpad/interactionModel/orientation/大屏）+ mDNS 广播（TXT 带 name/class/touch/mode/open）+ 密码鉴权 `/auth` | 发现列出名称+形态+开放情况；非公开输密码连通；TV 恒横屏判定正确 |
| **M4** | Skill（**CLI，无 MCP**）：`discover/auth/info/render` + **SKILL.md 画像生成策略 + templates 脚手架** | agent 一键发现→按画像投送（TV 自动播放 / 手机交互） |
| **M5** | 双向：JS SDK + `/events` long-poll + `capability.changed` 自适应 + 截图 | 点页面按钮 agent 近实时收到；旋转/暗色触发重渲染；拿到截图 |
| **M6** | **遥控器/D-pad 兼容**（leanback、焦点导航、Android TV 可装可操作） | TV 上无触摸，用遥控即可浏览待机页与可聚焦内容 |
| **M7** | 系统控制 `/system`（TTS/音量/手电/震动/通知/剪贴板/启动） | 逐项授权后 AI 能控制对应能力 |
| **M8** | 屏幕/作息（on/off/dim/night + 定时）+ 开机自启（尽力而为）+ 加固 + `examples/` | 夜间自动暗色/息屏；手机重启恢复，电视重启提示手动打开；多设备分别控制 |

---

## 16. 已确认决策（截至 v7）

- ✅ 网络：局域网点对点，**无中间件、无长连接、单向（agent→app）**
- ✅ App 技术：**Kotlin + WebView + Ktor + Jetpack Compose**；一套 app 跑手机/平板/**Android TV**，不按设备分支，**兼容遥控器 D-pad**
- ✅ Agent 端：**Skill + CLI（不用 MCP）**；内容适配由 agent 据画像生成
- ✅ 发现：**仅 mDNS**（不做 SSDP/UPnP，不被通用投屏软件发现）
- ✅ 模式：公开（完全开放 + 逐项权限开关）/ 认证（密码，去二维码）；设备可命名
- ✅ 权限：仅普通运行时权限，不做特殊权限
- ✅ 内容：不写死，一切走 `render`

## 17. 仅剩两个小确认

1. **UI 技术**：待机时钟与设置页用 **Jetpack Compose**（推荐，已默认）还是传统 XML View？
2. **时钟大屏**：除时间/IP/ID/权限外，要不要预留**天气**位（需联网取，可后续做）？

> 若以上无异议，我从 **M1（app 内置 server 骨架）** 开始写代码。
