import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("multiplatform") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("org.openapi.generator") version "7.7.0"
    id("maven-publish")
}

group = "com.sphereon.oid.fed"
version = "0.1.0-SNAPSHOT"

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
                    "kotlinx.serialization.json.JsonObject")
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
                        "dateLibrary" to "string"
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

    js {
        tasks {
            named("compileKotlinJs") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
            named("jsSourcesJar") {
                dependsOn("fixOpenApiGeneratorIssue")
            }
        }
        nodejs()
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

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            from(components["kotlin"])
        }
    }
}
