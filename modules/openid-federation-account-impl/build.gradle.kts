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
                api(projects.modules.openidFederationAccountPublic)

                // Core module for config interfaces
                api(projects.modules.openidFederationCorePublic)

                // Common utilities
                api(projects.modules.openidFederationCommon)

                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)

                // kotlin-inject for DI
                implementation(sphereonlib.me.tatarka.inject.kotlin.inject.runtime.kmp)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime.optional)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
            }
        }

        val jvmMain by getting {
            dependencies {
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
    add("kspJvm", sphereonlib.me.tatarka.inject.kotlin.inject.compiler.ksp)
    add("kspJvm", sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public)
    add("kspJvm", sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.impl.code.generators)
    add("kspJvm", sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.compiler)
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Account Implementation")
                description.set("Account service implementations for OpenID Federation")
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
