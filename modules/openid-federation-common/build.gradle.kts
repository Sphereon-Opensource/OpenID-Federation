import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    kotlin("plugin.serialization") version "2.0.0"
}

val ktorVersion = "2.3.11"

repositories {
    mavenCentral()
    mavenLocal()
    google()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)

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

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.sphereon.oid.fed:openapi:0.1.0-SNAPSHOT")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-logging:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-client-auth:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.0")
                implementation(libs.kermit.logging)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
                runtimeOnly("io.ktor:ktor-client-cio-jvm:$ktorVersion")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
                implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
        }
        val iosX64Main by getting {
            dependsOn(iosMain)
            dependencies {
                implementation("io.ktor:ktor-client-core-iosx64:$ktorVersion")
                implementation("io.ktor:ktor-client-cio-iosx64:$ktorVersion")
            }
        }
        val iosArm64Main by getting {
            dependsOn(iosMain)
            dependencies {
                implementation("io.ktor:ktor-client-core-iosarm64:$ktorVersion")
                implementation("io.ktor:ktor-client-cio-iosarm64:$ktorVersion")
            }
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
            dependencies {
                implementation("io.ktor:ktor-client-core-iossimulatorarm64:$ktorVersion")
                implementation("io.ktor:ktor-client-cio-iossimulatorarm64:$ktorVersion")
            }
        }

        val iosTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jsMain by getting {
            dependencies {
                runtimeOnly("io.ktor:ktor-client-core-js:$ktorVersion")
                runtimeOnly("io.ktor:ktor-client-js:$ktorVersion")
            }
        }

        val jsTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test-js"))
                implementation(kotlin("test-annotations-common"))
            }
        }
    }
}

tasks.register("printSdkLocation") {
    doLast {
        println("Android SDK Location: ${android.sdkDirectory}")
    }
}

android {
    namespace = "com.sphereon.oid.fed.common"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

