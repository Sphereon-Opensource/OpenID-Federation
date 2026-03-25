import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("maven-publish")
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-trust"
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
        }

        val commonMain by getting {
            dependencies {
                // OID-Fed client commands (ResolveTrustChain, VerifyTrustChain, VerifyTrustMark)
                api(projects.modules.openidFederationClientPublic)
                api(projects.modules.openidFederationCorePublic)

                // IDK trust framework
                api(idklib.sphereon.idk.lib.trust.core.public)
                implementation(idklib.sphereon.idk.lib.trust.core.impl)

                // IDK core
                api(idklib.sphereon.idk.lib.core.api.public)
                api(idklib.sphereon.idk.lib.core.compat)

                // IDK cache
                implementation(idklib.sphereon.idk.lib.core.api.public)

                // Metro DI
                implementation(sphereonlib.software.amazon.app.platform.di.common.public)
                implementation(sphereonlib.software.amazon.app.platform.scope.public)

                // Standard library
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test.junit)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Trust Bridge")
                description.set("Bridges OpenID Federation trust chain resolution to IDK Trust Validation framework")
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
