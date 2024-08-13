plugins {
    kotlin("multiplatform") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
}

group = "com.sphereon.oid.fed.services"
version = "0.1.0"

repositories {
    mavenCentral()
    mavenLocal()
    google()
}

kotlin {
//    js {
//        browser()
//        nodejs()
//    }
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openapi)
                api(projects.modules.persistence)
                api(projects.modules.openidFederationCommon)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

//        val jsMain by getting {
//            dependencies {
////                implementation(npm("jose", "5.6.3"))
//            }
//        }
    }
}
