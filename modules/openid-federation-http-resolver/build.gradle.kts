plugins {
    alias(sureplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sureplug.plugins.org.jetbrains.kotlin.plugin.serialization)
}



kotlin {
    jvm()

    js(IR) {
        /* browser {
             useEsModules()
             commonWebpackConfig {
                 devServer = KotlinWebpackConfig.DevServer().apply {
                     port = 8083
                 }
             }
         }*/
        nodejs {
            useEsModules()
            testTask {
                /*useMocha {
                    timeout = "5000"
                }*/
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
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

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(surelib.org.jetbrains.kotlinx.datetime)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.core)
                implementation(surelib.org.jetbrains.kotlinx.serialization.json)
                implementation(surelib.io.ktor.client.core)
                api(projects.modules.openidFederationCache)
                api(projects.modules.openidFederationLogger)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(surelib.org.jetbrains.kotlinx.coroutines.test)
                implementation(surelib.io.ktor.client.mock)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
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
