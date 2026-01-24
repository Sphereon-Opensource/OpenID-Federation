plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    id("maven-publish")
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Public interfaces
                api(projects.modules.openidFederationServicesPublic)

                // Core module for config interfaces
                api(projects.modules.openidFederationCorePublic)

                // Additional dependencies needed by implementations
                api(projects.modules.openidFederationClient)
                api(projects.modules.openidFederationCommon)

                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
                implementation(libs.ktor.client.cio)

                // IDK crypto libraries
                implementation(libs.idk.crypto.core.public)
                implementation(libs.idk.crypto.core.impl)
                implementation(libs.idk.crypto.kms.provider.software)
                implementation(libs.idk.crypto.kms.provider.azure)
                implementation(libs.idk.crypto.kms.provider.aws)
                implementation(sphereonlib.dev.whyoleg.cryptography.core)

                // kotlin-inject for DI
                implementation(libs.kotlin.inject.runtime)
                implementation(libs.anvil.runtime)
                implementation(libs.anvil.runtime.optional)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(libs.mockk)
            }
        }

        val jvmMain by getting {
            dependencies {
                // Core module dependencies (needed for KSP to resolve config types)
                api(projects.modules.openidFederationCorePublic)
                api(projects.modules.openidFederationCommon)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

ksp {
    // We are using the Amazon App Platform binding processor instead
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

dependencies {
    add("kspJvm", libs.kotlin.inject.compiler.ksp)
    add("kspJvm", libs.amz.kotlin.inject.contribute.public)
    add("kspJvm", libs.amz.kotlin.inject.contribute.code.generators)
    add("kspJvm", libs.anvil.compiler.ksp)
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Services Implementation")
                description.set("Service implementations for OpenID Federation")
                url.set("https://github.com/Sphereon-Opensource/openid-federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
