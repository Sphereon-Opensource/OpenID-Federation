plugins {
    kotlin("jvm") version "2.0.0"
}

group = "com.sphereon.oid.fed"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    api(projects.modules.openapi)
    api(projects.modules.persistence)
    api(projects.modules.openidFederationCommon)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
