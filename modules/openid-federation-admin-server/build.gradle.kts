plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm)
    id("maven-publish")
    application
}

// Keep static public OpenAPI in sync with openapi module sources (core + LEGACY accounts).
val copyOpenAPI = tasks.register<Sync>("copyOpenAPI") {
    from("../openid-federation-openapi/src/commonMain/kotlin/com/sphereon/openid/fed/openapi") {
        include("admin-server.yaml", "admin-accounts.yaml")
    }
    into(layout.buildDirectory.dir("generated/openapi"))
}

// The checked-in copies are documentation snapshots, not generated task outputs.
// Package the current specs once, preserving their public/* resource paths.
sourceSets.named("main") {
    resources.exclude("public/admin-server.yaml", "public/admin-accounts.yaml")
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    from(copyOpenAPI) {
        into("public")
    }
}

// Backwards compatibility shim module
// Re-exports both API and Ktor modules for consumers of the original module
dependencies {
    // Re-export both modules for backwards compatibility
    api(projects.modules.openidFederationAdminServerApi)
    api(projects.modules.openidFederationAdminServerKtor)
}

application {
    mainClass.set("com.sphereon.openid.fed.server.admin.ktor.KtorAdminServerKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("OpenID Federation Admin Server")
                description.set("Admin Server for OpenID Federation (backwards compatibility shim)")
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
