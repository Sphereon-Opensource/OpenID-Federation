plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("maven-publish")
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Public interfaces
                api(projects.modules.openidFederationServicesPublic)

                // Account module (for backward compatibility re-exports)
                api(projects.modules.openidFederationAccountImpl)

                // Core module for config interfaces and TenantContextResolver
                api(projects.modules.openidFederationCorePublic)

                // Additional dependencies needed by implementations
                api(projects.modules.openidFederationClient)
                api(projects.modules.openidFederationCommon)

                // Metadata policy operators (Resolved Metadata for /resolve — OIDFed 1.1 §6.1 / §8.3)
                api(projects.modules.openidFederationWalletPublic)

                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
                implementation(sphereonlib.io.ktor.client.cio)

                // IDK crypto libraries
                implementation(idklib.sphereon.idk.lib.crypto.core.public)
                implementation(idklib.sphereon.idk.lib.crypto.core.impl)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.azure)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.aws)
                implementation(sphereonlib.dev.whyoleg.cryptography.core)

                // IDK AppConfigService / default config pipeline for OidfConfigBinder
                api(idklib.sphereon.idk.lib.core.api.default)

                // IDK YAML property sources (APP + tenant + principal scopes via PropertySourceContribution)
                // Do not reimplement YAML parsing in OIDFed — use this for application.yaml / tenant YAML.
                api("com.sphereon.idk:lib-conf-yaml:0.25.0-SNAPSHOT")

            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(sphereonlib.io.mockk.mockk)
            }
        }

        val jvmMain by getting {
            dependencies {
                // Core module dependencies
                api(projects.modules.openidFederationCorePublic)
                api(projects.modules.openidFederationCommon)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(sphereonlib.io.mockk.mockk)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Services Implementation")
                description.set("Service implementations for OpenID Federation")
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
