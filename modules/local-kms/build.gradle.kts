plugins {
    alias(libs.plugins.springboot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kotlinJvm)
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
    implementation(libs.springboot.data.jdbc)
    testImplementation(libs.springboot.test)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}