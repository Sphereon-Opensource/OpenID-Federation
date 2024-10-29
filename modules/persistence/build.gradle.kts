plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("app.cash.sqldelight") version "2.0.2"
    id("maven-publish")
}

group = "com.sphereon.oid.fed.persistence"

repositories {
    google()
    mavenCentral()
    mavenLocal()
}

sqldelight {
    databases {
        create("Database") {
            packageName = "com.sphereon.oid.fed.persistence"
            dialect("app.cash.sqldelight:postgresql-dialect:2.0.2")
            schemaOutputDirectory = file("src/commonMain/resources/db/migration")
            migrationOutputDirectory = file("src/commonMain/resources/db/migration")
            deriveSchemaFromMigrations = true
            migrationOutputFileFormat = ".sql"
            srcDirs.from(
                "src/commonMain/sqldelight"
            )
        }
    }
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.modules.openapi)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.sqldelight.jdbc.driver)
                implementation(libs.hikari)
                implementation(libs.postgresql)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            
            pom {
                name.set("OpenID Federation Persistence")
                description.set("Persistence module for OpenID Federation")
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
