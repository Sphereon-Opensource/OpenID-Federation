plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.springboot)
    alias(libs.plugins.kotlinPluginSpring)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.springDependencyManagement)
    id("maven-publish")
    application
}

repositories {
    mavenCentral()
    mavenLocal()

    maven {
        url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
    }
    maven {
        url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
    }
    maven {
        url = uri("https://jitpack.io")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(projects.modules.openidFederationOpenapi)
    api(projects.modules.openidFederationCommon)
    api(projects.modules.openidFederationPersistence)
    api(projects.modules.openidFederationServices)
    api(projects.modules.openidFederationLogger)
    implementation(libs.sphereon.kmp.cbor)
    implementation(libs.sphereon.kmp.crypto)
    implementation(libs.sphereon.kmp.crypto.kms)
    implementation(libs.sphereon.kmp.crypto.kms.ecdsa)
    implementation(libs.whyoleg.cryptography.core)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.springboot.actuator)
    implementation(libs.springboot.web)
    implementation(libs.springboot.data.jdbc)
    implementation(libs.springboot.security)
    implementation(libs.springboot.oauth2.resource.server)
    implementation(libs.kotlin.reflect)
    testImplementation(libs.springboot.test)
    testImplementation(libs.testcontainer.junit)
    testImplementation(libs.springboot.testcontainer)
    runtimeOnly(libs.springboot.devtools)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        setExceptionFormat("full")
        events("started", "skipped", "passed", "failed")
        showStandardStreams = true
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            artifact(tasks.named("bootJar"))

            pom {
                name.set("OpenID Federation Server")
                description.set("Server for OpenID Federation")
                url.set("https://github.com/Sphereon-Opensource/OpenID-Federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}
