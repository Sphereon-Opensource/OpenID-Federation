rootProject.name = "openid-federation"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
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
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots/")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases/")
        }
        gradlePluginPortal()
        maven {
            url = uri("https://jitpack.io")
        }
        // Keep maven local at the end
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("sphereonplug") {
            from("com.sphereon.gradle:gradle-plugin-bom:0.5.0@toml" as String)
        }
        create("sphereonlib") {
            from("com.sphereon.gradle:library-bom:0.5.0@toml" as String)
        }
        // TODO: Move aws sdk to our bom
        create("awssdk") {
            from("aws.sdk.kotlin:version-catalog:1.4.31" as String)
        }
    }
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
        }
        maven {
            url = uri("https://jitpack.io")
        }
        // Keep maven local at the end
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
    }
}

// Core modules (IDK migration)
// NOTE: These modules should use IDK types directly, not duplicate them
include(":modules:openid-federation-core-public")
include(":modules:openid-federation-core-impl")

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
