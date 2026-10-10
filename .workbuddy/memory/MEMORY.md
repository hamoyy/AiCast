# aiface 项目长期笔记

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
- `docs/DESIGN.md` §5/§10 目前仍写着 `POST /eval`、`GET /screenshot` 两个**不存在**的端点，
  文献与实现不一致，改到相关区域时顺手订正。
- 本机验证方式：模型看不了图片，改由页面用 `Aiface.emit` 回传
  `getBoundingClientRect()` / `scrollHeight-clientHeight` / `CSS.supports` 结果，
  比截图更能揪问题（曾靠这个抓到右栏溢出 252px）。
