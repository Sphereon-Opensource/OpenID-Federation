import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    id("maven-publish")
}

kotlin {
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("com.sphereon.openid.fed.conformance.SetupConformanceTest")
            }
        }
    }


    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationClient)
                // IDK logging API
                api(libs.idk.core.api.public)
                api(projects.modules.openidFederationOpenapi)
                api(projects.modules.openidFederationPersistence)
                api(projects.modules.openidFederationCommon)
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
                implementation(libs.ktor.client.cio)
                // IDK crypto libraries
                implementation(libs.idk.crypto.core.public)
                implementation(libs.idk.crypto.kms.provider.software)
                implementation(libs.idk.crypto.kms.provider.azure)
                implementation(libs.idk.crypto.kms.provider.aws)
                implementation(sphereonlib.dev.whyoleg.cryptography.core)
                implementation(libs.idk.core.api.public)
                implementation(projects.modules.openidFederationOpenapi)
                implementation(projects.modules.openidFederationPersistence)
                implementation(projects.modules.openidFederationCommon)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(libs.mockk)
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
