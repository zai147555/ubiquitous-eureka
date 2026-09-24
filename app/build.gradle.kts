plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nekonyan.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nekonyan.assistant"
        minSdk = 31            // 需求：Android 12+（API 31+）
        targetSdk = 35
        versionCode = 19
        versionName = "0.16.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
        vectorDrawables { useSupportLibrary = true }

        // 需求：多 ABI（有 native 代码时才生效）
        // 只保留 64 位：armeabi-v7a 已被 Android 15 淘汰，而多一份 ncnn 就多约 4MB 下载
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true      // Robolectric 需要
            isReturnDefaultValues = true
        }
    }

    ndkVersion = "26.1.10909125"

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*"
        )
    }

    // native（M4）：JNI 源码在 src/main/cpp/，NCNN 预编译包由 tools/fetch_ncnn.sh 在构建前放入
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

ksp {
    // 用 Kotlin 生成（避免生成 Java 实现类时踩 Java 关键字/默认方法这类坑）
    arg("room.generateKotlin", "true")
    // 未开启 exportSchema，因此不设 room.schemaLocation
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ============================ 内置 YOLO 模型 ============================
//
// 构建前把内置的 YOLOv11n（NCNN 的 .param + .bin + 类别表）取到
// app/src/main/assets/models/v1/，脚本幂等：已存在且校验通过就直接跳过。
//
// 为什么挂在 Gradle 而不是 CI 的 yml 里：
//   `.github/workflows/` 的改动需要 token 具备 workflow 权限，缺权限时 GitHub 会
//   直接拒绝**整棵树**（表现为 404，很难查）。而"APK 里要有模型"这件事与在哪儿
//   触发构建无关 —— 挂到 preBuild 上，本地构建与 CI 都会得到带模型的包。
//
// 离线构建：加 -PskipYoloModel=true 跳过（此时 APK 不含内置模型，
// 应用界面会如实显示"还没有可用模型"，不会假装有）。
val skipYoloModel: Boolean = providers.gradleProperty("skipYoloModel").isPresent

val fetchYoloModel = tasks.register<Exec>("fetchYoloModel") {
    group = "nekonyan"
    description = "取内置 YOLOv11n 到 app/src/main/assets/models/v1（幂等）"
    workingDir = rootProject.projectDir
    commandLine("bash", "tools/fetch_yolo_model.sh")
    onlyIf { !skipYoloModel }
}

// preBuild 由 AGP 注册；用 matching 而不是 named，避免注册顺序导致的配置期报错
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(fetchYoloModel)
}

// ============================ NCNN 原生推理 ============================
// 构建前取 NCNN 预编译包（幂等）；CMake 的 configure/build 任务都依赖它，
// 保证 configureCMakeDebug 跑的时候 ncnn/ 已经就位。
// 离线构建：加 -PskipNcnn=true 跳过（此时没有 libyolo_ncnn.so，应用会如实报"推理不可用"）。
val skipNcnn: Boolean = providers.gradleProperty("skipNcnn").isPresent

val fetchNcnn = tasks.register<Exec>("fetchNcnn") {
    group = "nekonyan"
    description = "取 NCNN 官方 Android 预编译包到 app/src/main/cpp/ncnn（幂等）"
    workingDir = rootProject.projectDir
    commandLine("bash", "tools/fetch_ncnn.sh")
    onlyIf { !skipNcnn }
}

tasks.matching {
    it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") ||
        it.name.startsWith("externalNativeBuild") || it.name == "preBuild"
}.configureEach { dependsOn(fetchNcnn) }
