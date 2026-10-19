---
name: aicast
version: 1.0.3
agent_created: true
description: 局域网内向装了 AiCast app 的安卓设备投送 HTML 页面，并按设备画像（触摸/遥控/电视）决定内容形态、读交互回传。当用户要"给手机或电视投一页内容/把闲置安卓屏当 AI 的显示面板/投待办、简报、仪表盘、音频页"时使用。不是系统投屏：视频镜像、屏幕镜像、Miracast、AirPlay、DLNA 都不归它管。
---

# AiCast（爱投屏）— AI 的局域网显示通道

AiCast app 跑在目标安卓设备上，内置 HTTP server（默认端口 43210，被占用时顺延 43211…，
真实端口以 `/info` 上报为准）。本 Skill 提供零依赖 CLI（`bin/aicast`，Node 18+），
agent 用 Bash 调用：发现 → 鉴权 → 投送 HTML → （需要时）读回传。

**核心铁律：内容永远不写死，HTML 是唯一契约。先取画像（含内核档位 `device.webview`），再生成内容。**

本文档只写**实机验证过的硬约束**与**已知的坑**。示例、模板都是参考做法不是规范——
放开想象力自由发挥，踩到新坑再把经验补回来。

## 开工前：环境自检

先确认运行时，再走工作流。详细安装、故障处理与降级见 `references/setup-guide.md`：

```bash
node --version                                            # 需 ≥ 18（CLI 零依赖，只要 Node）
python <本skill目录>/scripts/check_environment.py          # 机器可读的就绪状态（Windows 可能是 python3）
```

| 状态 | 含义 | 下一步 |
|---|---|---|
| `ready` | Node 在且够版本 | 直接进工作流，不再讲配置 |
| `needs_setup` | Node 缺失 | 按 `references/setup-guide.md` 装好 Node 18+ 再重检 |
| `unavailable` | CLI 不在预期位置 | 确认本 Skill 装在 `~/.workbuddy/skills/aicast/`，或照检查器输出里的路径修 |

设备侧状态（app 是否在前台、要不要密码、`accepting` 开关、配额）**不进环境检查**——它随用随变，
交给工作流里各自的失败分支处理，不提前拦人。

## 标准工作流

```bash
A="node <本skill目录>/bin/aicast"

$A discover                      # 1. mDNS 发现（带画像摘要+权限清单）
$A auth <dev> --password-stdin   # 2. auth 模式设备：向用户要密码（走管道），换长期 token
$A info <dev>                    # 3. 取完整画像 —— 生成内容前必做
$A render <dev> --html-file page.html --wake   # 4. 投送（先写到临时 UTF-8 文件）
```

- `<dev>` 可以是 deviceId、设备名或 `ip:port`，CLI 自动解析并把 token 长期存 `~/.aicast/devices.json`。
- 公开模式（`mode:public`）跳过 auth。`open` 数组决定该设备可用哪些能力。
- 发现失败时：先看 `discover` 输出里的 `mdnsDiag`——5353 被占用、组播被网络禁掉都会写明原因，少走弯路。
  然后问用户待机时钟底部显示的 IP → `$A discover --address 192.168.x.x` 手动登记
  （设备端口若不是默认 43210，要写成 `192.168.x.x:43211`）；
  仍不通多半是设备重启后 app 没在跑（部分 ROM 拦开机自启属正常），请用户手动打开一次 app。

### 设备可能"不接受投送"——发现得到 ≠ 能投

`/info` 与 `discover` 都会给出 **`accepting`**（老版本 app 没有该字段，视为 `true`）。
它为 `false` 表示**用户在 app 里把「投送」总开关关了**——设备照样在线、照样被搜索到，
但 `POST /render` 会直接失败（403 `cast_disabled`）。

**遇到 `accepting:false` 时不要自己反复重试或换设备**，`acceptingHint` 里已经写好一句可
直接转述给人看的话，照原话告诉用户，请他到设备上打开「爱投屏」→ 访问模式 → 投送 再让你重试。
CLI 在 `render` 撞上 `cast_disabled` 时也会原样打印这段话。

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
- **`device.webview.major` 是上面这张表之外的第二根轴**，决定能用哪些语法与 CSS（见《HTML 生成铁律》第 6 条）。
  它和 `interactionModel` 是**正交**的：一台电视可能既要"巨字号自动轮播"（布局维度），
  又只有内核 66（语法维度）。别只看 `class` 就开始写代码。
- `device.recommendedFontScale`（≥40 寸给 1.8、≥20 寸给 1.4、其余 1.0）与 `device.largeScreen`
  是大屏远看的字号提示，**直接乘到基准字号上**；`device.diagonalInches` 是算好的物理对角线。
- `screen.cutoutInsets` 给出刘海/挖孔的四个方向避让值（dp = CSS px），挖孔避让直接用它（铁律第 3 条）。

## HTML 生成铁律

1. **单文件 + 外链按画像取舍**：CSS/JS 内联；`internet.state=online` 时可引用外链真图/CDN，优先真图提质感。
   - **防盗链**：页面 baseURL 是 `https://aicast.local/`，外链图带此 Referer 会被部分图床拒。每页必写
     `<meta name="referrer" content="no-referrer">`；仍 403 就换源。
   - **降级兜底**：关键图配 `onerror`（换内联 SVG/占位块）。
   - 没有现成好图或需贴合内容时，手绘内联 SVG 仍是首选。
2. `<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">` 必写。
   **画布尺寸读 `screen.widthDp × heightDp`** —— 它就是页面里的 CSS 像素（= dp），
   实测 Mi 10 手机是 `393×851`、老手机是 `360×640`、电视是 `960×540`。
   **别拿 `widthPx/heightPx` 当 CSS 尺寸**：那是物理像素，差一个 `dpr` 倍（Mi 10 上是 2.75 倍）。
   字号倍率的字段也在 `screen.fontScale`（`appearance` 里只有 `darkMode`）。
3. **挖孔/刘海避让读画像的 `screen.cutoutInsets`（单位 dp，与 CSS px 同量级），不要用 `env()`。**
   实测本 app 的 WebView 是沉浸式全屏，`viewport-fit=cover` 下 `env(safe-area-inset-*)` **恒为 `0px`**
   （带挖孔的那台 Mi 10 上，页面里读它依然读到 0）；旧内核更直接 —— 连 `env()` 都不认，
   整条声明被静默丢弃。**直接写固定值**，例如 `padding-top:33px`（值取 `cutoutInsets.top`；
   无挖孔的设备四项都是 0，写不写都行）。高度用 `vh` 正常（app 已修视口，实测 `100vh` = 屏高）；
   `dvh/svh/lvh` 则要求 major ≥ 108。
4. 中文内容一律**写入 UTF-8 文件后用 `--html-file` 投送**（Windows Git Bash 的 shell 参数会把中文变 GBK 乱码）。
5. 单页面 ≤ 4MB（`/render` 上限，指 HTML 本体）；空 body 的 render = 回待机。
6. **内容能用哪些 CSS / JS，由画像的 `device.webview.major` 决定 —— 没有"统一基线"这回事。**
   内核版本与 `osVersion`、型号**没有因果关系**：WebView 是独立可升级的 APK，厂商电视的
   固件通道常年冻结版本。实测三台设备分别是 **65**（Android 7.1 手机）、**66**（Android TV）、
   **116**（Android 13 手机）。照一台设备的基线写、投到另一台上，后果往往是**静默失效**。

   > `webview` 缺失或 `major:0`（老版本 app、或内核尚未初始化）→ **按最保守档（< 66）写**。
   > 下面的 ✔/✘ 都是在 **65** 和 **116** 上逐项实测出来的，不是查表推测。

   **① 保守档安全集（65 实测 ✔，全机队通用，拿不准就用这套）**

   > 已闭环验证：同一份只用这套写法的页面（三列 `grid` + `grid-gap`、`flex` 行用 `margin` 代 gap、
   角标用 `top/right` 定位），在 65 与 116 两台设备上量出的元素坐标**完全一致**。

   `flexbox` · `display:grid` + `grid-gap` · `position:sticky` · `object-fit` · `calc()` ·
   CSS 变量 · 渐变 · `transition/animation/transform/filter` · `mix-blend-mode` ·
   `writing-mode` · `user-select` · `pointer-events` · `-webkit-line-clamp` ·
   `scroll-behavior` · `overscroll-behavior` · `100vh` · 固定 px 字号 ·
   `@supports` / `@media` / `@keyframes` · `top/right/bottom/left` 定位。

   **② 保守档必须避开（65 实测 ✘，写了整条声明被静默丢弃）**

   `inset` · `clamp() / min() / max()` · `env()` · `aspect-ratio` · `backdrop-filter` ·
   `dvh / svh / lvh` · `scroll-snap` · `accent-color` · `margin-inline` / `inset-inline` ·
   `text-wrap:balance` · `color-mix()` · `oklch()` · `:is()` / `:where()` · `:has()` ·
   `:focus-visible` · `@container` · `prefers-color-scheme` · `prefers-reduced-motion`。

   - `inset` / `clamp()` 被丢的后果**极具欺骗性**：不是报错，而是 `position:absolute; inset:0`
     的元素**失去全部位置约束**，页面塌成内容挤在左上角一小坨。
   - **`gap` 这个坑最值得单独记**：65 上 `gap` 和 `row-gap` 实测是 `false`，而 `column-gap`
     是 `true`（它来自更老的多列布局）。66~83 只是**认识 `gap` 这个名字**，**flex 容器里要
     Chrome 84 才真正生效**。所以 **flex 布局别用 `gap`** —— 用 `margin`，或干脆改
     `display:grid` + `grid-gap`（65 实测 `true`，全档安全）。
   - 深色模式判断用画像的 `appearance.darkMode`，**别用 `@media (prefers-color-scheme)`**（65/66 不支持）。

   **③ JS 比 CSS 更要命：语法不支持不是某个属性失效，而是整个 `<script>` 解析失败、脚本全丢。**

   major < 80 时**禁用** `?.`（可选链）、`??`（空值合并）、`||=` / `??=`（逻辑赋值）；
   major < 66 再禁用 `catch {}`（省略异常变量）、`1_000`（数字分隔符）、class fields。
   65 上实测**可用**的语法：`let/const` · 箭头函数 · 模板字符串 · 解构 · 默认/剩余参数 ·
   展开运算符 · `for-of` · generator · `async/await` · `**` · 正则新特性全套
   （命名捕获组 / lookbehind / `\p{L}` / dotAll）。

   **④ 常用 API 的最低 major**（下面这些都 65 实测 ✘，写前先比 `major`）

   `String.trimStart`(66) · `AbortController`(66) · `navigator.clipboard`(66) · `BigInt`(67) ·
   `Array.flat`(69) · `globalThis`(71) · `Intl.RelativeTimeFormat`(71) · `Object.fromEntries`(73) ·
   `String.matchAll`(73) · `Promise.allSettled`(76) · `CSS.registerProperty`(78) ·
   `String.replaceAll`(85) · `Promise.any`(85) · `Intl.Segmenter`(87) · `Array.at`(92) ·
   `crypto.randomUUID`(92) · `Array.findLast`(97) · `structuredClone`(98)。

   65 上确认**可用**的（可放心用）：`fetch` · `localStorage` · `Map/Set/WeakMap` ·
   `Symbol.iterator` · `IntersectionObserver` · `ResizeObserver` · `MutationObserver` ·
   `requestIdleCallback` · `performance.now()` · 2D canvas / WebGL · `WebAudio` ·
   WebSocket / EventSource / Worker / ServiceWorker · `element.animate()` · `requestAnimationFrame` ·
   `Element.closest` · `NodeList.forEach` · `scrollIntoView({block})` ·
   `toLocaleTimeString('zh-CN')` · `document.fonts` · ES module（`<script type=module>`）。

   65 上也**没有**的：`Notification`、`speechSynthesis` —— WebView 里本就不提供，与版本无关，
   别指望用它们做提醒或播报。

   > 兜底：真拿不准就写一段 `CSS.supports` / `new Function` 探针页投上去复测，**别照抄任何现成页面**。
7. **别依赖 JS 读视口**（电视实测坑）：旧内核（实测那台 Chromium 66 电视）里，脚本首次同步执行时
   `window.innerWidth` 可能是 **`0x0`**（首帧尚未完成布局）。需要量尺寸就延后一拍再测：
   `setTimeout(fn, 1000)` 或放 `window.onload`；纯 CSS 的 `%`/`vh` 不受影响。
8. **完工前自查溢出**：投送内容**不会**被 app 注入 `overflow:hidden`（只有待机主题才会）——
   页面超出视口是**真能滚**的，但电视端靠遥控器滚很别扭、手机端的滚动条又被禁掉，
   用户只会觉得"下面被切了一块"。所以整屏页面请自己保证一屏装得下。
   在页尾加一段自检，把 `documentElement.scrollHeight - clientHeight` 和关键元素的
   `getBoundingClientRect()` 用 `AiCast.emit` 回传给 agent，`$A events` 一拉就知道
   （实测靠这招揪出过右栏溢出 252px；修法是把固定 px 高度改成 `flex:1` 自适应分配）。
   确实需要禁止滚动时，**自己**写 `html,body{overflow:hidden}`，app 不代劳。

## 交互回传（按需，不是日常必用）

页内 SDK 已自动注入，内容里随时可 `AiCast.emit(name, data)` 回传事件、`AiCast.onMessage` 收增量数据。
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
| `auth <dev> --password-stdin` | 换 token 并持久化（30 天，多 token 并存）；口令走管道，或设 `AICAST_PASSWORD` |
| `info <dev>` | 完整画像 JSON（含 internet 实测状态） |
| `render <dev> --html-file f \| --html '<h>' \| --url u \| --clear [--wake]` | 投送/加载 URL/回待机；wake=亮屏投。**url 投送也进历史**（只记地址，重播＝重新加载） |
| `message <dev> --data '<json>' \| --data-file f` | 给页面发增量数据（透传给 `AiCast.onMessage`） |
| `events <dev> [--since N] [--timeout s] [--follow]` | 取回传事件（见上一节） |
| `state <dev>` | 当前渲染状态（hasContent/url/screenOn/theme/**pending**/**foreground**） |
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

- **200**：已上屏——界面在前台、内容已交给 WebView，正常情况用户此刻就看得到。
  唯一的例外是用户正停在 app 自己的「设置 / 历史」页（那层盖在内容上），关掉那页才看到内容。
- **202 `{ok:true, queued:true}`**：设备界面**不可见**（退到桌面 / 屏幕熄灭 / app 被清后台），
  内容已排队，**设备回到前台会自动呈现**。这是正常路径，不要当成失败重试。
  确认方式：`$A state <dev>` 的 `pending: true` 表示还在队列里；`foreground: false` 就是"界面不可见"。
- `--wake` 对**内联 HTML 与 URL 两种投送都生效**；排队期间记下的 wake 会在补投时一起用。
- 用户回「没看到」时：先看 `/state` 的 `foreground`。`foreground:false` → 让他打开一次 app；
  `foreground:true` 且 `hasContent:true` → 直接重投一次，别在设备上猜。

### 直接写 HTTP 调用（不用 CLI）时必读

用 CLI 就不用管这些；只有当你要自己 `curl`/`fetch` 时才需要：

- token 只认 **`Authorization: Bearer <token>`**，`?token=` 已废弃（一律 401）。
- 写操作必须带 **`Content-Type: application/json`**，否则 **415**；`POST /media` 是 raw body 例外，
  但必须自带 `Content-Length`。
- 带 `Origin` 头的跨站请求直接 **403**（反 CSRF）：只能从本机/agent 侧直连。
- **Host 必须是设备自己的地址**：只接受 `localhost`/回环或本机任一网卡 IP；用域名访问一律
  **403 `bad_host`**（防 DNS rebinding）。别给设备配个域名再用浏览器/脚本打开它。
- 未开放的能力返回 **403 `permission_denied`**，看 `/info` 的 `capabilities` 与 `open` 再动手。
- `/history`、`/state`、`/events` 等在 auth 模式下都需要 token，别假设它有公网般的匿名读。

## 模板脚手架（templates/）

验证过的起点，不是唯一写法，详见 `templates/README.md`：
`todo-interactive`（手机待办）、`auto-briefing`（轮播简报）、`rich-article`（图文范式）、
`remote-briefing`（TV 遥控轮播）、`lunch-roulette-{touch,tv}`（决策小游戏，⚠ 页内变量勿命名 `name`）、
`audio-player-tv`（音频播放页）。

模板只是起点不是规范：读懂填充点后按当次意图重写，别照搬版式。
同一意图投多台设备时，逐台取画像、各按§7.1决策表生成对应形态，两次 `render` 可并发下发互不干扰。

## 音频投送

设备出声完全可行（TV 真实出声已验证）。形式随你想象：播放器、有声简报、带 BGM 的氛围屏都行。实机坑：

- **旧内核（实测那台 Chromium 66 电视）WebAudio 现场合成不可靠**（声音塌成噪音）；播现成音轨（`<audio>`）稳定。手机一般都行。
- **版权**：只用公有领域/CC/免版税资源并在页面标注来源；流行歌曲不要投。
- mp3 是旧设备最稳格式；`<audio>` 别加 `crossorigin`（可能反而加载失败）。
- **自动播放可能被拦**：先直接试 `play()`，失败留"按 OK/点一下"手势入口。
- **选源以设备实测为准**：PC 上快的站电视可能反复缓冲；国内可达通常好于境外站，
  别把某个站当固定依赖，多个候选逐源试。
- **没有合适外链时上传到播放设备本机**：`aicast media <播放设备> <文件>`，页面 `<audio src>` 用返回的
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
5. **自定义主题 id 有规则**：1–40 位 `[A-Za-z0-9_-]`，且不能等于内置 id（`native` / `aurora` /
   `minimal-light`）。违规直接 **400 `bad_theme`**，不会静默改名——撞内置 id 会让 `htmlFor()`
   永远命中内置版，等于写出一份读不到也删不掉的孤儿条目。

## 安全与礼仪

- 永不猜测/爆破密码；向用户索取，token 长期复用（**同一设备最多保留 32 个活跃 token**）。
  满了会淘汰**最新签发**的那个，所以反复调 `auth` 的客户端只会不断替换自己的旧 token、
  不会挤掉长期在线的 agent；真被挤掉了重新 `auth` 一次即可。
- 投送前若 `state` 显示 hasContent（正显示别人的内容），先确认再覆盖。
- 提醒用户：真用之前把测试密码换掉（设置页可改，可"撤销所有 token"）。
- 不该做的：不要试图绕过 403 权限，也不要把一台设备当另一台的"网盘"跨设备搬媒体 ——
  能力矩阵与配额都是用户对该设备的明确授权。
