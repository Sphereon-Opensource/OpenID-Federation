@file:OptIn(KspExperimental::class)

import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.google.devtools.ksp.KspExperimental

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    id("maven-publish")
    application
}

tasks.register<Copy>("copyOpenAPI") {
    from("../openid-federation-openapi/src/commonMain/kotlin/com/sphereon/openid/fed/openapi/admin-server.yaml")
    into("src/main/resources/public")
}

tasks.processResources.dependsOn(":modules:openid-federation-admin-server:copyOpenAPI")

// Note: We don't include the Java/Spring generated models anymore
// Ktor server uses Kotlin multiplatform models from openid-federation-openapi

dependencies {
    api(projects.modules.openidFederationOpenapi)
    api(projects.modules.openidFederationCommon)
    api(projects.modules.openidFederationPersistence)
    api(projects.modules.openidFederationServices)
    api(projects.modules.openidFederationClient)
    // IDK logging API
    api(libs.idk.core.api.public)
    // IDK-compatible caching infrastructure
    api(projects.modules.openidFederationCorePublic)
    api(projects.modules.openidFederationCoreImpl)

    // Kotlin
    implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
    implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
    implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
    implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
    implementation(sphereonlib.org.jetbrains.kotlin.reflect)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(sphereonlib.io.ktor.serialization.kotlinx.json)

    // IDK crypto libraries
    implementation(libs.idk.crypto.core.public)
    implementation(libs.idk.crypto.core.impl)
    implementation(libs.idk.crypto.kms.provider.software)
    implementation(libs.idk.crypto.kms.provider.aws)
    implementation(libs.idk.crypto.kms.provider.azure)
    implementation(libs.idk.core.api.public)
    implementation(libs.idk.core.api.default)
    implementation(libs.idk.data.link.http.client.public)
    implementation(libs.idk.data.link.http.client.impl)

    // IDK Ktor server support
    implementation(libs.idk.ktor.server.kotlin.inject)

    // kotlin-inject DI with Amazon App Platform / Anvil
    implementation(libs.bundles.kotlin.inject)

    // Cryptography
    implementation(sphereonlib.dev.whyoleg.cryptography.core)

    // Database
    runtimeOnly(libs.postgresql)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.amz.kotlin.inject.impl)
}

// KSP configuration for kotlin-inject with Anvil
ksp {
    useKsp2.set(true)
    // Use Amazon App Platform binding processor instead of Anvil's
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

// Configure KSP processors
dependencies {
    ksp(libs.kotlin.inject.compiler.ksp)
    ksp(libs.amz.kotlin.inject.contribute.public)
    ksp(libs.amz.kotlin.inject.contribute.code.generators)
    ksp(libs.anvil.compiler.ksp)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    mainClass.set("com.sphereon.openid.fed.server.admin.KtorAdminServerKt")
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
                name.set("OpenID Federation Admin Server")
                description.set("Admin Server for OpenID Federation")
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

// No longer depends on Java code generation since we use Kotlin multiplatform models
