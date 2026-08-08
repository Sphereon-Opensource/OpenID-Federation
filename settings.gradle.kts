rootProject.name = "openid-federation"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // Prefer local Sphereon/IDK publishes over remote SNAPSHOTs (metadata must match Metro).
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroupAndSubgroups("software.amazon") }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases/")
            mavenContent { releasesOnly() }
        }
        maven {
            url = uri("https://jitpack.io")
        }
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    // Project-level repositories {} would replace these (Gradle PREFER_PROJECT default)
    // and silently drop Sphereon Nexus — that broke openapi CI for IDK SNAPSHOTs.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    versionCatalogs {
        create("sphereonplug") {
            from("com.sphereon.gradle:gradle-plugin-bom:0.9.0-SNAPSHOT@toml" as String)
        }
        create("sphereonlib") {
            from("com.sphereon.gradle:library-bom:0.9.0-SNAPSHOT@toml" as String)
        }
        create("idklib") {
            from("com.sphereon.idk:idk-bom:0.25.0-SNAPSHOT@toml" as String)
        }
        // TODO: Move aws sdk to our bom
        create("awssdk") {
            from("aws.sdk.kotlin:version-catalog:1.6.107" as String)
        }
    }
    repositories {
        // Prefer local Sphereon/IDK publishes over remote SNAPSHOTs (metadata must match Metro).
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Nexus repos also proxy/cache external deps (e.g. software.amazon) — no group filter
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
            mavenContent { releasesOnly() }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroupAndSubgroups("software.amazon") }
        }
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://jitpack.io")
        }
        gradlePluginPortal()

        // Kotlin/JS + node-gradle download Node/Yarn via Ivy, not Maven.
        // With PREFER_SETTINGS, project.repositories.ivy { } is ignored for resolution
        // (see KT-55620 / kotlinNodeJsSetup), so these must live in settings.
        ivy {
            name = "Node.js distributions"
            url = uri("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn distributions"
            url = uri("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

// Core modules (IDK migration)
// NOTE: These modules should use IDK types directly, not duplicate them
include(":modules:openid-federation-core-public")
include(":modules:openid-federation-core-impl")

// Account modules (optional account-based multi-tenancy)
include(":modules:openid-federation-account-public")
include(":modules:openid-federation-account-impl")
// LEGACY /accounts REST only — optional dependency (on classpath = REST present)
include(":modules:openid-federation-account-http")

// Existing modules
include(":modules:openid-federation-openapi")
include(":modules:openid-federation-services")
include(":modules:openid-federation-services-public")
include(":modules:openid-federation-services-impl")
include(":modules:openid-federation-bom")
include(":modules:openid-federation-common")
include(":modules:openid-federation-client")
include(":modules:openid-federation-client-public")
include(":modules:openid-federation-client-impl")
include(":modules:openid-federation-client-test-js")

// Trust bridge module (bridges OID-Fed trust chain to IDK Trust Validation framework)
include(":modules:openid-federation-trust")

// Wallet architecture modules (OpenID Federation Wallet Architecture 1.0)
include(":modules:openid-federation-wallet-public")
include(":modules:openid-federation-wallet-impl")
include(":modules:openid-federation-http-resolver")
include(":modules:openid-federation-persistence")
include(":modules:openid-federation-integration-tests")
include(":modules:openid-federation-conformance-tests")

// Admin server modules (API layer is KMP, Ktor layer is JVM)
include(":modules:openid-federation-admin-server-api")
include(":modules:openid-federation-admin-server-ktor")
include(":modules:openid-federation-admin-server")  // Backwards compat shim

// Federation server modules (API layer is KMP, Ktor layer is JVM)
include(":modules:openid-federation-public-server-api")
include(":modules:openid-federation-public-server-ktor")
include(":modules:openid-federation-server")  // Backwards compat shim
