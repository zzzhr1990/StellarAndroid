// Stable transitive dependency baseline, verified against publisher repositories on 2026-09-25.
// Constraints upgrade an artifact only when another dependency actually brings it in.
// They do not add Android libraries to the platform-independent player-contract module.
val stableTransitives = listOf(
    "androidx.activity:activity:1.13.0",
    "androidx.activity:activity-compose:1.13.0",
    "androidx.activity:activity-ktx:1.13.0",
    "androidx.annotation:annotation:1.11.0",
    "androidx.annotation:annotation-experimental:1.6.0",
    "androidx.appcompat:appcompat:1.8.0",
    "androidx.appcompat:appcompat-resources:1.8.0",
    "androidx.autofill:autofill:1.3.0",
    "androidx.collection:collection:1.6.0",
    "androidx.collection:collection-ktx:1.6.0",
    "androidx.concurrent:concurrent-futures:1.3.0",
    "androidx.concurrent:concurrent-futures-ktx:1.3.0",
    "androidx.core:core:1.19.1",
    "androidx.core:core-ktx:1.19.1",
    "androidx.customview:customview:1.2.0",
    "androidx.customview:customview-poolingcontainer:1.1.0",
    "androidx.drawerlayout:drawerlayout:1.2.0",
    "androidx.emoji2:emoji2:1.7.0",
    "androidx.fragment:fragment:1.9.1",
    "androidx.graphics:graphics-path:1.1.0",
    // New Material3 transitive, verified against Google Maven on 2026-09-28.
    "androidx.graphics:graphics-shapes:1.1.0",
    "androidx.lifecycle:lifecycle-common:2.11.0",
    "androidx.lifecycle:lifecycle-runtime:2.11.0",
    "androidx.loader:loader:1.2.0",
    "androidx.navigationevent:navigationevent:1.1.2",
    "androidx.navigationevent:navigationevent-compose:1.1.2",
    "androidx.profileinstaller:profileinstaller:1.4.1",
    "androidx.savedstate:savedstate:1.5.0",
    "androidx.savedstate:savedstate-compose:1.5.0",
    "androidx.savedstate:savedstate-ktx:1.5.0",
    "androidx.startup:startup-runtime:1.2.0",
    "androidx.tracing:tracing:2.0.3",
    "androidx.vectordrawable:vectordrawable:1.2.0",
    "androidx.vectordrawable:vectordrawable-animated:1.2.0",
    "androidx.versionedparcelable:versionedparcelable:1.2.1",
    "androidx.viewpager:viewpager:1.1.0",
    "androidx.window:window:1.5.1",
    "androidx.window:window-core:1.5.1",
    "com.squareup.okhttp3:okhttp:5.5.0",
    "com.squareup.okio:okio:3.18.2",
    "org.hamcrest:hamcrest-core:3.0",
    "org.hamcrest:hamcrest-library:3.0",
    "org.jetbrains:annotations:26.1.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0",
    "org.jspecify:jspecify:1.0.1",
    "org.slf4j:slf4j-api:2.0.20",
)

configurations.matching {
    it.name in setOf("implementation", "testImplementation", "androidTestImplementation")
}.configureEach {
    val scope = name
    stableTransitives.forEach { coordinate ->
        project.dependencies.constraints.add(scope, coordinate) {
            because("Use the verified stable dependency baseline; see docs/DEPENDENCY_UPGRADE_20260925.md")
        }
    }
}
