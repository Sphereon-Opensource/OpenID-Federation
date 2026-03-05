import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    id("maven-publish")
}

kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-client-public"
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
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
                // Core module with FederationResult, error types, and cache infrastructure
                api(projects.modules.openidFederationCorePublic)

                // OpenAPI models
                api(projects.modules.openidFederationOpenapi)

                // HTTP resolver for fetching federation data
                api(projects.modules.openidFederationHttpResolver)

                // IDK core API (for Command interface and logging)
                api(idklib.sphereon.idk.lib.core.api.public)

                // IDK compat annotations for @JsExportCompat
                api(idklib.sphereon.idk.lib.core.compat)

                // IDK crypto libraries (for JwtService interface)
                api(idklib.sphereon.idk.lib.crypto.core.public)

                // Standard library
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
                implementation(sphereonlib.io.ktor.client.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Client Public")
                description.set("Client interfaces for OpenID Federation")
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
