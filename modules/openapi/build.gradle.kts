import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.openapiGenerator)
    id("maven-publish")
    alias(libs.plugins.npmPublish)
}

project.extra.set("openApiPackage", "com.sphereon.oid.fed.openapi")

val profiles = project.properties["profiles"]?.toString()?.split(",") ?: emptyList()
val isModelsOnlyProfile = profiles.contains("models-only")

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
            filter { line: String ->
                line.replace(
                    regex = Regex("(@SerialName\\(value = \\\"(\\w+)\\\"\\))"),
                    replacement = "@JsName(\"$2\") $1"
                )
            }
            filter { line: String ->
                line.replace(
                    regex = Regex("(import kotlinx\\.serialization\\.\\*)"),
                    replacement = "$1 \nimport kotlin.js.JsName"
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
                inputSpec.set("$projectDir/src/commonMain/kotlin/com/sphereon/oid/fed/openapi/admin-server.yaml")
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
        generateTypeScriptDefinitions()
        nodejs()

        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-open-api"
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
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
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
                "name" by "@sphereon/openid-federation-open-api"
                "version" by rootProject.extra["npmVersion"] as String
            }
            scope.set("@sphereon")
            packageName.set("openid-federation-openapi")
        }
    }
}
