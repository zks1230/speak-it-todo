// phone-app - 手机端 Android App
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // ⚠️ 必须有 KSP：Room 是注解处理器，没有它就不会生成 SaymDb_Impl，
    //    编译能过但一运行就抛 "Cannot find implementation for ... SaymDb"
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.saym.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saym.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // 发布签名（密钥在 keystore/ 目录，密码在 gradle.properties）
    // 这样 `assembleRelease` 出来的包可直接安装，不用再手动签名。
    signingConfigs {
        create("release") {
            val ksPath = (project.findProperty("saym.release.storeFile") as String?) ?: "keystore/saym-release.jks"
            val ks = rootProject.file(ksPath)
            if (ks.exists()) {
                storeFile = ks
                storePassword = project.findProperty("saym.release.storePassword") as String?
                keyAlias = project.findProperty("saym.release.keyAlias") as String? ?: "saym"
                keyPassword = project.findProperty("saym.release.keyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = rootProject.file(
                (project.findProperty("saym.release.storeFile") as String?) ?: "keystore/saym-release.jks"
            )
            if (ks.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false; buildConfig = true }
    // Robolectric 需要：在 JVM 上跑真实 Android 组件（本机是云虚拟机，无法跑模拟器）
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    // 手机端自包含（新架构）：本地库(Room) + 直连大模型(HttpURLConnection) + 局域网同步(原生 Socket)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Room：运行时 + 注解处理器（KSP 版，缺一不可）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ---- 测试：在 JVM 上真实启动 Activity/数据库，用于抓"一启动就崩"这类问题 ----
    // （本机是阿里云虚拟机，无嵌套虚拟化，Android 模拟器无法运行）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
