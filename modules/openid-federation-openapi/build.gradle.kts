import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.openapiGenerator)
    id("maven-publish")
    alias(libs.plugins.npmPublish)
}

val openApiSpecPath = "$projectDir/src/commonMain/kotlin/com/sphereon/oid/fed/openapi/admin-server.yaml"
val kotlinOutputDir = "$projectDir/build/generated"
val javaOutputDir = "$projectDir/build/generated-java"
val basePackage = "com.sphereon.oid.fed.openapi"
val kotlinApiPackage = "$basePackage.api"
val kotlinModelPackage = "$basePackage.models"
val javaPackage = "$basePackage.java"
val javaApiPackage = "$javaPackage.api"
val javaModelPackage = "$javaPackage.models"

project.extra.set("openApiPackage", basePackage)

val profiles = project.properties["profiles"]?.toString()?.split(",") ?: emptyList()
val isModelsOnlyProfile = profiles.contains("models-only")

repositories {
    mavenCentral()
}

tasks.register<GenerateTask>("openApiGenerateKotlin") {
    group = "openapi tools"
    description = "Generates Kotlin Multiplatform code from OpenAPI specification."
    generatorName.set("kotlin")
    inputSpec.set(openApiSpecPath)
    outputDir.set(kotlinOutputDir)
    packageName.set(basePackage)
    apiPackage.set(kotlinApiPackage)
    modelPackage.set(kotlinModelPackage)
    library.set("multiplatform")
    configOptions.set(
        mapOf(
            "dateLibrary" to "string",
            "collectionType" to "array",
            "sourceFolder" to "src/commonMain/kotlin"
        )
    )

    if (isModelsOnlyProfile) {
        globalProperties.set(
            mapOf(
                "models" to "",
                "supportingFiles" to ""
            )
        )
    } else {
        globalProperties.set(
            mapOf(
                "models" to "",
                "apis" to "",
                "supportingFiles" to ""
            )
        )
    }
}

tasks.register<GenerateTask>("openApiGenerateJavaSpring") {
    dependsOn(":modules:openid-federation-integration-tests:compileTestKotlinJvm")
    group = "openapi tools"
    description = "Generates Java Spring code with Jakarta validations from OpenAPI specification."
    generatorName.set("kotlin-spring")
    inputSpec.set(openApiSpecPath)
    outputDir.set(javaOutputDir)
    packageName.set(javaPackage)
    apiPackage.set(javaApiPackage)
    modelPackage.set(javaModelPackage)
    configOptions.set(
        mapOf(
            "delegatePattern" to "false",
            "interfaceOnly" to "false",
            "useSpringBoot3" to "true",
            "useJakartaEe" to "true",
            "useBeanValidation" to "true",
            "performBeanValidation" to "true",
            "dateLibrary" to "java8",
//            "serializationLibrary" to "jackson",
            "sourceFolder" to "src/main/java"
        )
    )

    if (isModelsOnlyProfile) {
        globalProperties.set(
            mapOf(
                "models" to "",
                "supportingFiles" to ""
            )
        )
    } else {
        globalProperties.set(
            mapOf(
                "models" to "",
                "apis" to ""

            )
        )
    }
}

kotlin {
    tasks.register<Copy>("fixOpenApiKotlinIssues") {
        dependsOn("openApiGenerateKotlin")
        from("$kotlinOutputDir/src/commonMain/kotlin/$basePackage".replace('.', '/'))
        into("$projectDir/build/copy/src/commonMain/kotlin/$basePackage".replace('.', '/'))

        filter { line: String ->
            line.replace("io.ktor.util.InternalAPI", "io.ktor.utils.io.InternalAPI")
        }
        filter { line: String ->
            line.replace("kotlin.collections.Map<kotlin.String, kotlin.Any>", "kotlinx.serialization.json.JsonObject")
        }
        filter { line: String ->
            line.replace(
                regex = Regex("(@SerialName\\(value = \\\"(\\w+)\\\"\\))"),
                replacement = "@JsName(\"$2\") $1"
            )
        }
        filter { line: String ->
            line.replace(
                regex = Regex("(package com.*)"),
                replacement = "$1\nimport kotlin.js.ExperimentalJsExport\nimport kotlin.js.JsExport"
            )
        }

        filter { line: String ->
            line.replace(
                regex = Regex("(data class (\\w+).*)"),
                replacement = "@OptIn(ExperimentalJsExport::class)\n@JsExport\n$1"
            )
        }
        filter { line: String ->
            line.replace(
                regex = Regex("(import kotlinx\\.serialization\\.\\*)"),
                replacement = "$1 \nimport kotlin.js.JsName"
            )
        }
    }

    tasks.withType<KotlinCompileCommon> {
        dependsOn("fixOpenApiKotlinIssues")
    }
    tasks.named("sourcesJar") {
        dependsOn("fixOpenApiKotlinIssues")
    }

    jvm {

        tasks.named<KotlinJvmCompile>("compileKotlinJvm") {
            dependsOn("fixOpenApiKotlinIssues")
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }

        tasks.named("jvmSourcesJar") {
            dependsOn("fixOpenApiKotlinIssues")

        }

        tasks.named<Jar>("jvmJar") {
            dependsOn("fixOpenApiKotlinIssues")


            archiveBaseName.set("openapi")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from("$projectDir/build/classes/kotlin/jvm/main")
        }
    }

    js(IR) {
        tasks.named("compileKotlinJs") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        tasks.named("jsSourcesJar") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        binaries.library()
        generateTypeScriptDefinitions()
        nodejs {
            useEsModules()
        }
        browser {
            useEsModules()
        }

        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-open-api"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation OpenAPI Library (Kotlin Multiplatform)"
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
        tasks.named("compileKotlinIosX64") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        tasks.named("iosX64SourcesJar") {
            dependsOn("fixOpenApiKotlinIssues")
        }
    }
    iosArm64 {
        tasks.named("compileKotlinIosArm64") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        tasks.named("iosArm64SourcesJar") {
            dependsOn("fixOpenApiKotlinIssues")
        }
    }
    iosSimulatorArm64 {
        tasks.named("compileKotlinIosSimulatorArm64") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        tasks.named("iosSimulatorArm64SourcesJar") {
            dependsOn("fixOpenApiKotlinIssues")
        }
    }

    sourceSets {
        val commonMain by getting {

            kotlin.srcDir("$projectDir/build/copy/src/commonMain/kotlin")
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

tasks.register<Delete>("cleanGeneratedJava") {
    group = "build"
    delete(javaOutputDir)
}

tasks.named("clean") {
    dependsOn("cleanGeneratedJava")
}

tasks.register<Copy>("fixOpenApiJavaIssues") {
    dependsOn("openApiGenerateJavaSpring")
    from(javaOutputDir)
    into("$projectDir/build/fixed-java")


//    filter { line: String ->
//        line.replace(
//            "kotlin.collections.Map<kotlin.String, kotlin.Any>",
//            "kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonElement>",
//        )
//    }
}

tasks.register<Copy>("copyFixedJavaToOutput") {
    dependsOn("fixOpenApiJavaIssues")
    from("$projectDir/build/fixed-java")
    into(javaOutputDir)
}

// Ensure that openApiGenerateJavaSpring task includes our fixes
tasks.named("openApiGenerateJavaSpring").configure {
    finalizedBy("fixOpenApiJavaIssues", "copyFixedJavaToOutput")
}
