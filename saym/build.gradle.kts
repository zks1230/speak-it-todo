// 根项目构建脚本
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // KSP：Room 注解处理必需（版本必须与 Kotlin 2.0.21 匹配）
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
