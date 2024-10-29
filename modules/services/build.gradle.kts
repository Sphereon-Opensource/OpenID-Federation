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
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openapi)
                api(projects.modules.persistence)
                api(projects.modules.openidFederationCommon)
                api(projects.modules.localKms)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
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
