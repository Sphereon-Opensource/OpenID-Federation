import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

group = "com.sphereon.oid.fed"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
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
            name = "@sphereon/openid-federation-cache"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Cache Module"
            customField("description", "OpenID Federation Cache Module")
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
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kache)
                implementation(libs.kache.file)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
