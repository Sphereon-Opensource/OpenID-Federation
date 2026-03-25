plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("maven-publish")
    application
}

tasks.register<Copy>("copyOpenAPI") {
    from("../openid-federation-openapi/src/commonMain/kotlin/com/sphereon/openid/fed/openapi/admin-server.yaml")
    into("src/main/resources/public")
}

tasks.named("processResources") {
    dependsOn("copyOpenAPI")
}

dependencies {
    // API layer
    api(projects.modules.openidFederationAdminServerApi)

    // Service implementations (JVM only)
    api(projects.modules.openidFederationServices)
    api(projects.modules.openidFederationCoreImpl)
    api(projects.modules.openidFederationPersistence)
    api(projects.modules.openidFederationClient)
    api(projects.modules.openidFederationOpenapi)
    api(projects.modules.openidFederationCommon)

    // IDK logging API
    api(idklib.sphereon.idk.lib.core.api.public)
    api(idklib.sphereon.idk.lib.core.api.default)

    // Kotlin
    implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
    implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
    implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
    implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
    implementation(sphereonlib.org.jetbrains.kotlin.reflect)

    // Ktor Server
    implementation(sphereonlib.io.ktor.server.core)
    implementation(sphereonlib.io.ktor.server.cio)
    implementation(sphereonlib.io.ktor.server.content.negotiation)
    implementation(sphereonlib.io.ktor.server.cors)
    implementation(sphereonlib.io.ktor.server.status.pages)
    implementation(sphereonlib.io.ktor.server.call.logging)
    implementation(sphereonlib.io.ktor.server.auth)
    implementation(sphereonlib.io.ktor.server.auth.jwt)
    implementation(sphereonlib.io.ktor.serialization.kotlinx.json)

    // IDK crypto libraries
    implementation(idklib.sphereon.idk.lib.crypto.core.public)
    implementation(idklib.sphereon.idk.lib.crypto.core.impl)
    implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
    implementation(idklib.sphereon.idk.lib.crypto.kms.provider.aws)
    implementation(idklib.sphereon.idk.lib.crypto.kms.provider.azure)
    implementation(idklib.sphereon.idk.lib.data.link.http.client.public)
    implementation(idklib.sphereon.idk.lib.data.link.http.client.impl)

    // IDK Ktor server support
    implementation(idklib.sphereon.idk.ktor.server.kotlin.inject)

    // DI
    implementation(sphereonlib.software.amazon.app.platform.di.common.public)
    implementation(sphereonlib.software.amazon.app.platform.scope.public)

    // Cryptography
    implementation(sphereonlib.dev.whyoleg.cryptography.core)

    // Database
    runtimeOnly(sphereonlib.org.postgresql.postgresql)

    // Testing
    testImplementation(sphereonlib.org.jetbrains.kotlin.test)
    testImplementation(sphereonlib.io.ktor.server.test.host)
    testImplementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    mainClass.set("com.sphereon.openid.fed.server.admin.ktor.KtorAdminServerKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        setExceptionFormat("full")
        events("started", "skipped", "passed", "failed")
        showStandardStreams = true
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("OpenID Federation Admin Server Ktor")
                description.set("Ktor-based Admin Server for OpenID Federation")
                url.set("https://github.com/Sphereon-Opensource/OpenID-Federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
