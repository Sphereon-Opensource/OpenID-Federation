plugins {
    alias(libs.plugins.kotlinMultiplatform)
    kotlin("plugin.serialization") version "2.1.0"
}

group = "com.sphereon.oid.fed.logger"

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    js(IR) {
        browser()
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kermit.logging)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.kermit.logging)
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(libs.kermit.logging)
            }
        }
    }
}