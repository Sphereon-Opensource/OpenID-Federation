plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    id("maven-publish")
    alias(libs.plugins.kover)
}

/**
 * This is a wrapper module that re-exports both the service interfaces (-public)
 * and service implementations (-impl) for backward compatibility.
 *
 * New code should depend on:
 * - openid-federation-services-public for interfaces only
 * - openid-federation-services-impl for implementations with DI
 */
kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Re-export both public interfaces and implementations
                api(projects.modules.openidFederationServicesPublic)
                api(projects.modules.openidFederationServicesImpl)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Services")
                description.set("Services module for OpenID Federation")
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
