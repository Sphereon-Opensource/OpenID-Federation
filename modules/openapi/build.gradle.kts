plugins {
    kotlin("jvm") version "2.0.0"
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

dependencies {
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.0")
}

openApiGenerate {
    val openApiPackage: String by project
    generatorName.set("kotlin")
    packageName.set("com.sphereon.oid.fed.openapi")
    apiPackage.set("$openApiPackage.api")
    modelPackage.set("$openApiPackage.models")
    inputSpec.set("$projectDir/src/main/kotlin/com/sphereon/oid/fed/openapi/openapi.yaml")
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


publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            from(components["kotlin"])
        }
    }
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

tasks.jar {
    dependsOn(tasks.compileKotlin)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set(project.name)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from("$projectDir/build/classes/kotlin/main")
}

kotlin {
    sourceSets.main {
        kotlin.srcDirs(
            "$projectDir/build/generated/src/commonMain/kotlin"
        )
    }
    jvmToolchain(21)
}
