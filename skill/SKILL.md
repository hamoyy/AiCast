---
name: aiface
description: 局域网内 AI 向安卓设备投送 HTML 内容并读取设备画像/交互回传。当用户要"给某台设备屏幕投内容/把手机或电视当显示面板/投送待办、简报、仪表盘、音频页面"时使用。
---

# aiface — AI 的局域网显示通道

aiface app 跑在目标安卓设备上，内置 HTTP server（端口 43210）。本 Skill 提供零依赖 CLI
（`bin/aiface`，Node 18+），agent 用 Bash 调用：发现 → 鉴权 → 投送 HTML → （需要时）读回传。

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
6. **CSS 兼容基线 = Chromium 66**（电视系统 WebView 常无法升级，实测小米电视停在 66；手机一般 ≥110）：
   `inset`、`clamp()/min()/max()`、flex `gap`、`env(safe-area-inset)` 在 66 上会被**静默丢弃**，
   页面塌成内容尺寸挤在左上角。写法二选一：纯兼容（`top/right/bottom/left`、`margin` 代 gap），
   或"回退值在前、现代值在后"双声明。`templates/` 全部按此基线写，可参照。

## 交互回传（按需，不是日常必用）

页内 SDK 已自动注入，内容里随时可 `Aiface.emit(name, data)` 回传事件、`Aiface.onMessage` 收增量数据。
读取端**不需要常驻监听服务**：设备侧有事件环形缓冲（最近 200 条/10 分钟），
`$A events <dev> --since 0` 任何时候跑一次就能把攒下的事件一次取回；`--follow` 持续监听仅调试时用。
建议用在：需要知道用户点了什么（投票/勾选/游戏）、或排查页面为什么没反应。日常投送不取事件也完全成立。

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
| `state <dev>` | 当前渲染状态（hasContent/url/screenOn/theme） |
| `param <dev> [key] [value]` | 读/设 brightness/theme/fontScale/volume/rotationLock/idleDimSeconds |
| `themes <dev>` / `theme <dev> <id>` | 列出主题 / 激活待机主题 |
| `theme <dev> --register (--html-file f \| --html '<h>') [--name N] [--id ID] [--activate]` | 注册自定义 HTML 主题 |
| `theme <dev> --remove <id>` | 删除自定义主题（内置不可删） |
| `media <dev> <file> [--name n]` | 上传媒体到即将播放它的那台设备，返回 `absoluteUrl`（见「音频投送」） |
| `media <dev> --list` / `--remove <id>` | 列出 / 删除设备上的媒体项 |
| `forget <dev>` | 删除已存设备记录 |

所有输出为 stdout JSON；错误 `{"ok":false,...}` 且退出码 1。401 提示先 auth，429 为密码锁 60s。

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
  `absoluteUrl`，本机流式（Range/seek 均支持，单文件 ≤64MB）。
  **铁律：每台设备只和 agent 联络，不要把一台设备当另一台的"网盘"跨设备拉流。**
- **低配电视存储紧张是常态**：app 有 32MB 环形配额 + 72h 自动清理，空间不足回 **507**——
  此时把音频压小再传（降码率/裁时长/80kbps 单声道），或 `--remove` 清旧项，不要改投别的设备。
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

- 永不猜测/爆破密码；向用户索取，token 长期复用。
- 投送前若 `state` 显示 hasContent（正显示别人的内容），先确认再覆盖。
- 提醒用户：真用之前把测试密码换掉（设置页可改，可"撤销所有 token"）。
