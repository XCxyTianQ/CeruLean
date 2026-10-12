/*
 * CeruLean Cores —— 根构建
 *
 * 形态：paperweight patcher 的 **patch-only fork**。
 *   L0 Mojang  →  L1 PaperMC/Paper（按 gradle.properties 的 paperRef 拉取，含其 981 个补丁）
 *              →  L2 CeruLean 补丁层（cerulean-server/ 、cerulean-api/ 下的 patches 目录）
 *
 * 该结构对齐 PaperMC/Folia 现行做法（GPLv3），仅做品牌与模块改名。
 */
import io.papermc.paperweight.tasks.RebuildGitPatches

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.21"
}

paperweight {
    filterPatches = false
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("cerulean-server/build.gradle.kts")
            patchFile = file("cerulean-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("cerulean-api/build.gradle.kts")
            patchFile = file("cerulean-api/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-checkstyle/build.gradle.kts"
            outputFile = file("cerulean-checkstyle/build.gradle.kts")
            patchFile = file("cerulean-checkstyle/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("cerulean-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("paperCheckstyle") {
            upstreamPath = "paper-checkstyle"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("cerulean-checkstyle/paper-patches")
            outputDir = file("paper-checkstyle")
        }
        patchDir("paperCheckstyleConfig") {
            upstreamPath = ".checkstyle"
            patchesDir = file("cerulean-checkstyle/config-patches")
            outputDir = file(".checkstyle")
        }
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // Minecraft/Paper 26.x 的 class 主版本为 69 = Java 25，无降级空间
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
}

tasks.register("printMinecraftVersion") {
    doLast { println(providers.gradleProperty("mcVersion").get().trim()) }
}

tasks.register("printBuildVersion") {
    doLast { println(project.version) }
}
