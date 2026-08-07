plugins {
    `java-platform`
    `maven-publish`
}

// Include all sibling modules as BOM constraints (replaces gradle-bom-generator-plugin)
dependencies {
    constraints {
        rootProject.subprojects
            .filter { it != project }
            .forEach { subproject ->
                api(subproject)
            }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            from(components["javaPlatform"])
            artifactId = "openid-federation-bom"

            pom {
                name.set("OpenID Federation BOM")
                description.set("Bill of Materials (BoM) for my OpenID Federation libraries")
                url.set("https://github.com/Sphereon-Opensource/OpenID-Federation")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/Sphereon-Opensource/OpenID-Federation/issues")
                }

                developers {
                    developer {
                        id.set("Sphereon International BV")
                        email.set("support@sphereon.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com:Sphereon-Opensource/OpenID-Federation")
                    developerConnection.set("scm:git:ssh://github.com:Sphereon-Opensource/OpenID-Federation.git")
                    url.set("https://github.com/Sphereon-Opensource/OpenID-Federation")
                }
            }
        }
    }
}
