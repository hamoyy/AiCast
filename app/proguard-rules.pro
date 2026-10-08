# Ktor / kotlinx.serialization 的 R8 规则
#
# 说明：原先是 -keep class io.ktor.** { *; }，等于把整个 Ktor 排除在裁剪之外。
# Ktor 里只有「JSON ContentNegotiation 插件」和「静态文件 Content」是通过服务加载 /
# 反射拿到的；引擎、路由、应用管线都是显式实例化，交给 R8 正常裁剪即可（release 已实测通过）。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep class kotlinx.serialization.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }
-keep class io.ktor.http.content.** { *; }
-keep class io.ktor.server.http.content.** { *; }

# Ktor 有几处桌面 JVM 的软依赖（调试器探测 / slf4j 静态绑定 / 目录监听），
# Android 上不存在对应类且运行时不会走到，缺类必须显式忽略，否则 release 的 R8 直接失败。
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn com.sun.nio.file.SensitivityWatchEventModifier
