pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// No toolchain resolver plugin (e.g. foojay) is applied on purpose: if a local
// Java 25 installation cannot be found, the build fails instead of silently
// provisioning or falling back to another JDK.

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // io.github.castab:commerce-runtime and commerce-domain are published to GitHub
        // Packages, and resolve only from there: exclusiveContent keeps Gradle from ever
        // looking for io.github.castab artifacts on Maven Central.
        //
        // GitHub Packages requires a token with read:packages even for public packages.
        // Credentials come from the GitHubPackagesUsername / GitHubPackagesPassword Gradle
        // properties (for example in ~/.gradle/gradle.properties), or else from the
        // GITHUB_ACTOR / GITHUB_TOKEN environment variables (CI). Never commit them.
        exclusiveContent {
            forRepository {
                maven {
                    name = "GitHubPackages"
                    // The registry of the repository that publishes both commerce artifacts.
                    url = uri("https://maven.pkg.github.com/castab/commerce-domain")
                    credentials {
                        username =
                            providers
                                .gradleProperty("GitHubPackagesUsername")
                                .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                                .orNull
                        password =
                            providers
                                .gradleProperty("GitHubPackagesPassword")
                                .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                                .orNull
                    }
                }
            }
            filter { includeGroup("io.github.castab") }
        }
        mavenCentral()
    }
}

rootProject.name = "fionas-commerce"
