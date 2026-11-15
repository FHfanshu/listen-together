plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    // jvm 目标只用来跑共享逻辑的单元测试，比 Android 单测快。
    jvm()
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        // Android 和桌面 JVM 共用：App 自己解析 YouTube（NewPipeExtractor 是纯 Java 库，桌面上也能跑测试）。
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation(libs.newpipe.extractor) }
        }
        androidMain.get().dependsOn(jvmCommonMain)
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "app.listentogether.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    // NewPipeExtractor 在 minSdk < 33 时要 nio 版的核心库脱糖。
    compileOptions { isCoreLibraryDesugaringEnabled = true }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.nio)
}
