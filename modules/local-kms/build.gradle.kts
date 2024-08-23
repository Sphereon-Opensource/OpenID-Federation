plugins {
    kotlin("jvm") version "2.0.0"
}

group = "com.sphereon.oid.fed.kms.local"
version = "0.1.0"

repositories {
    mavenCentral()
    mavenLocal()
    google()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(projects.modules.services)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}