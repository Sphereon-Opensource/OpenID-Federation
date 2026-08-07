plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    id("maven-publish")
}


kotlin {
    // Align with admin-server-ktor (JVM 21) so PlatformInProcessFixture can depend on it.
    jvm {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }
    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationClient)
                // IDK logging API
                api(idklib.sphereon.idk.lib.core.api.public)
                api(projects.modules.openidFederationOpenapi)
                api(projects.modules.openidFederationPersistence)
                api(projects.modules.openidFederationCommon)
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
                implementation(sphereonlib.io.ktor.client.cio)
                // IDK crypto libraries
                implementation(idklib.sphereon.idk.lib.crypto.core.public)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.azure)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.aws)
                implementation(sphereonlib.dev.whyoleg.cryptography.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(idklib.sphereon.idk.lib.core.api.public)
                implementation(projects.modules.openidFederationOpenapi)
                implementation(projects.modules.openidFederationPersistence)
                implementation(projects.modules.openidFederationCommon)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(sphereonlib.io.mockk.mockk)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5")) // Use the JUnit 5 variant
                implementation("org.junit.jupiter:junit-jupiter-engine:5.10.2") // Use an appropriate version
                // PLATFORM e2e: tenant claim helpers + OpenAPI models + in-process admin
                implementation(projects.modules.openidFederationCorePublic)
                implementation(projects.modules.openidFederationOpenapi)
                // Full admin Ktor graph for PlatformInProcessFixture (AS + admin same JVM)
                implementation(projects.modules.openidFederationAdminServerKtor)
                // Metro graph supertypes for AdminServerAppGraph (JWT auth extension)
                implementation("com.sphereon.idk:ktor-server-jwt-auth:0.25.0-SNAPSHOT")
                implementation(sphereonlib.io.ktor.client.cio)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                // Real IDK OAuth2 / OIDC Authorization Server (in-process issuer — not Keycloak).
                // Source: VDX-infra vdx/edk/idk/services/oauth2-as (services-oauth2-as-rest).
                // Local:  cd …/vdx/edk/idk && ./gradlew :services-oauth2-as-rest:publishToMavenLocal
                // CI:     same artifact from snapshot repo (project.publication plugin).
                implementation("com.sphereon.idk:services-oauth2-as-rest:0.25.0-SNAPSHOT")
                // Compile-time APIs used by IdkOauth2AsFixture (CreateAccessToken*, bootstrap, Ktor).
                // Runtime graph also pulls these transitively from services-oauth2-as-rest.
                implementation("com.sphereon.idk:lib-oauth2-server-authorization-public:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-oauth2-server-authorization-impl:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-oauth2-common-public:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-oauth2-common-impl:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-conf-yaml:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-core-events-impl:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-oauth2-jwt-validation-impl:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-oauth2-server-resource-impl:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:ktor-server-kotlin-inject:0.25.0-SNAPSHOT")
                implementation("com.sphereon.idk:lib-crypto-kms-provider-software:0.25.0-SNAPSHOT")
                implementation(sphereonlib.io.ktor.server.cio)
                implementation(sphereonlib.io.ktor.server.core)
                implementation(sphereonlib.io.ktor.server.content.negotiation)
                implementation(sphereonlib.io.ktor.server.status.pages)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Services")
                description.set("Services module for OpenID Federation")
                url.set("https://github.com/Sphereon-Opensource/openid-federation")
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

val integrationTests by tasks.registering {
    group = "verification"
    description = "Runs the integration tests by triggering the jvmTest task."
}

/**
 * PLATFORM e2e: in-process IDK AS + in-process admin (see PlatformInProcessFixture).
 * Postgres required; no standalone PLATFORM server.
 * See docs/PLATFORM_E2E.md.
 */
val platformIntegrationTests by tasks.registering {
    group = "verification"
    description =
        "PLATFORM e2e (in-process AS + admin). Prefer --tests …platform.* on jvmTest."
}

/** Always-on smoke: boot in-process IDK AS, discovery, CreateAccessTokenCommand mint. */
val platformAsSmokeTests by tasks.registering {
    group = "verification"
    description = "Enables jvmTest for IdkOauth2AsFixtureTest (pass --tests on jvmTest)."
}

tasks.named<Test>("jvmTest") {
    description = "Runs integration tests for the JVM target."
    group = "verification"

    useJUnitPlatform()

    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Gate heavy integration suite behind explicit verification umbrella tasks.
    onlyIf {
        val graph = gradle.taskGraph
        graph.hasTask(integrationTests.get()) ||
            graph.hasTask(platformIntegrationTests.get()) ||
            graph.hasTask(platformAsSmokeTests.get())
    }
}

listOf(integrationTests, platformIntegrationTests, platformAsSmokeTests).forEach { umbrella ->
    umbrella.configure { finalizedBy(tasks.named("jvmTest")) }
}
