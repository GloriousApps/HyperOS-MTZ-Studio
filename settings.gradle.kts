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

rootProject.name = "HyperOS-MTZ-Studio"
include(":app", ":mtz-core", ":mtz-library", ":mtz-composer", ":tester-adapter", ":ppocr-sdk")
