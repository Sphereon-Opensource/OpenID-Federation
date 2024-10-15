plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("app.cash.sqldelight") version "2.0.2"
}

group = "com.sphereon.oid.fed.persistence"
version = "0.1.0"

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
                implementation("app.cash.sqldelight:jdbc-driver:2.0.2")
                implementation("com.zaxxer:HikariCP:5.1.0")
                implementation("org.postgresql:postgresql:42.7.3")
            }
        }
    }
}
