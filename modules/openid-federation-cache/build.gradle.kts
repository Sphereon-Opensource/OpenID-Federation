plugins {
    alias(sureplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sureplug.plugins.org.jetbrains.kotlin.plugin.serialization)
}



kotlin {
    jvm()

    js(IR) {
        nodejs {
            useCommonJs()
            testTask {
                /*useMocha {
                    timeout = "5000"
                }*/
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-cache"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Cache Module"
            customField("description", "OpenID Federation Cache Module")
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
                implementation(surelib.org.jetbrains.kotlin.stdlib)
                implementation(surelib.org.jetbrains.kotlinx.coroutines.core)
                implementation(surelib.org.jetbrains.kotlinx.datetime)
                implementation(surelib.com.mayakapps.kache.kache)
                implementation(surelib.com.mayakapps.kache.file.kache)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(surelib.org.jetbrains.kotlinx.coroutines.test)
                implementation(surelib.org.jetbrains.kotlin.stdlib)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
