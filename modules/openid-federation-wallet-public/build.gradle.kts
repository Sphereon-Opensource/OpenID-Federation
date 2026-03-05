import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    id("maven-publish")
}

kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-wallet-public"
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
                // Federation client interfaces (FederationClient, commands, FederationContext)
                api(projects.modules.openidFederationClientPublic)

                // Core module with FederationResult, error types
                api(projects.modules.openidFederationCorePublic)

                // OpenAPI models (EntityConfigurationStatement, TrustMark, etc.)
                api(projects.modules.openidFederationOpenapi)

                // IDK core API (for Command interface and logging)
                api(idklib.sphereon.idk.lib.core.api.public)

                // IDK compat annotations for @JsExportCompat
                api(idklib.sphereon.idk.lib.core.compat)

                // Standard library
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
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
                name.set("OpenID Federation Wallet Public")
                description.set("Wallet architecture interfaces for OpenID Federation")
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
