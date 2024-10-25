plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("app.cash.sqldelight") version "2.0.2"
}

group = "com.sphereon.oid.fed.kms.local"
version = "0.1.0"

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
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.core)
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

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
