// shared 模块 - 共享 Kotlin JVM 库（数据模型、AI 接口、DB 访问）
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    // 数据库
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.4")
    // 加密
    implementation("org.mindrot:jbcrypt:0.4")
    // HTTP 客户端（用 JDK 自带 java.net.http）
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
