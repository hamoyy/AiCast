# templates/ —— HTML 脚手架索引

生成投送内容时可先复制对应模板再填充（见 SKILL.md「模板脚手架」节）。
**模板是起点不是规范**：读懂填充点后按当次意图重写，别照搬版式——
同样的意图投到手机和电视上，本就该长成两个样子。

模板遵守同一铁律：HTML 本体单文件（CSS/JS 内联，设备默认可联网、可引外链真图/CDN）、`viewport-fit=cover`、
挖孔/刘海避让用画像的 `screen.cutoutInsets`（**别用 `env()`**，实测它在本 app 的 WebView 里恒为 0）、
通过内置 `AiCast` SDK 回传事件。
样式为深色高对比基线，可按 `info` 里的 `appearance.darkMode` 反转。
**写法按画像 `device.webview.major` 分档**（SKILL.md 铁律第 6 条）；本目录模板统一只用保守档安全集，
故 WebView 65 起的全机队通用。

「回传事件」列可直接用于 `$A events` 过滤。
**命名规则：事件名务必带点号**（`todo.toggle` 而非 `toggle`），且不要以
`system. / auth. / app. / device. / capability. / render. / media. / theme.` 开头 ——
否则设备会把它改写成 `page.*`（冒名系统事件被降级）或补 `ui.` 前缀（无点号），
你在 `events` 里按原名捞不到。详见 SKILL.md「交互回传」。

| 文件 | 适用画像 (interactionModel) | 结构与填充点 | 回传事件 |
|---|---|---|---|
| `todo-interactive.html` | touch · 手机竖屏 | 单列待办卡片流；勾选+进度条；`items` 初始数组；localStorage 持久化 | `todo.toggle`、`todo.allDone`；`/message` 支持 `{action:"add"\|"clear"}` |
| `auto-briefing.html` | auto / remote · 电视或大屏 | 5s 自动轮播简报；`SLIDES` 数组（每页 标题+要点+页点）；无必要交互 | `slide.played` |
| `rich-article.html` | touch 或 auto | 图文文章范式：hero + 内联 SVG 配图 + 引文 + 统计块 + 标签；直接替换 `<div class="page">` 内容 | — |
| `remote-briefing.html`（电视实测效果佳） | remote · 电视 | 自动轮播 + 遥控器接管：◀▶ 翻页、OK 暂停/继续（左下角"已暂停"标签 + 帮助条 + 顶部进度条）；`SLIDES` 数组填充 | `slide.played{by:"auto"\|"nav"}`、`remote.pause` |
| `lunch-roulette-touch.html` | touch · 手机竖屏 | 老虎机式随机选择小游戏（示例"中午吃什么"）：点卡片/按钮滚动、再点停、锁定；替换 `FOODS` 数组即可复用为任意"随机抽一个"决策器；**注意变量勿叫 `name`**（与 `window.name` 冲突会静默失效） | `lunch.spin`、`lunch.pick` |
| `lunch-roulette-tv.html`（电视实测效果佳） | remote · 电视 | 同游戏的遥控版：进页面 1.2s 自动摇一次当吸引演示；OK 摇/停、↑ 锁定、↓ 再摇；底部键位提示条；`FOODS` 数组填充；CSS 保守档写法 | `lunch.spin{by:"tv"}`、`lunch.pick{by:"tv"}` |
| `audio-player-tv.html`（电视真实出声） | remote / auto · 电视或大屏 | 音频播放页：`<audio>` 播现成音轨（示例用 OpenGameArt 的 `Red Curtain`，HorrorPen · CC-BY 3.0，页面内已标注来源；换成别的音轨时同样要在页面标注授权与来源）；进页试播、被拦等 OK/点击；进度条+时长；OK 暂停/恢复。已知坑：旧 TV WebView 别用 WebAudio 现场合成（出噪音）、`<audio>` 勿加 `crossorigin`、mp3 最稳；外链不通时用 `aicast media` 把音频传到播放它的那台设备、或压小后 data URI 内嵌（详见 SKILL.md「音频投送」） | `track.play{src\|auto}`、`track.pause{at}`、`track.ended`、`track.error` |

## audioserver.js（局域网音频兜底服务器）

外链音源在目标设备上反复缓冲时的临时办法：在本机起一个静态音频服务，
页面 `<audio src>` 指向 `http://<本机IP>:8770/<file>.mp3`（支持 Range/seek）。

**用完即关，不要当常驻服务。** 它 `listen` 在 `0.0.0.0` 且响应带 `Access-Control-Allow-Origin: *`，
等于**整个局域网都能读它所在目录下的文件**——只在受信任的家庭/办公网络里短时间用。
路径穿越已由 `path.basename` 挡住（不会跳出所在目录）。
需要更严的场合，把 `listen(PORT, "0.0.0.0")` 改成本机内网 IP；更稳的做法是改用
`aicast media` 把音频传到播放它的那台设备，连这层依赖都不需要。

## 新模板沉淀

演示验收后把源码收进本目录，并在上表登记一行（适用画像 / 填充点 / 回传事件）。
