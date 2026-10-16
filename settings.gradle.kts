pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "cerulean"

include("cerulean-api")
include("cerulean-server")
include("cerulean-checkstyle")

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    // channel 决定版本串的**通道后缀**（本期为 SNAPSHOT ⇒ 预发布）：
    //   无构建号（本地/PR 构建）： 26.3-SNAPSHOT
    //   有构建号（发布 tag v26.3-build.N ⇒ BUILD_NUMBER=N）：26.3-build.N-SNAPSHOT
    // Maven 惯例把 SNAPSHOT 放在**最后**，所以这里用 `-${channel}` 而不是把 channel 塞在中间。
    val channel = providers.gradleProperty("channel").get().trim().uppercase()
    val buildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (buildNumber == null) {
        "$mcVersion-$channel"
    } else {
        "$mcVersion-build.$buildNumber-$channel"
    }
    version = versionString
    group = providers.gradleProperty("group").get().trim()
}
