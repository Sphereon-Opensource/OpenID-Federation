plugins {
    alias(sureplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sureplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
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
                implementation(surelib.org.jetbrains.kotlinx.datetime)
                implementation(surelib.org.jetbrains.kotlinx.serialization.json)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(surelib.io.ktor.client.mock)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.test)
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
