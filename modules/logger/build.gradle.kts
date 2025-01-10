plugins {
    alias(libs.plugins.kotlinMultiplatform)

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
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(libs.kermit.logging)
            }
        }
    }
}
