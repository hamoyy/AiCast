# AiCast Skill 配置与环境指南

本 Skill 只有一个可自动检查的外部依赖：**Node.js 运行时**。其余都是设备侧状态，
随用随变，不进环境检查，按各自的失败分支处理（见 SKILL.md 与 `skill-dependencies.json`）。

## 1. Node.js 运行时（必需）

依赖 id：`node-runtime`（对应 `skill-dependencies.json` 与 `scripts/check_environment.py` 的输出）

| 项 | 内容 |
|---|---|
| 用途 | 运行 `bin/aicast`（零依赖单文件 CLI，只用 `dgram`/`fs`/`os`/`path`/内置 `fetch`） |
| 最低版本 | 18（用到内置 `fetch` 与 `AbortController`） |
| 官方主页 | https://nodejs.org |
| 官方文档 | https://nodejs.org/en/docs |
| 官方下载 | https://nodejs.org/en/download |
| 核验日期 | 2026-10-12 |

安装后用下面的命令确认版本，**只看版本号，不输出任何凭据**：

```bash
node --version          # 需 ≥ v18
```

不需要 `npm install`，也不需要任何第三方包。

## 2. 环境检查怎么用

```bash
python  <本skill目录>/scripts/check_environment.py
python3 <本skill目录>/scripts/check_environment.py          # 部分系统用 python3
python  <本skill目录>/scripts/check_environment.py --capability render-html
```

- 不带 `--capability`：检查全部依赖。
- 带 `--capability`：只检查本次请求用到的功能所关联的依赖，参数值取自
  `skill-dependencies.json` 的 `capabilities`（`discover-devices` / `read-device-profile` /
  `render-html` / `read-events` / `manage-theme` / `upload-media`）。
- 退出码：`ready` 与 `partial` 为 0，其余为 1。

| 状态 | 下一步 |
|---|---|
| `ready` | 直接进入工作流，不再展示任何安装教程 |
| `partial` | 进入可用流程，说明受影响功能与降级方式 |
| `needs_setup` | 只展示缺失必需项（即 Node）的安装步骤，装好后重新检查 |
| `unavailable` | 展示结构化故障原因与恢复步骤 |

检查器只读：不装东西、不登录、不改配置、不输出凭据值。

## 3. 设备侧凭据（不进环境检查）

设备密码与 token 没有云端服务，**全部在用户自己的设备 app 内管理**，因此没有登录网址。
agent 一律向用户索取，绝不猜测或爆破密码。

| 项 | 做法 |
|---|---|
| 存储位置 | `~/.aicast/devices.json`（可用 `AICAST_HOME` 改目录） |
| 文件权限 | 文件 `0600`、目录 `0700`，仅当前用户可读 |
| 传入方式 | `--password-stdin`（口令走管道）或 `AICAST_PASSWORD` 环境变量；**不要用 `--password <pwd>`**，那会进 shell history 并出现在进程列表 |
| 有效期 | token 30 天过期，同一设备最多 32 个活跃 token，满了淘汰最新签发的那个 |
| 轮换 | 用户到设备「爱投屏」设置页修改访问密码 |
| 撤销 | 用户到设备「爱投屏」设置页执行「撤销所有 token」 |
| 试错上限 | 连错 5 次锁定 60 秒，计数落盘，重启 app 不清除 |

推荐写法：

```bash
printf '%s' "$DEVICE_PASSWORD" | node <本skill目录>/bin/aicast auth <dev> --password-stdin
```

token 过期或失效时表现是 `401`，重新 `auth` 一次即可，不需要清理本地文件。

## 4. 降级速查

完整降级矩阵见 `skill-dependencies.json`。日常最常遇到的几条：

| 现象 | 处置 |
|---|---|
| `discover` 空结果 | 先看输出里的 `mdnsDiag`（5353 被占用 / 组播被禁），再用 `--address <ip[:port]>` 手动登记 |
| `accepting: false` | 不重试、不换设备，原样转述 `acceptingHint` 请用户开投送开关 |
| `render` 返回 202 queued | 正常路径，查 `state` 的 `pending`，不要当失败重投 |
| `render` 返回 401 | 先 `auth` 换 token（public 模式不会出现） |
| `render` 返回 429 | 按 `retryAfterMs` 等待，不缩短间隔重试 |
| HTML 超 4MB / 媒体超 32MB | 客户端已预检并直接报错，压缩后再投 |
| `media` 返回 507 | 配额满，`media --list` 后 `--remove` 清旧项，或压小重传 |
| `events` 的 `reset` 为 true | CLI 自动从头续拉，无需干预 |

**没有 Node 又必须投送时**：退回 SKILL.md「直接写 HTTP 调用」一节，用 `curl` 直连设备端点。
代价是没有 mDNS 发现、没有 token 持久化、没有客户端体积预检，且需自带
`Authorization: Bearer` 与 `Content-Type: application/json` 两个头。
