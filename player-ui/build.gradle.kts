plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.stellar.player.ui"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(project(":player-contract"))
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    // Explicitly authorized preview exception on 2026-09-28; see ADR-066.
    api("androidx.compose.material3:material3:1.5.0-alpha29")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
