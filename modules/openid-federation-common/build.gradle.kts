import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("maven-publish")
    alias(libs.plugins.npmPublish)
}


repositories {
    mavenCentral()
    mavenLocal()
    google()
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
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
            name = "@sphereon/openid-federation-common"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Common Library"
            customField("description", "OpenID Federation Common Library")
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
                api(projects.modules.openapi)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.core.jvm)
                runtimeOnly(libs.ktor.client.cio.jvm)
                implementation(libs.nimbus.jose.jwt)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val jsMain by getting {
            dependencies {
                runtimeOnly(libs.ktor.client.core.js)
                runtimeOnly(libs.ktor.client.js)
                implementation(npm("typescript", "5.5.3"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core.js)
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(npm("jose", "5.6.3"))
                implementation(kotlin("test-js"))
                implementation(kotlin("test-annotations-common"))
            }
        }
    }
}


npmPublish {
    registries {
        register("npmjs") {
            uri.set("https://registry.npmjs.org")
            authToken.set(System.getenv("NPM_TOKEN") ?: "")
        }
    }
    packages {
        named("js") {
            packageJson {
                "name" by "@sphereon/openid-federation-common"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-common")
        }
    }
}
