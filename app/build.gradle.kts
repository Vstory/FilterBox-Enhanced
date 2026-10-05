import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名来源：local.properties（已被 .gitignore 忽略）。CI 从 secrets 解出固定 keystore 后写入这四个键；
// 本机构建不带该文件时不挂签名。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signStoreFile = localProps.getProperty("storeFile")
val hasSigning = !signStoreFile.isNullOrBlank()

android {
    namespace = "io.github.vstory.hook.filterbox"
    compileSdk = 37
    // 本机只装了 arm64 build-tools 37.0.0（Commit451 版）；不指定则 AGP 会去装 x86_64 的默认版本
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.vstory.hook.filterbox"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signStoreFile!!)
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    buildTypes {
        debug {
            // 与正式版共用同一把签名 ⇒ 两个变体可互相覆盖安装（不必先卸载，卸载会丢 LSPosed 作用域状态）
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // libxposed 本地 jar（api102）：api=编译期 + interface/service=运行期
    compileOnly(files("libs/libxposed/api.jar"))
    implementation(files("libs/libxposed/interface.jar"))
    implementation(files("libs/libxposed/service.jar"))
}
