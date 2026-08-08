plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("maven-publish")
    application
}

dependencies {
    // API layer
    api(projects.modules.openidFederationPublicServerApi)

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
    // IDK YAML config (APP/tenant/principal) — Metro contributions on classpath
    api("com.sphereon.idk:lib-conf-yaml:0.25.0-SNAPSHOT")

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

    // IDK Ktor server support + JWT auth (PLATFORM)
    // Metro aggregates @ContributesBinding from the compile classpath: jwt-validation-impl
    // needs client + resource-server command impls (they are only runtime-transitive in the POM).
    implementation(idklib.sphereon.idk.ktor.server.kotlin.inject)
    implementation("com.sphereon.idk:ktor-server-jwt-auth:0.25.0-SNAPSHOT")
    implementation("com.sphereon.idk:lib-oauth2-jwt-validation-api:0.25.0-SNAPSHOT")
    implementation("com.sphereon.idk:lib-oauth2-jwt-validation-impl:0.25.0-SNAPSHOT")
    implementation("com.sphereon.idk:lib-oauth2-common-public:0.25.0-SNAPSHOT")
    implementation("com.sphereon.idk:lib-oauth2-client-public:0.25.0-SNAPSHOT")
    implementation("com.sphereon.idk:lib-oauth2-client-impl:0.25.0-SNAPSHOT")
    implementation("com.sphereon.idk:lib-oauth2-server-resource-public:0.25.0-SNAPSHOT")
    implementation("com.sphereon.idk:lib-oauth2-server-resource-impl:0.25.0-SNAPSHOT")

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
    mainClass.set("com.sphereon.openid.fed.server.federation.ktor.ApplicationKt")
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
                name.set("OpenID Federation Public Server Ktor")
                description.set("Ktor-based Federation Server for OpenID Federation")
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
