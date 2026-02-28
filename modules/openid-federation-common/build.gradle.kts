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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationOpenapi)
                // Core module for OidfConfigKeys used by LegacyEnvMappingPropertySource
                api(projects.modules.openidFederationCorePublic)
                // IDK compat annotations for @JsExportCompat
                api(idklib.sphereon.idk.lib.core.compat)
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
                // nimbus-jose-jwt removed - no longer used in source code
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
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core.js)
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

// Replace wasmJs npm-publish tasks: mainFile provider has no value on Kotlin 2.3.x wasmJs targets
afterEvaluate {
    listOf("assembleWasmJsPackage", "packWasmJsPackage", "publishWasmJsPackageToNpmjsRegistry").forEach { taskName ->
        try { tasks.replace(taskName) } catch (_: Exception) {}
    }
}
