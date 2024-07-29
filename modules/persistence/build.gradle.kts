plugins {
    kotlin("multiplatform") version "2.0.0"
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
            dialect("app.cash.sqldelight:postgresql-dialect:2.0.2")
            packageName.set("com.sphereon.oid.fed.persistence")
            srcDirs.from(project.file("./src/commonMain/sqldelight/"))
        }
    }
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.modules.openapi)
                implementation("app.cash.sqldelight:jdbc-driver:2.0.2")
            }
        }

        jvmMain {
            dependencies {
                implementation("com.zaxxer:HikariCP:5.1.0")
                implementation("org.postgresql:postgresql:42.7.3")
            }
        }
    }
}
