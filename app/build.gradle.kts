plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.muse.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.muse.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 140
        versionName = "2.6.32"
        vectorDrawables { useSupportLibrary = true }
    }

    // 完整版带 libVLC（可软解 10bit H.265 等），轻量版不含原生库、体积小
    flavorDimensions += "engine"
    productFlavors {
        create("full") {
            dimension = "engine"
        }
        create("lite") {
            dimension = "engine"
            // 与完整版并存安装，互不覆盖
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
        }
    }

    // 按 CPU 架构拆包：libVLC 原生库很大，各架构单独发布，不再出全量包
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            // 个人使用：复用 debug keystore 给 release 包签名，保证可直接安装。
            // 仓库内置一份（signing/debug.keystore），让 GitHub Actions 构建的包与本机签名一致；
            // 没有内置文件时回退到本机 debug keystore。
            val repoKeystore = rootProject.file("signing/debug.keystore")
            storeFile = if (repoKeystore.exists()) {
                repoKeystore
            } else {
                file("${System.getProperty("user.home")}/.android/debug.keystore")
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "/META-INF/{AL2.0,LGPL2.1}"
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 第二播放引擎：Media3 解不出的格式（如 10bit H.265）交给 libVLC 软解。
    // 只打进完整版，轻量版用 src/lite 下的空实现替代。
    "fullImplementation"("org.videolan.android:libvlc-all:3.6.0")

    // 局域网协议：SMB / FTP / WebDAV
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
