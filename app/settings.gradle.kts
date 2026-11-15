rootProject.name = "listen-together-app"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor（App 自己解析 YouTube）只发布在 JitPack。
        maven("https://jitpack.io") { content { includeGroup("com.github.teamnewpipe"); includeGroup("com.github.TeamNewPipe") } }
    }
}

include(":shared", ":composeApp")
