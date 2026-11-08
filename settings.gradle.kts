// Supply-chain rules (see docs/security/review-0.1.0.md, finding 5):
// - Google's repository may serve ONLY Android/AndroidX groups, and those groups may
//   come ONLY from it, so nothing else can be swapped in under those names.
// - Everything else comes from Maven Central. The Gradle Plugin Portal (open
//   publishing) is not used.
// - gradle/verification-metadata.xml pins the SHA-256 of every artifact.
pluginManagement {
    repositories {
        googleOnlyForAndroid()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        googleOnlyForAndroid()
        mavenCentral()
    }
}

fun RepositoryHandler.googleOnlyForAndroid() {
    exclusiveContent {
        forRepository { google() }
        filter {
            includeGroupByRegex("androidx(\\..*)?")
            includeGroupByRegex("com\\.android(\\..*)?")
            includeGroupByRegex("com\\.google\\.testing\\.platform(\\..*)?")
        }
    }
}

rootProject.name = "android-sweeper"
include(":app")
