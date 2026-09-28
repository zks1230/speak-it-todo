// 说一嘴 - 顶层 settings
//
// 仓库说明：
//  - 默认走 google() + mavenCentral()（原行为不变）。
//  - 如果本机直连 Google/Maven Central 很慢（或需要走镜像），设置环境变量
//        $env:SAYM_USE_MIRROR = "1"     (PowerShell)
//        export SAYM_USE_MIRROR=1       (bash)
//    即可切到阿里云镜像，无需改代码。
pluginManagement {
    repositories {
        if (System.getenv("SAYM_USE_MIRROR") == "1" || System.getProperty("saym.useMirror") == "1") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("SAYM_USE_MIRROR") == "1" || System.getProperty("saym.useMirror") == "1") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "saym"
include(":shared", ":backend", ":phone-app", ":watch-app")
