plugins {
    alias(libs.plugins.bom.generator)
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
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
                        id.set("4sure")
                        name.set("Niels Klomp")
                        email.set("nklomp@4sure.tech")
                    }

                    developer {
                        id.set("4sure")
                        name.set("John Melati")
                        email.set("jmelati@4sure.tech")
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
