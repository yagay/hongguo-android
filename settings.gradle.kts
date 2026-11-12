// Prefer Google's public Maven Central mirror when Maven Central returns 403 on CI runners.
// Keep canonical repositories as fallback; no project-local repositories allowed.
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy("https://github.com/kadaliao/openhongguo/releases/download") {
                    name = "openhongguo-kaixinguo"
                    patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("io.github.kadaliao", "openhongguo") }
        }
    }
}
rootProject.name = "HongguoNativeRewrite"
include(":app")
