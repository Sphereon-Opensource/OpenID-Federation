plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    id("maven-publish")
    alias(sphereonplug.plugins.dev.petuska.npm.publish.dev.petuska.npm.publish.gradle.plugin)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js(IR) {
        nodejs {
            useEsModules()
            testTask {
            }
        }

        binaries.library()
        generateTypeScriptDefinitions()

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

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Core public API (our federation errors and types)
                api(projects.modules.openidFederationCorePublic)

                // IDK core API for logging, sessions, etc.
                api(libs.idk.core.api.public)
                implementation(libs.idk.core.api.default)

                // Serialization
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.core)

                // Coroutines
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)

                // kotlin-inject runtime
                implementation(libs.kotlin.inject.runtime)

                // Kache for in-memory caching
                implementation(sphereonlib.com.mayakapps.kache.kache)

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
                implementation(libs.kotlinx.coroutines.core.js)
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

// KSP configuration for kotlin-inject
dependencies {
    add("kspCommonMainMetadata", libs.kotlin.inject.compiler.ksp)
    add("kspJvm", libs.kotlin.inject.compiler.ksp)
    add("kspJs", libs.kotlin.inject.compiler.ksp)
}

// Configure KSP for all targets
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
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
