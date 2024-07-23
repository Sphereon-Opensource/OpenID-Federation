import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("multiplatform") version "2.0.0"
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
        withType<KotlinCompileCommon> {
           dependsOn("openApiGenerate")
        }
        named("sourcesJar") {
            dependsOn("openApiGenerate")
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

            named<KotlinJvmCompile>("compileKotlinJvm") {
                dependsOn("openApiGenerate")
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }

            named("jvmSourcesJar") {
                dependsOn("openApiGenerate")
            }

            named<Jar>("jvmJar") {
                dependsOn("compileKotlinJvm")
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
                dependsOn("openApiGenerate")
            }
            named("jsSourcesJar") {
                dependsOn("openApiGenerate")
            }
        }
        nodejs()
    }

    iosX64 {
        tasks {
            named("compileKotlinIosX64") {
                dependsOn("openApiGenerate")
            }
            named("iosX64SourcesJar") {
                dependsOn("openApiGenerate")
            }
        }
    }
    iosArm64 {
        tasks {
            named("compileKotlinIosArm64") {
                dependsOn("openApiGenerate")
            }
            named("iosArm64SourcesJar") {
                dependsOn("openApiGenerate")
            }
        }
    }
    iosSimulatorArm64 {
        tasks {
            named("compileKotlinIosSimulatorArm64") {
                dependsOn("openApiGenerate")
            }
            named("iosSimulatorArm64SourcesJar") {
                dependsOn("openApiGenerate")
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/generated/src/commonMain/kotlin")
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
