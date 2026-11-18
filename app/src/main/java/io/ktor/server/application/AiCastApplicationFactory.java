package io.ktor.server.application;

import io.ktor.events.Events;
import io.ktor.server.engine.ApplicationEngine;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function0;

/**
 * 为 Android 4.4 提供一条不经过 Ktor `EmbeddedServer` 的启动路径。
 *
 * 为什么需要这个文件：
 * - `EmbeddedServer` 持有 `java.nio.file.WatchService/WatchKey` 引用（开发期热重载），
 *   而 `java.nio.file` 在 Android 上要 API 26 才有。Dalvik（4.4）在类校验阶段直接拒绝
 *   整个类 → `VerifyError`，服务端起不来。
 * - 自己组装引擎时，`Application` 的构造器在 Kotlin 侧是 `internal`，Kotlin 代码调不到；
 *   但 `internal` 只是编译器约束，字节码层面构造器是 **public**，所以同包的 Java 可以直接 new。
 *
 * 这里只做一件事：把构造器暴露成一个普通的静态工厂。其余生命周期仍由 Ktor 自己的引擎驱动。
 */
public final class AiCastApplicationFactory {

    private AiCastApplicationFactory() {
    }

    public static Application create(
            ApplicationEnvironment environment,
            boolean developmentMode,
            String rootPath,
            Events monitor,
            CoroutineContext parentCoroutineContext,
            Function0<ApplicationEngine> engineProvider
    ) {
        return new Application(
                environment, developmentMode, rootPath, monitor, parentCoroutineContext, engineProvider
        );
    }
}
