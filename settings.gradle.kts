pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1") }
            filter { includeModule("com.microsoft.device.display", "display-mask") }
        }
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeModule("com.github.bitfireAT", "dav4jvm") }
        }
    }
}
rootProject.name = "StellarAndroid"
include(":app", ":player-ui", ":player-contract", ":player-native", ":player-mpv", ":player-source", ":player-disc")
