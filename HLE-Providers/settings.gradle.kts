pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://api.xposed.info/") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "HLE-Providers"
include(":providers:kuwo")
include(":providers:netease")
include(":providers:qqmusic")
include(":providers:salt-player")
include(":providers:kugou")
include(":providers:qishui")
include(":providers:spotify")
