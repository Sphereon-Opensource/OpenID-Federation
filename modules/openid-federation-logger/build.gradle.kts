plugins {
    alias(libs.plugins.kotlinMultiplatform)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
repositories {
    mavenCentral()
}

kotlin {
    jvm()

    js(IR) {
        nodejs {
            useEsModules()
            testTask {
                /*useMocha {
                    timeout = "5000"
                }*/
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kermit.logging)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
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
