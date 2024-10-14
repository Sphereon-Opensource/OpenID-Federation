plugins {
    kotlin("multiplatform") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
}

group = "com.sphereon.oid.fed.services"
version = "0.1.0"

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
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
