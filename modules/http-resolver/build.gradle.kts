import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

group = "com.sphereon.oid.fed"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven("https://jitpack.io")
}

kotlin {
    jvm()

    js(IR) {
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
        binaries.library()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-http-resolver"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation HTTP Resolver Module"
            customField("description", "OpenID Federation HTTP Resolver Module")
            customField("license", "Apache-2.0")
            customField("author", "Sphereon International")
            customField(
                "repository", mapOf(
                    "type" to "git",
                    "url" to "https://github.com/Sphereon-Opensource/openid-federation"
                )
            )

            customField(
                "publishConfig", mapOf(
                    "access" to "public"
                )
            )

            types = "./index.d.ts"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation(project(":modules:cache"))
                implementation(project(":modules:logger"))
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
                implementation("io.ktor:ktor-client-mock:2.3.7")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-cio:2.3.7")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.1")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}