import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
}



kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-http-resolver"
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
        }
        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-http-resolver"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation HTTP Resolver Module"
            customField("description", "OpenID Federation HTTP Resolver Module")
            customField("license", "Apache-2.0")
            customField("author", "Sphereon International")
            customField("type", "module")
            customField(
                "repository", mapOf(
                    "type" to "git",
                    "url" to "https://github.com/Sphereon-Opensource/openid-federation"
                )
            )

            customField(
                "publishConfig", mapOf(
                    "access" to "public"
                )
            )


        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.io.ktor.client.core)
                // IDK-compatible caching infrastructure
                api(projects.modules.openidFederationCorePublic)
                api(projects.modules.openidFederationCoreImpl)
                // IDK logging API
                api(idklib.sphereon.idk.lib.core.api.public)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(sphereonlib.io.ktor.client.mock)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(sphereonlib.io.ktor.client.cio)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.1")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
