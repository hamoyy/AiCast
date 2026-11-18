plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.aicast.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aicast.app"
        // 19 = Android 4.4。4.4/4.4W 走 LegacyActivity（纯 View 界面），5.0+ 走 Compose。
        // Compose 依赖自身声明 minSdk 21，靠 AndroidManifest 里的 tools:overrideLibrary 放行；
        // 低版本系统上只要不加载那些类就是安全的 —— 见 LauncherActivity 的分派逻辑。
        minSdk = 19
        targetSdk = 35
        // minSdk < 21 必须开 multidex：4.4 的 Dalvik 只自动加载 classes.dex
        multiDexEnabled = true
        // versionCode 必须单调递增：应用内更新靠它判断新旧。
        // 打 tag 发版时 CI 会校验 tag 与 versionName 一致（见 .github/workflows/build.yml）。
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("localDebug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("localDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Ktor 3 的日期工具用了 java.time（API 26+），4.4/5.x/6.x 靠 desugaring 回填
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // 4.4 上必须显式安装第二段 dex（manifest 里 application 用 MultiDexApplication）
    implementation("androidx.multidex:multidex:2.0.1")

    val ktorVersion = "3.3.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    // appcompat 已移除：1.7.x 要求 minSdk 21，且会在 4.4 上经 androidx.startup 自动初始化。
    // 窗口主题改用平台主题（values/themes.xml），夜模式判断改读 Configuration。
}
