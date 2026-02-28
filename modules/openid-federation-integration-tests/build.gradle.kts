plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    id("maven-publish")
}


kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationClient)
                // IDK logging API
                api(idklib.sphereon.idk.lib.core.api.public)
                api(projects.modules.openidFederationOpenapi)
                api(projects.modules.openidFederationPersistence)
                api(projects.modules.openidFederationCommon)
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
                implementation(sphereonlib.io.ktor.client.cio)
                // IDK crypto libraries
                implementation(idklib.sphereon.idk.lib.crypto.core.public)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.azure)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.aws)
                implementation(sphereonlib.dev.whyoleg.cryptography.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(idklib.sphereon.idk.lib.core.api.public)
                implementation(projects.modules.openidFederationOpenapi)
                implementation(projects.modules.openidFederationPersistence)
                implementation(projects.modules.openidFederationCommon)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(sphereonlib.io.mockk.mockk)
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
