rootProject.name = "openid-federation"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
        }
        maven {
            url = uri("https://jitpack.io")
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("sureplug") {
            from("tech.4sure.gradle:gradle-plugin-bom:0.0.4-SNAPSHOT@toml")
        }
        create("surelib") {
            from("tech.4sure.gradle:library-bom:0.0.4-SNAPSHOT@toml")
        }
        // TODO: Move aws sdk to our bom
        create("awssdk") {
            from("aws.sdk.kotlin:version-catalog:1.4.31")
        }

    }
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
        }
        maven {
            url = uri("https://jitpack.io")
        }
    }
}
include(":modules:openid-federation-cache")
include(":modules:openid-federation-openapi")
include(":modules:openid-federation-services")
include(":modules:openid-federation-logger")
include(":modules:openid-federation-admin-server")
include(":modules:openid-federation-bom")
include(":modules:openid-federation-common")
include(":modules:openid-federation-client")
include(":modules:openid-federation-http-resolver")
include(":modules:openid-federation-persistence")
include(":modules:openid-federation-server")
include(":modules:openid-federation-integration-tests")
include(":modules:openid-federation-conformance-tests")
