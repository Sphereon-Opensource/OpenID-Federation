plugins {
    kotlin("multiplatform") version "2.0.0"
    id("app.cash.sqldelight") version "2.0.2"
    id("maven-publish")
}

group = "com.sphereon.oid.fed.kms.local"

repositories {
    mavenCentral()
    mavenLocal()
    google()
}

sqldelight {
    databases {
        create("Database") {
            packageName = "com.sphereon.oid.fed.kms.local"
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
                api(projects.modules.openapi)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1")
            }
        }

        jvmMain {
            dependencies {
                implementation("app.cash.sqldelight:jdbc-driver:2.0.2")
                implementation("com.zaxxer:HikariCP:5.1.0")
                implementation("org.postgresql:postgresql:42.7.3")
                implementation("com.nimbusds:nimbus-jose-jwt:9.40")
            }
        }

//        jsMain {
//            dependencies {
//                implementation(npm("typescript", "5.5.3"))
//                implementation(npm("jose", "5.6.3"))
//                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
//            }
//        }

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            
            pom {
                name.set("OpenID Federation Local KMS")
                description.set("Local Key Management System for OpenID Federation")
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
