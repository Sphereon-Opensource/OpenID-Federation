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
                // Core module with FederationResult and error types
                api(projects.modules.openidFederationCorePublic)

                // OpenAPI models
                api(projects.modules.openidFederationOpenapi)

                // Persistence for query types (needed by LogService interface)
                api(projects.modules.openidFederationPersistence)

                // IDK logging API (for LogLevel in LogService)
                api(idklib.sphereon.idk.lib.core.api.public)

                // IDK crypto libraries (for mappers)
                implementation(idklib.sphereon.idk.lib.crypto.core.public)
                implementation(idklib.sphereon.idk.lib.crypto.core.impl)

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
                name.set("OpenID Federation Services Public")
                description.set("Service interfaces for OpenID Federation")
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
