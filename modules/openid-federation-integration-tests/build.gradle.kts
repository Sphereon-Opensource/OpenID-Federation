plugins {
    alias(sureplug.plugins.org.jetbrains.kotlin.multiplatform)
    id("maven-publish")
}


kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationClient)
                api(projects.modules.openidFederationLogger)
                api(projects.modules.openidFederationOpenapi)
                api(projects.modules.openidFederationPersistence)
                api(projects.modules.openidFederationCommon)
                implementation(surelib.org.jetbrains.kotlin.stdlib)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.core)
                implementation(surelib.org.jetbrains.kotlinx.serialization.json)
                implementation(surelib.io.ktor.serialization.kotlinx.json)
                implementation(surelib.org.jetbrains.kotlinx.datetime)
                implementation(libs.ktor.client.cio)
                implementation(libs.sphereon.kmp.cbor)
                implementation(libs.sphereon.kmp.crypto)
                implementation(libs.sphereon.kmp.crypto.kms)
                implementation(libs.sphereon.kmp.crypto.kms.ecdsa)
                implementation(libs.sphereon.kmp.crypto.kms.azure)
                implementation(libs.sphereon.kmp.crypto.kms.aws)
                implementation(surelib.dev.whyoleg.cryptography.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(projects.modules.openidFederationLogger)
                implementation(projects.modules.openidFederationOpenapi)
                implementation(projects.modules.openidFederationPersistence)
                implementation(projects.modules.openidFederationCommon)
                implementation(surelib.io.ktor.client.content.negotiation)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.test)
                implementation(libs.mockk)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5")) // Use the JUnit 5 variant
                implementation("org.junit.jupiter:junit-jupiter-engine:5.10.2") // Use an appropriate version
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Services")
                description.set("Services module for OpenID Federation")
                url.set("https://github.com/Sphereon-Opensource/openid-federation")
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

tasks.withType<Test>().named("jvmTest") {
    description = "Runs integration tests for the JVM target."
    group = "verification"

    useJUnitPlatform()

    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    onlyIf { gradle.taskGraph.hasTask(tasks.named("integrationTests").get()) }
}

tasks.register("integrationTests") {
    group = "verification"
    description = "Runs the integration tests by triggering the jvmTest task."

    finalizedBy(tasks.named("jvmTest"))
}
