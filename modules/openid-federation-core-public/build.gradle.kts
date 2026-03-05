import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    id("maven-publish")
    alias(sphereonplug.plugins.org.jetbrains.kotlin.npm.publish.org.jetbrains.kotlin.npm.publish.gradle.plugin)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-core-public"
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
        }

        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-core-public"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Core Public API - Error types and interfaces for IDK integration"
            customField("description", "OpenID Federation Core Public API")
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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // IDK core API for IdkResult, IdkError, IdkErrorType
                api(idklib.sphereon.idk.lib.core.api.public)

                // Serialization for error messages
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.core)

                // Ktor HTTP status codes
                implementation(sphereonlib.io.ktor.http)

                // DateTime for cache TTL configuration
                api(sphereonlib.org.jetbrains.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core.js)
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
                "name" by "@sphereon/openid-federation-core-public"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-core-public")
        }
    }
}

// Replace wasmJs npm-publish tasks: mainFile provider has no value on Kotlin 2.3.x wasmJs targets
afterEvaluate {
    listOf("assembleWasmJsPackage", "packWasmJsPackage", "publishWasmJsPackageToNpmjsRegistry").forEach { taskName ->
        try { tasks.replace(taskName) } catch (_: Exception) {}
    }
}
