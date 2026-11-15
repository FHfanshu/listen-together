plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.material.icons.extended)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.ktor.client.okhttp.app)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.datasource.okhttp)
        }
    }
}

android {
    namespace = "app.listentogether"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.listentogether"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.6.0"
    }
    compileOptions { isCoreLibraryDesugaringEnabled = true }
    packaging {
        // NewPipeExtractor 依赖里的重复元数据文件。
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.nio)
}
