import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("maven-publish")
    alias(sphereonplug.plugins.org.jetbrains.kotlin.npm.publish.org.jetbrains.kotlin.npm.publish.gradle.plugin)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-core-impl"
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
        }

        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-core-impl"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Core Implementation - Logging, config adapters for IDK integration"
            customField("description", "OpenID Federation Core Implementation")
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
                // Core public API (our federation errors and types)
                api(projects.modules.openidFederationCorePublic)

                // IDK core API for logging, sessions, etc.
                api(idklib.sphereon.idk.lib.core.api.public)
                // IDK cache infrastructure (provides KacheCacheBackend on non-wasm, MapCacheBackend on wasmJs)
                api(idklib.sphereon.idk.lib.core.api.default)

                // IDK crypto (optional JWE adapter — IdkOidfJweService)
                implementation(idklib.sphereon.idk.lib.crypto.core.public)

                // Serialization
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.core)

                // Coroutines
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)

                // DateTime for cache TTL
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
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

        val wasmJsMain by getting {
            dependencies {
            }
        }

        val wasmJsTest by getting {
            dependencies {
                implementation(kotlin("test"))
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
                "name" by "@sphereon/openid-federation-core-impl"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-core-impl")
        }
    }
}

// Replace wasmJs npm-publish tasks: mainFile provider has no value on Kotlin 2.3.x wasmJs targets
afterEvaluate {
    listOf("assembleWasmJsPackage", "packWasmJsPackage", "publishWasmJsPackageToNpmjsRegistry").forEach { taskName ->
        try { tasks.replace(taskName) } catch (_: Exception) {}
    }
}
