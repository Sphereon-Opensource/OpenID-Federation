import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
//    alias(libs.plugins.androidLibrary)
    kotlin("plugin.serialization") version "2.0.0"
}

val ktorVersion = "2.3.11"

repositories {
    mavenCentral()
    mavenLocal()
    google()
}

kotlin {
    jvm()

    js {
        browser {
            commonWebpackConfig {
                devServer = KotlinWebpackConfig.DevServer().apply {
                    port = 8083
                }
            }
        }
        nodejs {
            testTask {
                useMocha {
                    timeout = "5000"
                }
            }
        }
    }

    // wasmJs is not available yet for ktor until v3.x is released which is still in alpha

//    androidTarget {
//        @OptIn(ExperimentalKotlinGradlePluginApi::class)
//        compilerOptions {
//            jvmTarget.set(JvmTarget.JVM_11)
//        }
//    }

//    iosX64()
//    iosArm64()
//    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openapi)
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-logging:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-client-auth:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1")
                implementation(libs.kermit.logging)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0-RC")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
                runtimeOnly("io.ktor:ktor-client-cio-jvm:$ktorVersion")
                implementation("com.nimbusds:nimbus-jose-jwt:9.40")
                implementation(project(":modules:local-kms"))
                implementation(project(":modules:openid-federation-common"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
//  TODO Should be placed back at a later point in time: https://sphereon.atlassian.net/browse/OIDF-50
//        val androidMain by getting {
//            dependencies {
//                implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
//                implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
//            }
//        }
//        val androidUnitTest by getting {
//            dependencies {
//                implementation(kotlin("test-junit"))
//            }
//        }

//        val iosMain by creating {
//            dependsOn(commonMain)
//        }
//        val iosX64Main by getting {
//            dependsOn(iosMain)
//            dependencies {
//                implementation("io.ktor:ktor-client-core-iosx64:$ktorVersion")
//                implementation("io.ktor:ktor-client-cio-iosx64:$ktorVersion")
//            }
//        }
//        val iosArm64Main by getting {
//            dependsOn(iosMain)
//            dependencies {
//                implementation("io.ktor:ktor-client-core-iosarm64:$ktorVersion")
//                implementation("io.ktor:ktor-client-cio-iosarm64:$ktorVersion")
//            }
//        }
//        val iosSimulatorArm64Main by getting {
//            dependsOn(iosMain)
//            dependencies {
//                implementation("io.ktor:ktor-client-core-iossimulatorarm64:$ktorVersion")
//                implementation("io.ktor:ktor-client-cio-iossimulatorarm64:$ktorVersion")
//            }
//        }
//
//        val iosTest by creating {
//            dependsOn(commonTest)
//            dependencies {
//                implementation(kotlin("test"))
//            }
//        }

        val jsMain by getting {
            dependencies {
                runtimeOnly("io.ktor:ktor-client-core-js:$ktorVersion")
                runtimeOnly("io.ktor:ktor-client-js:$ktorVersion")
                implementation(npm("typescript", "5.5.3"))
                implementation(npm("jose", "5.6.3"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0-RC")
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                implementation(kotlin("test-annotations-common"))
            }
        }
    }
}

//tasks.register("printSdkLocation") {
//    doLast {
//        println("Android SDK Location: ${android.sdkDirectory}")
//    }
//}
//
//android {
//    namespace = "com.sphereon.oid.fed.common"
//    compileSdk = libs.versions.android.compileSdk.get().toInt()
//    compileOptions {
//        sourceCompatibility = JavaVersion.VERSION_11
//        targetCompatibility = JavaVersion.VERSION_11
//    }
//    defaultConfig {
//        minSdk = libs.versions.android.minSdk.get().toInt()
//    }
//}

