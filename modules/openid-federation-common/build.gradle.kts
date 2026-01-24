plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    id("maven-publish")
    alias(sphereonplug.plugins.dev.petuska.npm.publish.dev.petuska.npm.publish.gradle.plugin)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js(IR) {
        /*browser {
             useEsModules()
             commonWebpackConfig {
                 devServer = KotlinWebpackConfig.DevServer().apply {
                     port = 8083
                 }
             }
         }*/
        nodejs {
            useEsModules()
            testTask {
                /*     useMocha {
                         timeout = "5000"
                     }*/
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
            customField("type", "module")
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
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationOpenapi)
                // Core module for OidfConfigKeys used by LegacyEnvMappingPropertySource
                api(projects.modules.openidFederationCorePublic)
                implementation(sphereonlib.io.ktor.client.core)
                implementation(sphereonlib.io.ktor.client.logging)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.io.ktor.client.auth)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(sphereonlib.io.ktor.client.mock)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(sphereonlib.io.ktor.client.core.jvm)
                runtimeOnly(sphereonlib.io.ktor.client.cio.jvm)
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
                runtimeOnly(sphereonlib.io.ktor.client.core.js)
                runtimeOnly(sphereonlib.io.ktor.client.js)
                implementation(npm("typescript", "5.5.3"))
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
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
