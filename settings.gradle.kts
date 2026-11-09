pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AramAI"
include(":app", ":llama")

// The official llama.cpp Android binding is fetched by GitHub Actions before Gradle starts.
project(":llama").projectDir = file("vendor/llama.cpp/examples/llama.android/lib")
