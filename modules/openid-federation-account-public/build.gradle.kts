plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    id("maven-publish")
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Core module with FederationResult, error types, and TenantServiceConfig
                api(projects.modules.openidFederationCorePublic)

                // OpenAPI models (Account, CreateAccount)
                api(projects.modules.openidFederationOpenapi)

                // Persistence for query types
                api(projects.modules.openidFederationPersistence)

                // IDK core API for ServiceCommand, HttpEndpointCommand
                api(idklib.sphereon.idk.lib.core.api.public)

                // Standard library
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
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
                name.set("OpenID Federation Account Public")
                description.set("Account service interfaces for OpenID Federation")
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
