---
name: aiface
description: 局域网内 AI 向安卓设备投送 HTML 内容并读取设备画像/交互回传。当用户要"给某台设备屏幕投内容/把手机或电视当显示面板/投送待办、简报、仪表盘、音频页面"时使用。
---

# aiface — AI 的局域网显示通道

aiface app 跑在目标安卓设备上，内置 HTTP server（默认端口 43210，被占用时顺延 43211…，
真实端口以 `/info` 上报为准）。本 Skill 提供零依赖 CLI（`bin/aiface`，Node 18+），
agent 用 Bash 调用：发现 → 鉴权 → 投送 HTML → （需要时）读回传。

**核心铁律：内容永远不写死，HTML 是唯一契约。先取画像，再生成内容。**

本文档只写**实机验证过的硬约束**与**已知的坑**。示例、模板都是参考做法不是规范——
放开想象力自由发挥，踩到新坑再把经验补回来。

## 标准工作流

```bash
A="node <本skill目录>/bin/aiface"

$A discover                      # 1. mDNS 发现（带画像摘要+权限清单）
$A auth <dev> --password <pwd>   # 2. auth 模式设备：向用户要密码，换长期 token
$A info <dev>                    # 3. 取完整画像 —— 生成内容前必做
$A render <dev> --html-file page.html --wake   # 4. 投送（先写到临时 UTF-8 文件）
```

- `<dev>` 可以是 deviceId、设备名或 `ip:port`，CLI 自动解析并把 token 长期存 `~/.aiface/devices.json`。
- 公开模式（`mode:public`）跳过 auth。`open` 数组决定该设备可用哪些能力。
- 发现失败时：问用户待机时钟底部显示的 IP → `$A discover --address 192.168.x.x` 手动登记；
  仍不通多半是设备重启后 app 没在跑（部分 ROM 拦开机自启属正常），请用户手动打开一次 app。

## 画像驱动生成决策表（§7.1，最关键）

| 画像 | interactionModel | 应生成的内容 |
|---|---|---|
| 手机 · 竖屏 · 触摸 | `touch` | 单列卡片流；按钮/表单/勾选待办 |
| 平板 · 触摸 | `touch` | 多列网格仪表盘；点击目标 ≥48px |
| 电视 · 不可触摸 | `auto` | **自动播放**轮播/动画，禁依赖点击；巨字号、深色高对比 |
| 电视 · 遥控 | `remote` | 自动播放为主 + D-pad 焦点导航 |

- **分支全在 agent 侧**。app 只如实上报画像、原样渲染 HTML。
- `/info` 返回 `internet`（`state: online/offline/unknown` + `validated`）——**联网情况由设备实测**，
  生成内容前先看它：offline 就改用内联资源/`/media`，别投一堆会挂的外链。

## HTML 生成铁律

1. **单文件 + 外链按画像取舍**：CSS/JS 内联；`internet.state=online` 时可引用外链真图/CDN，优先真图提质感。
   - **防盗链**：页面 baseURL 是 `https://aiface.local/`，外链图带此 Referer 会被部分图床拒。每页必写
     `<meta name="referrer" content="no-referrer">`；仍 403 就换源。
   - **降级兜底**：关键图配 `onerror`（换内联 SVG/占位块）。
   - 没有现成好图或需贴合内容时，手绘内联 SVG 仍是首选。
2. `<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">` 必写。
3. 高度用 `vh`/dvh 正常（app 已修视口）；顶部间距用
   `padding-top: max(<设计值>, calc(env(safe-area-inset-top,0px) + <设计值>))` 为挖孔/刘海留位，底部同理。
4. 中文内容一律**写入 UTF-8 文件后用 `--html-file` 投送**（Windows Git Bash 的 shell 参数会把中文变 GBK 乱码）。
5. 单页面 ≤ 4MB（`/render` 上限，指 HTML 本体）；空 body 的 render = 回待机。
6. **CSS 兼容基线 = Chromium 66**（小米电视 MiTV-MFTP0 实测停在 66.0.3359.158，无法通过应用商店升级；
   手机一般 ≥110）。下面是 **CSS.supports 实测表**，不是推测（`examples/tv-03-cssprobe.html` 可复测）：

   | 特性 | Chromium 66 | 说明 |
   |---|---|---|
   | `inset` | ✘ **丢弃** | 用 `top/right/bottom/left` |
   | `clamp()/min()/max()` | ✘ **丢弃** | 用固定 px；要自适应就"回退值在前、现代值在后"双声明 |
   | `env(safe-area-inset-*)` | ✘ **丢弃** | 直接用固定 padding，被丢也只是少留一点刘海位 |
   | `aspect-ratio` | ✘ 不支持 | 用 `padding-top` 百分比撑 |
   | `backdrop-filter` | ✘ 不支持 | 改用半透明纯色底 |
   | **`gap` / `column-gap`** | **✔ 支持** | ⚠️ 旧文档说会被丢弃，实测支持（Chrome 66 起引入 flex gap） |
   | `display:grid` `grid-template-columns` | ✔ 支持 | 可用，但 `grid-gap` 建议照旧写 `grid-gap` |
   | `position:sticky` `object-fit` CSS 变量 渐变 flexbox | ✔ 支持 | 放心用 |

   **`inset`/`clamp()` 被丢的后果极具欺骗性**：不是报错，而是整个
   `position:absolute; inset:0` 的元素**失去所有位置约束**，页面塌成内容挤在左上角一小坨。
7. **别依赖 JS 读视口**（电视实测坑）：Chromium 66 里脚本首次同步执行时
   `window.innerWidth` 可能是 **`0x0`**（首帧尚未完成布局）。需要量尺寸就延后一拍再测：
   `setTimeout(fn, 1000)` 或放 `window.onload`；纯 CSS 的 `%`/`vh` 不受影响。
8. **完工前自查溢出**：整屏页面最终会被套上 `overflow:hidden`，超出视口的部分**静默裁掉**，
   肉眼只觉得"下面怎么空了一块"。在页尾加一段自检把 `documentElement.scrollHeight - clientHeight`
   和关键元素的 `getBoundingClientRect()` 用 `Aiface.emit` 回传给 agent，`$A events` 一拉就知道
   （实测靠这招揪出过右栏溢出 252px；修法是把固定 px 高度改成 `flex:1` 自适应分配）。

## 交互回传（按需，不是日常必用）

页内 SDK 已自动注入，内容里随时可 `Aiface.emit(name, data)` 回传事件、`Aiface.onMessage` 收增量数据。
读取端**不需要常驻监听服务**：设备侧有事件环形缓冲（**最近 200 条**），
`$A events <dev> --since 0` 任何时候跑一次就能把攒下的事件一次取回；`--follow` 持续监听仅调试时用。
建议用在：需要知道用户点了什么（投票/勾选/游戏）、或排查页面为什么没反应。日常投送不取事件也完全成立。

**事件名会被设备按安全规则改写**，写页面时要按最终形态去 `events` 里捞：

| 页面里 emit 的名字 | 设备实际记录的 kind | 说明 |
|---|---|---|
| `todo.toggle`、`slide.played`、`remote.pause` …（含点号且非保留字） | 原样保留 | 绝大多数情况，照模板写即可 |
| `system.xxx`、`auth.xxx`、`app.xxx`、`device.xxx`、`capability.xxx`、`render.xxx`、`media.xxx`、`theme.xxx` | 降级为 `page.xxx` | 页面不可冒名系统事件，这是刻意约束 |
| `done`、`approve`（不含点号） | 加前缀 `ui.done`、`ui.approve` | 建议一开始就写带点号的名字 |

名字限 `[A-Za-z0-9_-.]`，最长 64 字符，过滤后为空则该事件被丢弃。
另外：seq 已持久化，设备重启不会回零；但**缓冲本身是进程内的，重启即清空** ——
此时 `since` 保持不变也安全，CLI 会识别设备的 `reset` 标记自动从头续拉。

## CLI 命令参考

| 命令 | 说明 |
|---|---|
| `discover [--timeout ms] [--address ip[:port]]` | mDNS 浏览；address 为手动回退 |
| `list` | 已保存设备（含 authed 状态） |
| `auth <dev> --password <pwd>` | 换 token 并持久化（30 天，多 token 并存） |
| `info <dev>` | 完整画像 JSON（含 internet 实测状态） |
| `render <dev> --html-file f \| --html '<h>' \| --url u \| --clear [--wake]` | 投送/加载 URL/回待机；wake=亮屏投 |
| `message <dev> --data '<json>' \| --data-file f` | 给页面发增量数据（透传给 `Aiface.onMessage`） |
| `events <dev> [--since N] [--timeout s] [--follow]` | 取回传事件（见上一节） |
| `state <dev>` | 当前渲染状态（hasContent/url/screenOn/theme/**pending**） |
| `param <dev> [key] [value]` | 读/设 **brightness / theme / fontScale / volume**（四选一） |
| `themes <dev>` / `theme <dev> <id>` | 列出主题 / 激活待机主题 |
| `theme <dev> --register (--html-file f \| --html '<h>') [--name N] [--id ID] [--activate]` | 注册自定义 HTML 主题 |
| `theme <dev> --remove <id>` | 删除自定义主题（内置不可删；**删的若是正在用的那个，会自动退回 native**） |
| `media <dev> <file> [--name n]` | 上传媒体到即将播放它的那台设备，返回 `absoluteUrl`（见「音频投送」） |
| `media <dev> --list` / `--remove <id>` | 列出 / 删除设备上的媒体项 |
| `forget <dev>` | 删除已存设备记录 |

所有输出为 stdout JSON；错误 `{"ok":false,...}` 且退出码 1。401 提示先 auth，429 为密码锁 60s
（响应带 `retryAfterMs`，连错 5 次触发，**计数落盘，重启 app 也躲不掉**）。

**注意 `param` 只剩四个键**：`rotationLock` / `idleDimSeconds` 从未真正实现，现在会被明确拒绝（400），
别再指望用它们锁方向或控制息屏。

### 投送结果：200 / 202 要分清

- **200**：已上屏。
- **202 `{ok:true, queued:true}`**：设备界面不在前台（用户退到桌面 / app 被清后台），
  内容已排队，**设备回到前台会自动呈现**。这是正常路径，不要当成失败重试。
  确认方式：`$A state <dev>` 的 `pending: true` 即表示还在队列里；让用户打开一次 app 或
  `render --wake` 之后再查 `pending` 变 false、内容已上屏。

### 直接写 HTTP 调用（不用 CLI）时必读

用 CLI 就不用管这些；只有当你要自己 `curl`/`fetch` 时才需要：

- token 只认 **`Authorization: Bearer <token>`**，`?token=` 已废弃（一律 401）。
- 写操作必须带 **`Content-Type: application/json`**，否则 **415**；`POST /media` 是 raw body 例外，
  但必须自带 `Content-Length`。
- 带 `Origin` 头的跨站请求直接 **403**（反 CSRF）：只能从本机/agent 侧直连。
- 未开放的能力返回 **403 `permission_denied`**，看 `/info` 的 `capabilities` 与 `open` 再动手。
- `/history`、`/state`、`/events` 等在 auth 模式下都需要 token，别假设它有公网般的匿名读。

## 模板脚手架（templates/）

验证过的起点，不是唯一写法，详见 `templates/README.md`：
`todo-interactive`（手机待办）、`auto-briefing`（轮播简报）、`rich-article`（图文范式）、
`remote-briefing`（TV 遥控轮播）、`lunch-roulette-{touch,tv}`（决策小游戏，⚠ 页内变量勿命名 `name`）、
`audio-player-tv`（音频播放页）。多端并发案例见 `examples/`。

## 音频投送

设备出声完全可行（TV 真实出声已验证）。形式随你想象：播放器、有声简报、带 BGM 的氛围屏都行。实机坑：

- **旧 WebView（Chromium 66）WebAudio 现场合成不可靠**（声音塌成噪音）；播现成音轨（`<audio>`）稳定。手机一般都行。
- **版权**：只用公有领域/CC/免版税资源并在页面标注来源；流行歌曲不要投。
- mp3 是旧设备最稳格式；`<audio>` 别加 `crossorigin`（可能反而加载失败）。
- **自动播放可能被拦**：先直接试 `play()`，失败留"按 OK/点一下"手势入口。
- **选源以设备实测为准**：PC 上快的站电视可能反复缓冲；国内可达通常好于境外站，
  别把某个站当固定依赖，多个候选逐源试。
- **没有合适外链时上传到播放设备本机**：`aiface media <播放设备> <文件>`，页面 `<audio src>` 用返回的
  `absoluteUrl`，本机流式（Range/seek 均支持）。**单个文件 ≤32MB**（受 32MB 配额约束，
  不是协议上写的 64MB）；超限会被 `Content-Length` 预检挡回 **413**，只能压小再传。
  **铁律：每台设备只和 agent 联络，不要把一台设备当另一台的"网盘"跨设备拉流。**
- **低配电视存储紧张是常态**：app 有 32MB 环形配额 + 72h 自动清理，空间不足回 **507**——
  此时把音频压小再传（降码率/裁时长/80kbps 单声道），或 `--remove` 清旧项，不要改投别的设备。
  配额满时**淘汰最旧的媒体项，本次刚传的不会被自己挤掉** —— 也就是说老链接会失效，
  页面里引用 `/media/<id>` 前先 `$A media <dev> --list` 确认它还在。
- 短音频（≤约 2.9MB，80kbps 单声道 ≈ 4-5 分钟）可直接 **data URI 嵌进页面**，零依赖零缓冲。

## 待机主题（首页）

设备空闲无投送时的首页可切换：**native**（原生时钟，省电默认）/ 内置 HTML 主题（`aurora`、`minimal-light`）/
**agent 注册主题**（`theme --register`，存设备本地、重启保留）。主题是常驻首页：`render` 内容会盖上去，
`--clear` 回到主题。生成主题 = 生成自包含 HTML 首页，**遵守《HTML 生成铁律》全部条款**。

主题专属规矩（app 会强制兜底，但写的时候就要对）：
1. 静态首页不得滚动：整屏 `100vh/100vw` 布局，内容超屏自己重排（app 注入 `overflow:hidden`）。
2. viewport 加 `maximum-scale=1, user-scalable=no`。
3. 布局用绝对定位/居中；TV 退出走保留键 `←`=历史、`MENU`=设置，**主题不要占用这两个键**。
4. 用户也可在设备设置页 → 待机主题 里点选切换。

## 安全与礼仪

- 永不猜测/爆破密码；向用户索取，token 长期复用（**同一设备最多保留 32 个活跃 token**）。
  满了会淘汰**最新签发**的那个，所以反复调 `auth` 的客户端只会不断替换自己的旧 token、
  不会挤掉长期在线的 agent；真被挤掉了重新 `auth` 一次即可。
- 投送前若 `state` 显示 hasContent（正显示别人的内容），先确认再覆盖。
- 提醒用户：真用之前把测试密码换掉（设置页可改，可"撤销所有 token"）。
- 不该做的：不要试图绕过 403 权限，也不要把一台设备当另一台的"网盘"跨设备搬媒体 ——
  能力矩阵与配额都是用户对该设备的明确授权。
