plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
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

                // Account public interfaces (for account endpoint commands)
                api(projects.modules.openidFederationAccountPublic)

                // Common utilities
                api(projects.modules.openidFederationCommon)

                // IDK core APIs (KMP-compatible)
                api(idklib.sphereon.idk.lib.core.api.public)

                // Standard library
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)

                // kotlin-inject (KMP-compatible)
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.public)
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public)
                implementation(sphereonlib.software.amazon.app.platform.di.common.public)
                implementation(sphereonlib.software.amazon.app.platform.scope.public)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime.optional)
                implementation(sphereonlib.me.tatarka.inject.kotlin.inject.runtime.kmp)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                // JVM-specific dependencies for JsonMapper and other utilities
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Admin Server API")
                description.set("KMP-compatible API layer for OpenID Federation Admin Server")
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
