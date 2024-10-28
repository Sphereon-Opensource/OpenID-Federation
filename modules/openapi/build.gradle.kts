import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("multiplatform") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("org.openapi.generator") version "7.7.0"
    id("maven-publish")
    id("dev.petuska.npm.publish") version "3.4.3"
}

project.extra.set("openApiPackage", "com.sphereon.oid.fed.openapi")

val profiles = project.properties["profiles"]?.toString()?.split(",") ?: emptyList()
val isModelsOnlyProfile = profiles.contains("models-only")
val ktorVersion = "2.3.11"

repositories {
    mavenCentral()
}

kotlin {
    tasks {
        // Temporary fix for this issue: https://github.com/OpenAPITools/openapi-generator/issues/17658
        register<Copy>("fixOpenApiGeneratorIssue") {
            from(
                "$projectDir/build/generated/src/commonMain/kotlin/com/sphereon/oid/fed/openapi"
            )
            into(
                "$projectDir/build/copy/src/commonMain/kotlin/com/sphereon/oid/fed/openapi"
            )
            filter { line: String ->
                line.replace(
                    "kotlin.collections.Map<kotlin.String, kotlin.Any>",
                    "kotlinx.serialization.json.JsonObject"
                )
            }
        }

        withType<KotlinCompileCommon> {
            dependsOn("fixOpenApiGeneratorIssue")
        }
        named("sourcesJar") {
            dependsOn("fixOpenApiGeneratorIssue")
        }
    }
    jvm {
        tasks {
            openApiGenerate {
                val openApiPackage: String by project
                generatorName.set("kotlin")
                packageName.set("com.sphereon.oid.fed.openapi")
                apiPackage.set("$openApiPackage.api")
                modelPackage.set("$openApiPackage.models")
                inputSpec.set("$projectDir/src/commonMain/kotlin/com/sphereon/oid/fed/openapi/openapi.yaml")
                library.set("multiplatform")
                outputDir.set("$projectDir/build/generated")
                configOptions.set(
                    mapOf(
                        "dateLibrary" to "string",
                        "collectionType" to "array",
                    )
                )

                if (isModelsOnlyProfile) {
                    globalProperties.set(
                        configOptions.get().plus(
                            mapOf(
                                "models" to ""
                            )
                        )
                    )
                }
            }

            named<Copy>("fixOpenApiGeneratorIssue") {
                dependsOn("openApiGenerate")
            }

            named<KotlinJvmCompile>("compileKotlinJvm") {
                dependsOn("fixOpenApiGeneratorIssue")
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }

            named("jvmSourcesJar") {
                dependsOn("fixOpenApiGeneratorIssue")
            }

            named<Jar>("jvmJar") {
                dependsOn("fixOpenApiGeneratorIssue")
                archiveBaseName.set("openapi")
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                from(configurations.kotlinCompilerClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
                from("$projectDir/build/classes/kotlin/jvm/main")
            }
        }
    }

    js(IR) {
        tasks {
            named("compileKotlinJs") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
            named("jsSourcesJar") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
        }
        binaries.library()
        nodejs()

        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-openapi"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation OpenAPI Library"
            customField("description", "OpenID Federation OpenAPI Library")
            customField("license", "Apache-2.0")
            customField("author", "Sphereon International")
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

            types = "./index.d.ts"
        }
    }

    iosX64 {
        tasks {
            named("compileKotlinIosX64") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
            named("iosX64SourcesJar") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
        }
    }
    iosArm64 {
        tasks {
            named("compileKotlinIosArm64") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
            named("iosArm64SourcesJar") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
        }
    }
    iosSimulatorArm64 {
        tasks {
            named("compileKotlinIosSimulatorArm64") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
            named("iosSimulatorArm64SourcesJar") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/copy/src/commonMain/kotlin")
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
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
    packages{
        named("js") {
            packageJson {
                "name" by "@sphereon/openid-federation-openapi"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-openapi")
        }
    }
}
