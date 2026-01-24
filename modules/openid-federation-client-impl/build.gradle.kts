plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.dev.petuska.npm.publish.dev.petuska.npm.publish.gradle.plugin)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    id("maven-publish")
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js(IR) {
        nodejs {
            testTask {
            }
            useEsModules()
        }
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
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

    sourceSets {
        all {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
        }

        val commonMain by getting {
            dependencies {
                // Public interfaces
                api(projects.modules.openidFederationClientPublic)

                // Core implementation for caching
                api(projects.modules.openidFederationCoreImpl)

                // Kache dependencies are needed transitively via core-impl
                implementation(sphereonlib.com.mayakapps.kache.kache)
                implementation(sphereonlib.com.mayakapps.kache.file.kache)

                // IDK core and crypto
                api(libs.idk.core.api.public)
                api(libs.idk.crypto.core.public)
                api(libs.idk.crypto.core.impl)
                api(libs.idk.crypto.kms.provider.software)

                // kotlin-inject for DI
                implementation(libs.bundles.kotlin.inject)

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
                implementation(libs.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(sphereonlib.io.ktor.client.mock)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(libs.amz.kotlin.inject.impl)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.idk.crypto.kms.provider.software)
                // HTTP client factory implementation for DI in tests
                implementation(libs.idk.data.link.http.client.impl)
                // IDK core defaults for DI bindings in JVM tests only
                // (contains JVM-specific code that doesn't work on JS)
                implementation(libs.idk.core.api.default)
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
                implementation(libs.kotlinx.coroutines.core.js)
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                implementation(libs.kotlinx.coroutines.test.js)
                implementation(libs.ktor.client.mock.js)
                implementation(libs.idk.crypto.kms.provider.software)
                // HTTP client factory implementation for DI in tests
                implementation(libs.idk.data.link.http.client.impl)
                // Core defaults for test component infrastructure
                implementation(libs.idk.core.api.default)
            }
        }
    }
}

ksp {
    // We are using the Amazon App Platform binding processor instead!
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

fun DependencyHandlerScope.addKspDependencies(configName: String) {
    addProvider(configName, libs.kotlin.inject.compiler.ksp)
    add(configName, libs.amz.kotlin.inject.contribute.public)
    add(configName, libs.amz.kotlin.inject.contribute.code.generators)
    add(configName, libs.anvil.compiler.ksp)
}

// KSP dependencies for kotlin-inject code generation
dependencies {
    addKspDependencies("kspJvm")
    addKspDependencies("kspJvmTest")
    addKspDependencies("kspJs")
    addKspDependencies("kspJsTest")
}

// Needed because KSP-generated code needs to be included in the source sets
kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
}

kotlin.sourceSets.named("jsTest") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
    kotlin.srcDir("build/generated/ksp/js/jsTest/kotlin")
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
