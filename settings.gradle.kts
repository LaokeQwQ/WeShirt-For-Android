pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://developer.huawei.com/repo/") }
    if (file("app/agconnect-services.json").isFile) {
        // AGConnect 1.9.6's plugins-DSL fallback requires this exact catalog/alias.
        // Keep the version aligned with the root Android application plugin.
        versionCatalogs {
            create("libs") { plugin("android-application", "com.android.application").version("8.13.2") }
        }
    }
}
rootProject.name = "WeShirt"
include(":app")
