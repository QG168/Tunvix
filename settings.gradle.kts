pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Tunvix"
include(":app", ":core:contract")
// Backend owners add their own modules here via optional local manifests, without editing this file.
listOf("importer", "catalog", "playback", "audio-decoder").forEach { name ->
    if (providers.gradleProperty("uiOnly").orNull != "true" && file("backend/$name/build.gradle.kts").exists()) include(":backend:$name")
}
