import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.org.openapi.generator)
    id("maven-publish")
    alias(sphereonplug.plugins.org.jetbrains.kotlin.npm.publish.org.jetbrains.kotlin.npm.publish.gradle.plugin)
}

val openApiSpecPath = "$projectDir/src/commonMain/kotlin/com/sphereon/openid/fed/openapi/admin-server.yaml"
val kotlinOutputDir = "$projectDir/build/generated"
val basePackage = "com.sphereon.openid.fed.openapi"
val kotlinApiPackage = "$basePackage.api"
val kotlinModelPackage = "$basePackage.models"

project.extra.set("openApiPackage", basePackage)

val profiles = project.properties["profiles"]?.toString()?.split(",") ?: emptyList()
val isModelsOnlyProfile = profiles.contains("models-only")

repositories {
    mavenLocal()
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
            "collectionType" to "list",
            "sourceFolder" to "src/commonMain/kotlin",
//            "serializationLibrary" to "kotlinx_serialization",
            "serializableModel" to "false"
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

// Java Spring generation removed - migrated to Ktor

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
                replacement = "$1\nimport com.sphereon.core.compat.JsExportCompat"
            )
        }

        filter { line: String ->
            line.replace(
                regex = Regex("(data class (\\w+).*)"),
                replacement = "@JsExportCompat\n$1"
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

    js {
        outputModuleName = "@sphereon/openid-federation-open-api"
        tasks.named("compileKotlinJs") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        tasks.named("jsSourcesJar") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
        }

        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-open-api"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation OpenAPI Library (Kotlin Multiplatform)"
            customField("description", "OpenID Federation OpenAPI Library")
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

    // iOS targets require macOS for compilation (Kotlin/Native toolchain)
    if (System.getProperty("os.name").lowercase().contains("mac")) {
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
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        tasks.named("compileKotlinWasmJs") {
            dependsOn("fixOpenApiKotlinIssues")
        }
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        val commonMain by getting {

            kotlin.srcDir("$projectDir/build/copy/src/commonMain/kotlin")
            dependencies {
                api(idklib.sphereon.idk.lib.core.api.public)
                implementation(sphereonlib.io.ktor.client.core)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
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

// Replace wasmJs npm-publish tasks: mainFile provider has no value on Kotlin 2.3.x wasmJs targets
afterEvaluate {
    listOf("assembleWasmJsPackage", "packWasmJsPackage", "publishWasmJsPackageToNpmjsRegistry").forEach { taskName ->
        try { tasks.replace(taskName) } catch (_: Exception) {}
    }
}

// Java Spring generation tasks removed - migrated to Ktor
