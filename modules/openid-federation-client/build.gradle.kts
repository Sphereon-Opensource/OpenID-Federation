plugins {
    alias(sureplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sureplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sureplug.plugins.dev.petuska.npm.publish.dev.petuska.npm.publish.gradle.plugin)
    id("maven-publish")
    alias(libs.plugins.kover)
}


kotlin {
    jvm()

    js(IR) {
        /*browser {
            commonWebpackConfig {
                devServer = KotlinWebpackConfig.DevServer().apply {
                    port = 8083
                }
            }
            useEsModules()
        }*/
        nodejs {
            testTask {
                /*     useMocha {
                         timeout = "5000"
                     }*/
            }
            useEsModules()
        }
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-client"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Client Library"
            customField("description", "OpenID Federation Client Library")
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

    sourceSets {
        all {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
        }

        val commonMain by getting {
            dependencies {
                implementation(surelib.com.mayakapps.kache.kache)
                implementation(surelib.com.mayakapps.kache.file.kache)
                api(projects.modules.openidFederationCache)
                api(projects.modules.openidFederationHttpResolver)
                api(projects.modules.openidFederationOpenapi)
                api(projects.modules.openidFederationLogger)
                implementation(surelib.io.ktor.client.core)
                implementation(surelib.io.ktor.client.logging)
                implementation(surelib.io.ktor.client.content.negotiation)
                implementation(surelib.io.ktor.client.auth)
                implementation(surelib.io.ktor.serialization.kotlinx.json)
                implementation(surelib.org.jetbrains.kotlinx.serialization.json)
                implementation(surelib.org.jetbrains.kotlinx.serialization.core)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.core)
                implementation(surelib.org.jetbrains.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(surelib.io.ktor.client.mock)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.test)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
                implementation(libs.nimbus.jose.jwt)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
                implementation(libs.kotlinx.coroutines.core.js)
                implementation(npm("jose", "5.9.4"))
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                implementation(libs.kotlinx.coroutines.test.js)
                implementation(libs.ktor.client.mock.js)
            }
        }
    }
}

npmPublish {
    registries {
        register("npmjs") {
            uri.set("https://registry.npmjs.org")
            authToken.set(System.getenv("NPM_TOKEN") ?: "")
        }
    }
    packages {
        named("js") {
            packageJson {
                "name" by "@sphereon/openid-federation-client"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-client")
        }
    }
}
