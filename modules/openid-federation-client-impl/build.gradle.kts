import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.npm.publish.org.jetbrains.kotlin.npm.publish.gradle.plugin)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    id("maven-publish")
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-client-impl"
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
        }
        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-client-impl"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Client Implementation Library"
            customField("description", "OpenID Federation Client Implementation Library")
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
                // Public interfaces
                api(projects.modules.openidFederationClientPublic)

                // Core implementation for caching
                api(projects.modules.openidFederationCoreImpl)

                // IDK core and crypto
                api(idklib.sphereon.idk.lib.core.api.public)
                api(idklib.sphereon.idk.lib.crypto.core.public)
                api(idklib.sphereon.idk.lib.crypto.core.impl)
                api(idklib.sphereon.idk.lib.crypto.kms.provider.software)

                // kotlin-inject for DI
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.public)
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public)
                implementation(sphereonlib.software.amazon.app.platform.di.common.public)
                implementation(sphereonlib.software.amazon.app.platform.scope.public)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime.optional)
                implementation(sphereonlib.me.tatarka.inject.kotlin.inject.runtime.kmp)

                // Standard library
                implementation(sphereonlib.io.ktor.client.core)
                implementation(sphereonlib.io.ktor.client.logging)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.io.ktor.client.auth)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(sphereonlib.io.ktor.client.mock)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.impl)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(sphereonlib.io.ktor.client.java)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test.junit)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
                // HTTP client factory implementation for DI in tests
                implementation(idklib.sphereon.idk.lib.data.link.http.client.impl)
                // IDK core defaults for DI bindings in JVM tests only
                // (contains JVM-specific code that doesn't work on JS)
                implementation(idklib.sphereon.idk.lib.core.api.default)
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(sphereonlib.io.ktor.client.js)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core.js)
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test.js)
                implementation(sphereonlib.io.ktor.client.mock.js)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
                // HTTP client factory implementation for DI in tests
                implementation(idklib.sphereon.idk.lib.data.link.http.client.impl)
                // Core defaults for test component infrastructure
                implementation(idklib.sphereon.idk.lib.core.api.default)
            }
        }
    }
}

ksp {
    // We are using the Amazon App Platform binding processor instead!
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

fun DependencyHandlerScope.addKspDependencies(configName: String) {
    addProvider(configName, sphereonlib.me.tatarka.inject.kotlin.inject.compiler.ksp)
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public.get())
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.impl.code.generators.get())
    add(configName, sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.compiler.get())
}

// KSP dependencies for kotlin-inject code generation
dependencies {
    addKspDependencies("kspJvm")
    addKspDependencies("kspJvmTest")
    addKspDependencies("kspJs")
    addKspDependencies("kspJsTest")
    addKspDependencies("kspWasmJs")
    addKspDependencies("kspWasmJsTest")
}

// Needed because KSP-generated code needs to be included in the source sets
kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
}

kotlin.sourceSets.named("jsTest") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
    kotlin.srcDir("build/generated/ksp/js/jsTest/kotlin")
}

kotlin.sourceSets.named("wasmJsMain") {
    kotlin.srcDir("build/generated/ksp/wasmJs/wasmJsMain/kotlin")
}

kotlin.sourceSets.named("wasmJsTest") {
    kotlin.srcDir("build/generated/ksp/wasmJs/wasmJsMain/kotlin")
    kotlin.srcDir("build/generated/ksp/wasmJs/wasmJsTest/kotlin")
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
                "name" by "@sphereon/openid-federation-client-impl"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-client-impl")
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
                name.set("OpenID Federation Client Implementation")
                description.set("Client implementations for OpenID Federation")
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
