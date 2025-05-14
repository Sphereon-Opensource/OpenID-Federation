import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(sureplug.plugins.org.jetbrains.kotlin.multiplatform)
    id("maven-publish")
}

kotlin {
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("com.sphereon.oid.fed.conformance.SetupConformanceTest")
            }
        }
    }


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
                implementation(projects.modules.openidFederationLogger)
                implementation(projects.modules.openidFederationOpenapi)
                implementation(projects.modules.openidFederationPersistence)
                implementation(projects.modules.openidFederationCommon)
                implementation(surelib.io.ktor.client.content.negotiation)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.test)
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
