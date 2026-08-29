import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 云端密钥内置（2026-08-13）：secrets/cloud.properties（gitignored）→ BuildConfig。
// 缺文件/空值时为空串 = 无内置回退（NetConfig 回退链 SP → BuildConfig → 空）。
val cloudSecrets = Properties().apply {
    val f = rootProject.file("secrets/cloud.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cloudSecret(name: String): String =
    cloudSecrets.getProperty(name)?.trim().orEmpty()

android {
    namespace = "com.xiaoduo.deskpet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaoduo.deskpet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-m1"

        // S5 KWS：sherpa-onnx 官方 AAR（GitHub k2-fsa 源，用户已批准）。
        // arm64-v8a=真机出包；x86_64=模拟器调试（2026-08-17 用户批准，APK 增大约 15-20MB）
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // 云端密钥注入（严禁入 git；settings 页/SP 仍可覆盖）
        buildConfigField("String", "CLOUD_KEY", "\"${cloudSecret("CLOUD_KEY")}\"")
        buildConfigField("String", "ALIYUN_SPEECH_KEY", "\"${cloudSecret("ALIYUN_SPEECH_KEY")}\"")
        buildConfigField("String", "VOLC_SPEECH_KEY", "\"${cloudSecret("VOLC_SPEECH_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 安装包可直接安装分享：release 挂 debug 签名（混淆未开，sherpa-onnx JNI
            // /Compose 反射未过 proguard 验证；正式发布时换上传密钥库+混淆规则）
            signingConfig = signingConfigs.getByName("debug")
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
        // 云端密钥 BuildConfig 注入需要（2026-08-13；推翻 07-30「gradle 禁碰」旧 builder 地界纪律，
        // 偏差记 implementation-notes）
        buildConfig = true
    }

    composeOptions {
        // Kotlin 1.9.24 对应 Compose Compiler 1.5.14（KD11）
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Compose（S0 地基，BOM 统一版本）
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 网络（KTD4：NO_PROXY + host 白名单 TrustManager）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // S5 KWS 唤醒词（本地 AAR，模型在 assets/kws-model/）
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 契约测试解析 assets JSON（JVM 上 android 自带 org.json 是桩）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0") // AguiClient 登录/SSE 重试的 JVM 端对端桩
}
