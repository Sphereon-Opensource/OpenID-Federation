/**
 * Facade module for backward compatibility.
 *
 * This module re-exports both openid-federation-client-public and openid-federation-client-impl,
 * providing a single dependency for consumers who want all client functionality.
 *
 * New code should prefer depending on:
 * - openid-federation-client-public: For interfaces only (minimal dependencies)
 * - openid-federation-client-impl: For implementations with DI support
 *
 * This facade ensures backward compatibility for existing consumers.
 */
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.npm.publish.org.jetbrains.kotlin.npm.publish.gradle.plugin)
    id("maven-publish")
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js(IR) {
        nodejs {
            useEsModules()
        }
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-client"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Client Library"
            customField("description", "OpenID Federation Client Library")
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
        all {
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
        }

        val commonMain by getting {
            dependencies {
                // Re-export both public interfaces and implementations
                api(projects.modules.openidFederationClientPublic)
                api(projects.modules.openidFederationClientImpl)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val jvmMain by getting {
            dependencies {
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test.junit)
            }
        }

        val jsMain by getting {
            dependencies {
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
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
                "name" by "@sphereon/openid-federation-client"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-client")
        }
    }
}

// Replace wasmJs npm-publish tasks: mainFile provider has no value on Kotlin 2.3.x wasmJs targets
afterEvaluate {
    listOf("assembleWasmJsPackage", "packWasmJsPackage", "publishWasmJsPackageToNpmjsRegistry").forEach { taskName ->
        try { tasks.replace(taskName) } catch (_: Exception) {}
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Client")
                description.set("OpenID Federation Client Library (facade)")
                url.set("https://github.com/Sphereon-Opensource/openid-federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
