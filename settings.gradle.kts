// Supply-chain rules (see docs/security/review-0.1.0.md, finding 5):
// - Google's repository may serve ONLY Android/AndroidX groups, and those groups may
//   come ONLY from it, so nothing else can be swapped in under those names.
// - Everything else comes from Maven Central. The Gradle Plugin Portal (open
//   publishing) is not used.
// (pluginManagement is compiled on its own, so the rule is repeated, not shared.)
// - gradle/verification-metadata.xml pins the SHA-256 of every artifact.
pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.testing\\.platform(\\..*)?")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.testing\\.platform(\\..*)?")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "android-sweeper"
include(":app")
