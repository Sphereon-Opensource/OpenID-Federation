plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm)
    id("maven-publish")
    application
}

// Backwards compatibility shim module
// Re-exports both API and Ktor modules for consumers of the original module
dependencies {
    // Re-export both modules for backwards compatibility
    api(projects.modules.openidFederationPublicServerApi)
    api(projects.modules.openidFederationPublicServerKtor)
}

application {
    mainClass.set("com.sphereon.openid.fed.server.federation.ktor.ApplicationKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("OpenID Federation Server")
                description.set("Server for OpenID Federation (backwards compatibility shim)")
                url.set("https://github.com/Sphereon-Opensource/OpenID-Federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
