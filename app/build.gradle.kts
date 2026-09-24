import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 读取本地签名配置（keystore.properties），不存在时 release 将产出未签名包
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.mslx.console"
    compileSdk = 35
    // 本机开服需要 NDK：进程内 JVM 桥接（dlopen libjvm.so + JNI_CreateJavaVM）
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.mslx.console"
        minSdk = 24
        targetSdk = 35
        versionCode = 34
        // CI Actions 构建会以 -PversionName=x.x.x.x 覆盖（见 android.yml Compute Actions version）
        versionName = (project.findProperty("versionName") as String?) ?: "1.7.1"

        ndk {
            // arm64 真机 + x86_64 模拟器（本机开服调试用）
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Actions 渠道：CI 恢复正式签名密钥后，debug APK 也用 release 签名，
            // 使 Actions 调试构建可直接覆盖安装正式版（同签名升级，无需先卸载）。
            // 本地无 keystore.properties 时仍走默认 debug 签名。
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // ApiClient 的 User-Agent 读 BuildConfig.VERSION_NAME（与 versionName 自动同步）
        buildConfig = true
    }

    lint {
        // 已知可接受告警（trust-all SSL 仅用于连自建 Daemon、启动图标形状/主题色、
        // 依赖有新版提示等）已记入 baseline，使 ./gradlew check 可通过并只暴露新增问题。
        // 重新生成：./gradlew updateLintBaseline
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // 内嵌 JRE 是 .tar.xz，再压缩没有收益，跳过以加快打包
        noCompress += "xz"
    }

    // 内嵌 JRE 运行时放在独立源集：默认打进 APK（完整版）；
    // 传 -PwithoutJre=true 即产出不含运行时的精简版（CI 同一 Release 发布两个包）。
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            if (project.findProperty("withoutJre") != "true") {
                assets.srcDirs("src/jreRuntime/assets")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.signalr)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // 本机开服：解压内嵌 Android JRE（上游 .tar.xz）
    implementation(libs.xz)
    implementation(libs.commons.compress)

    // 本机开服增强：Shizuku/ADB 权限下 exec 真正的 java 子进程
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.androidx.ui.tooling)
}
