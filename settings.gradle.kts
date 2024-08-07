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
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
        }
    }
}

include(":modules:openid-federation-common")
include(":modules:admin-server")
include(":modules:openapi")
include(":modules:persistence")
include(":modules:services")
