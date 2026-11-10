import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.laoke.weshirt.push-tls")
}
// The ignored deployment file supplies AGConnect resources; it never enables SDK startup.
if (file("agconnect-services.json").isFile) apply(plugin = "com.huawei.agconnect")
val privateConfig = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun config(name: String, default: String = "") = (System.getenv(name) ?: privateConfig.getProperty(name, default))
fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""
android {
    namespace = "com.laoke.weshirt"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.laoke.weshirt"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "90000"
        buildConfigField("String", "API_BASE_URL", literal(config("WESHIRT_API_BASE_URL")))
        listOf("PUSH_FCM_APP_ID", "PUSH_FCM_API_KEY", "PUSH_FCM_PROJECT_ID", "PUSH_FCM_SENDER_ID", "PUSH_UMENG_APP_KEY", "PUSH_UMENG_MESSAGE_SECRET", "PUSH_XIAOMI_APP_ID", "PUSH_XIAOMI_APP_KEY").forEach { name -> buildConfigField("String", name, literal(config("WESHIRT_$name"))) }
        buildConfigField("boolean", "PUSH_HUAWEI_CONFIGURED", config("WESHIRT_PUSH_HUAWEI_CONFIGURED", "false").toBoolean().toString())
    }
    signingConfigs {
        if (config("WESHIRT_KEYSTORE").isNotBlank()) create("privateRelease") {
            storeFile = file(config("WESHIRT_KEYSTORE"))
            storePassword = config("WESHIRT_STORE_PASSWORD")
            keyAlias = config("WESHIRT_KEY_ALIAS", "weshirt")
            keyPassword = config("WESHIRT_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            signingConfig = signingConfigs.findByName("privateRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.all { it.maxHeapSize = "2g" } }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    // Scanner SDKs bring an older Fragment; Activity Result requires a compatible version.
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.alibaba.android:ddopenauth:1.5.0.11")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("com.umeng.umsdk:push:6.7.6")
    implementation("com.umeng.umsdk:common:10.0.1")
    implementation("com.umeng.umsdk:asms:1.8.7.2")
    implementation("com.umeng.umsdk:xiaomi-umengaccs:2.3.0")
    implementation("com.umeng.umsdk:xiaomi-push:7.12.4")
    implementation("com.umeng.umsdk:huawei-umengaccs:2.3.0")
    implementation("com.huawei.hms:push:6.13.0.301")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
