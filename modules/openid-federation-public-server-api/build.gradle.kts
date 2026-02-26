plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    id("maven-publish")
}

kotlin {
    jvm()
    // Future: js(IR) { ... }
    // Future: native targets

    sourceSets {
        all {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
        }

        val commonMain by getting {
            dependencies {
                // Core module with FederationResult, error types, and cache infrastructure
                api(projects.modules.openidFederationCorePublic)

                // OpenAPI models
                api(projects.modules.openidFederationOpenapi)

                // Services public interfaces
                api(projects.modules.openidFederationServicesPublic)

                // Persistence for direct query access
                api(projects.modules.openidFederationPersistence)

                // Common utilities
                api(projects.modules.openidFederationCommon)

                // IDK core APIs (KMP-compatible)
                api(libs.idk.core.api.public)

                // Standard library
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)

                // kotlin-inject (KMP-compatible)
                implementation(libs.bundles.kotlin.inject)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// KSP configuration for kotlin-inject with Anvil
// Use Amazon App Platform binding processor instead of Anvil's
ksp {
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

dependencies {
    add("kspJvm", libs.kotlin.inject.compiler.ksp)
    add("kspJvm", libs.amz.kotlin.inject.contribute.public)
    add("kspJvm", libs.amz.kotlin.inject.contribute.code.generators)
    add("kspJvm", libs.anvil.compiler.ksp)
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Public Server API")
                description.set("KMP-compatible API layer for OpenID Federation Server")
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
