import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localConfig = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
fun config(name: String) = providers.environmentVariable(name).orNull ?: localConfig.getProperty(name, "")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""


android {
    namespace = "cn.coldlake.stellar.opensource"
    ndkVersion = "29.0.14206865"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "cn.coldlake.stellar.opensource"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "TMDB_API_KEY", quoted(config("TMDB_API_KEY")))
        buildConfigField("String", "TRAKT_API_KEY", quoted(config("TRAKT_API_KEY")))
        buildConfigField("String", "TMDB_READ_ACCESS_TOKEN", quoted(config("TMDB_READ_ACCESS_TOKEN")))
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }
    // Debug uses Android's standard local debug keystore. Release is unsigned by default.
    buildTypes {
        getByName("debug") { isPseudoLocalesEnabled = true }
        getByName("release") { isDebuggable = false }
    }
    // Shared project modules link the same canonical native prefix.
    packaging { jniLibs { pickFirsts += setOf("**/libc++_shared.so", "**/libmpv.so", "**/libav*.so", "**/libsw*.so", "**/libsmb2.so", "**/libnfs.so") } }
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Latest stable verified against AndroidX release notes on 2026-10-28.
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    // Latest stable checked against upstream releases and Maven Central on 2026-10-26.
    implementation("com.materialkolor:material-color-utilities:6.0.0")
    // Latest stable release verified against AndroidX release notes on 2026-10-24.
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    // Fold posture for hinge-aware layouts; latest stable checked against Google Maven on 2026-10-27.
    implementation("androidx.window:window:1.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("androidx.browser:browser:1.10.0")
    implementation(project(":player-native"))
    implementation(project(":player-mpv"))
    implementation(project(":player-ui"))
    implementation("com.github.bitfireAT:dav4jvm:4.1.0") {
        exclude(group = "org.ogce", module = "xpp3")
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.7.1-android")
    implementation(platform("io.ktor:ktor-bom:3.6.0"))
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-auth:3.6.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
}
