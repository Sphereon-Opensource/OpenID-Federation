plugins {
    kotlin("multiplatform") version "2.0.0"
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
                implementation(projects.modules.services)
            }
        }

        jvmMain {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            }
        }
    }
}