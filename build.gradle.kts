buildscript {
    // Public/no-credential builds do not resolve or apply the vendor resource plugin.
    if (rootProject.file("app/agconnect-services.json").isFile) {
        repositories { google(); mavenCentral(); maven("https://developer.huawei.com/repo/") }
        dependencies { classpath("com.huawei.agconnect:agcp:1.9.6.300") }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
