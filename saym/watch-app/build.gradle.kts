// watch-app - DW99 手表端 App
//
// 设计约束（来自 §7 DW99 约束）：
//  - 精简 ROM、内存小 → 只用 Kotlin 标准库 + Android 原生 API，不引 AndroidX / Room / serialization
//  - 屏幕小、触摸精度差 → UI 全部程序化构建，字大、点击区大，并提供「界面缩放」小/中/大
//  - 没有 adb → 诊断靠「屏上日志页」+ 写入本地文件，用户拍照回传
//  - 要省电 → 不轮询；同步由：① 打开 App ② 手动刷新 ③ 每日定时 ④ 手机蓝牙推送 触发
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saym.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saym.watch"
        // 23 = Android 6.0，尽量兼容华强北表的旧系统；高版本 API 在代码里做判断
        minSdk = 23
        targetSdk = 30
        versionCode = 10
        versionName = "1.0"
        // DW99 常见 ABI：armeabi-v7a + arm64-v8a（32 位老表也要能装）
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // 发布签名（密钥在 keystore/ 目录，密码在 gradle.properties）
    // 这样 `assembleRelease` 出来的包可直接安装，不必再手动签名。
    signingConfigs {
        create("release") {
            val ksPath = (project.findProperty("saym.watch.storeFile") as String?) ?: "keystore/saym-watch-release.jks"
            val ks = rootProject.file(ksPath)
            if (ks.exists()) {
                storeFile = ks
                storePassword = project.findProperty("saym.watch.storePassword") as String?
                keyAlias = project.findProperty("saym.watch.keyAlias") as String? ?: "saymwatch"
                keyPassword = project.findProperty("saym.watch.keyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            // 手表内存小、体积要小：开启代码压缩 + 资源压缩
            // （isShrinkResources 必须配合 isMinifyEnabled，否则 Gradle 直接报配置错误）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = rootProject.file(
                (project.findProperty("saym.watch.storeFile") as String?) ?: "keystore/saym-watch-release.jks"
            )
            if (ks.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 调试期不开混淆，方便看崩溃行号
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = false; viewBinding = false }

    lint {
        // targetSdk=30 是**故意的**：DW99 是精简 Android，太新的 targetSdk 会被系统拒绝或行为异常。
        // 本项目纯自用、不上 Google Play，所以这条"上架商店"的检查不适用。
        disable += "ExpiredTargetSdkVersion"
        // 发布构建不要因为非致命告警中断
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json"
            )
        }
    }
}

dependencies {
    // 极简：只用 Kotlin 标准库。HTTP 用 HttpURLConnection、同步用原生 Socket、
    // 通知用 NotificationManager —— 都不需要额外依赖，控制 APK 体积。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
