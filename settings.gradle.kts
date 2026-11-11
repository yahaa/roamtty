pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Roamtty"
include(":app", ":ssh-core", ":desktop-core", ":code-core", ":terminal-emulator", ":terminal-view")
