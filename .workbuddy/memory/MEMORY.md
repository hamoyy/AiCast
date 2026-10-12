# AiCast（爱投屏）项目长期笔记

## 命名（2026-10-12 起，全仓库统一）

- 英文名 **AiCast**，中文名 **爱投屏**；包名/CLI 一律小写 `com.aicast.app` / `aicast`。
- 中文名只出现在**用户可见文案**：启动器名、通知标题/渠道名、`acceptingHint` 引导语。
  代码标识符、路径、命令一律 `AiCast` / `aicast`。
- 完整改名映射与验证记录见 `.workbuddy/memory/2026-10-12.md`。
- 关键标识符：`AiCastService`、`aiCastColors()`、`Theme.AiCast`、`assets/aicast-sdk.js`、
  `window.AiCast` / `AndroidAiCast` / `aicast-message`、`https://aicast.local`、
  mDNS `_aicast._tcp`、SharedPreferences `aicast*`、CLI `skill/bin/aicast` + `~/.aicast`。
- **工作区文件夹仍是 `aiface`**：会话中改不动（Bash 工具 cwd 一直占着句柄，WinError 32）。
  关掉会话后手动 `mv .../code/aiface .../code/aicast`。`scripts/dual-orchestrate.py`
  的路径已改为由 `__file__` 推导，改名与否都不影响。

## 构建工具链（本机）

- Gradle 8.14.3 / AGP 8.5.2 / Kotlin 2.2.20，仓库 `gradle-wrapper.properties` 指向腾讯镜像。
- **Android Studio 自带 JBR 已升到 Java 25，不能用来构建本工程**：Gradle 内嵌的 Kotlin DSL
  编译器解析不了 `25.0.3`，报 `IllegalArgumentException`。
- 固定用本机 Temurin 17：`C:\Users\wapse\.workbuddy\binaries\java\jdk-17`（仓库外，不入库）。
  已在 `~/.gradle/gradle.properties` 写入 `org.gradle.java.home`。
- **wrapper 脚本自己还要 java**：构建时必须显式带环境变量，光靠 gradle.properties 不够：
  `JAVA_HOME=C:\Users\wapse\.workbuddy\binaries\java\jdk-17 ./gradlew assembleDebug`

## 设备清单（局域网）

| 设备 | 地址 | 形态 | 模式 | WebView |
|---|---|---|---|---|
| Xiaomi Mi 10 | 192.168.31.179 | 手机 1080×2340 touch 竖屏 | auth（需 token） | ≥110 |
| xiaomi MiTV-MFTP0 | 192.168.31.197 | 电视 1920×1080 remote 横屏 960×540dp 无触摸 | **public（免鉴权）** | Chromium 66.0.3359.158 |

adb：`C:\Users\wapse\AppData\Local\Android\Sdk\platform-tools`（不在 PATH）；
电视走 `adb connect 192.168.31.197:5555`。小米电视的 adb 会在 screencap 输出前塞 45 字节
shell 日志，截图要用 `scripts/cap.py`（从 `\x89PNG` 起切）。

## 铁律

- `/info.capabilities` 只能上报**真的有对应 HTTP 端点**的能力（P1-16）。
  `scripts/smoke.sh` 已有「能力清单真实性」组自动守这条。
- 文献同样受上一条约束。2026-10-12 已清掉 `docs/DESIGN.md` 里所有虚构端点
  （`POST /eval`、`POST /screen`、`POST /system`、`GET /screenshot`）与虚构权限开关，
  §5/§6/§11 现在对齐真实实现。**再写文档时不要凭草稿补功能。**
- 本机验证方式：模型看不了图片，改由页面用 `AiCast.emit` 回传
  `getBoundingClientRect()` / `scrollHeight-clientHeight` / `CSS.supports` 结果，
  比截图更能揪问题（曾靠这个抓到右栏溢出 252px）。
