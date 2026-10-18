pluginManagement {
    repositories {
        // -Psm.mirror=aliyun: зеркало Google/Maven Central (если основные серверы недоступны).
        if (providers.gradleProperty("sm.mirror").orNull == "aliyun") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

val smMirror = providers.gradleProperty("sm.mirror").orNull

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (smMirror == "aliyun") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "StoykoMessenger"

include(":app")
include(":core")
include(":domain")
