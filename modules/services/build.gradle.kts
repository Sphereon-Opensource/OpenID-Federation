plugins {
    alias(libs.plugins.kotlinMultiplatform)
    kotlin("plugin.serialization") version "2.0.0"
    id("maven-publish")
}

group = "com.sphereon.oid.fed.services"

repositories {
    mavenCentral()
    mavenLocal()
    google()

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

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationClient)
                api(projects.modules.logger)
                api(projects.modules.openapi)
                api(projects.modules.persistence)
                api(projects.modules.openidFederationCommon)
                api(projects.modules.localKms)
                implementation(libs.ktor.serialization.kotlinx.json)
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
                // Add Ktor client core and engine dependencies
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.sphereon.kmp.crypto.kms)
                implementation(libs.sphereon.kmp.crypto.kms.azure)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(projects.modules.logger)
                implementation(projects.modules.openapi)
                implementation(projects.modules.persistence)
                implementation(projects.modules.openidFederationCommon)
                implementation(projects.modules.localKms)
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
                implementation("io.mockk:mockk:1.13.9")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("io.mockk:mockk:1.13.9")
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Services")
                description.set("Services module for OpenID Federation")
                url.set("https://github.com/Sphereon-Opensource/openid-federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
