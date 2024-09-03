plugins {
    kotlin("jvm") version "2.0.0"
}

group = "com.sphereon.oid.fed.kms.amazon"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    api(projects.modules.openapi)
    implementation(platform("software.amazon.awssdk:bom:2.21.1"))
    implementation("software.amazon.awssdk:kms")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}