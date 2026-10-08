# templates/ —— HTML 脚手架索引

agent 生成投送内容时优先复制对应模板再填充（见 SKILL.md「模板脚手架」节）。
模板遵守同一铁律：HTML 本体单文件（CSS/JS 内联，设备默认可联网、可引外链真图/CDN）、`viewport-fit=cover`、`env(safe-area-inset-*)` 避让挖孔/刘海、
通过内置 `Aiface` SDK 回传事件。样式为深色高对比基线，可按 `info` 里的 `appearance.darkMode` 反转。

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
| `remote-briefing.html` ★TV 实测效果佳 | remote · 电视 | 自动轮播 + 遥控器接管：◀▶ 翻页、OK 暂停/继续（左下角"已暂停"标签 + 帮助条 + 顶部进度条）；`SLIDES` 数组填充 | `slide.played{by:"auto"\|"nav"}`、`remote.pause` |
| `lunch-roulette-touch.html` | touch · 手机竖屏 | 老虎机式随机选择小游戏（示例"中午吃什么"）：点卡片/按钮滚动、再点停、锁定；替换 `FOODS` 数组即可复用为任意"随机抽一个"决策器；**注意变量勿叫 `name`**（与 `window.name` 冲突会静默失效） | `lunch.spin`、`lunch.pick` |
| `lunch-roulette-tv.html` ★TV 实测效果佳 | remote · 电视 | 同游戏的遥控版：进页面 1.2s 自动摇一次当吸引演示；OK 摇/停、↑ 锁定、↓ 再摇；底部键位提示条；`FOODS` 数组填充；CSS 按 Chromium 66 基线 | `lunch.spin{by:"tv"}`、`lunch.pick{by:"tv"}` |
| `audio-player-tv.html` ★TV 出声实测通过 | remote / auto · 电视或大屏 | 音频播放页：`<audio>` 播真音轨（示例为 Wikimedia Commons 公版德彪西《月光》录音，只用公版/CC 音轨并标注来源）；进页试播、被拦等 OK/点击；进度条+时长；OK 暂停/恢复。已知坑：旧 TV WebView 别用 WebAudio 现场合成（出噪音）、`<audio>` 勿加 `crossorigin`、mp3 最稳；外链不通时用 `aiface media` 把音频传到播放它的那台设备、或压小后 data URI 内嵌（详见 SKILL.md「音频投送」） | `track.play{src\|auto}`、`track.pause{at}`、`track.ended`、`track.error` |

★ 参考截图：`examples/remote-briefing-tv.png`（小米电视 1080p 实机画面，暂停态）。

新模板沉淀规则：演示验收后从 `examples/` 复制入本目录，并在上表登记一行。

## 实机案例（examples/）

| 案例 | 设备 | 源码 | 截图 |
|---|---|---|---|
| 遥控器简报（轮播 + D-pad 接管） | 小米电视 MiTV-MFTP0 | `examples/remote-briefing.html` | `examples/remote-briefing-tv.png` |
| 同一意图 · 双端并发：手机端"晚间习惯打卡"交互页 + 电视端"家庭晚间播报"轮播页，事件互回传 | Mi 10 + 小米电视 同时投送 | `examples/phone-routine.html` · `examples/tv-broadcast.html` | `examples/phone-routine-mi10.png` · `examples/tv-broadcast-mitv.png` |
| 「中午吃什么」摇一摇小游戏：触摸版（点卡摇/停/锁定）+ 遥控版（自动吸引演示 + OK/↑/↓） | Mi 10 · 小米电视 分别投送 | `lunch-roulette-touch.html` · `lunch-roulette-tv.html` | `examples/lunch-roulette-mi10.png` · `examples/lunch-roulette-tv.png` |
| 音频播放页（月光 · 公版录音，电视真实出声） | 小米电视 MiTV-MFTP0 | `audio-player-tv.html` + `audioserver.js` | `examples/audio-player-mitv.png` |

双端并发的要点（agent 生成同类内容时照此办理）：先分别 `GET /info` 拿两台设备画像，同一意图按 §7.1 决策表生成两种形态——touch 端做可点按交互并回传 `routine.done`/`routine.allDone`，remote 端做大字轮播并回传 `tv.broadcast`；两次 `render` 可并发下发，互不干扰。
