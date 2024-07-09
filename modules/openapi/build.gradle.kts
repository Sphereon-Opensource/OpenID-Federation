plugins {
    kotlin("jvm") version "2.0.0"
    id("org.openapi.generator") version "6.2.1"
}

group = "com.sphereon.oid.fed"
version = "1.0-SNAPSHOT"

project.extra.set("openApiPackage", "com.sphereon.oid.fed.openapi")

val profiles = project.properties["profiles"]?.toString()?.split(",") ?: emptyList()
val isModelsOnlyProfile = profiles.contains("models-only")

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

openApiGenerate {
    val openApiPackage: String by project
    generatorName.set("kotlin")
    packageName.set("com.sphereon.oid.fed.openapi")
    apiPackage.set("$openApiPackage.api")
    modelPackage.set("$openApiPackage.models")
    inputSpec.set("$rootDir/src/main/kotlin/com/sphereon/oid/fed/openapi/openapi.yaml")
    library.set("jvm-okhttp4")
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "jackson"
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

tasks.jar {
    dependsOn(tasks.openApiGenerate)
    archiveBaseName.set(project.name)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

sourceSets {
    main {
        java.srcDirs("build/generated/sources/openapi/src/main/kotlin")
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
