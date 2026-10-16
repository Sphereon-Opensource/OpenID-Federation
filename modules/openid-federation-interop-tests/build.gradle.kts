plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("com.nimbusds:nimbus-jose-jwt:10.0.2")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.80")
}

val interopNetworkSubnet = providers.gradleProperty("interop.network.subnet").orNull

tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    interopNetworkSubnet?.let { systemProperty("interop.network.subnet", it) }
}

val interopLane = providers.gradleProperty("interop.lane").orElse("unit").get()
val allowedInteropLanes = setOf("unit", "peer", "core")
if (interopLane !in allowedInteropLanes) {
    throw GradleException("Invalid -Pinterop.lane='$interopLane'; expected one of: ${allowedInteropLanes.joinToString()}")
}

val adminServerProject = rootProject.findProject(":modules:openid-federation-admin-server-ktor")
val publicServerProject = rootProject.findProject(":modules:openid-federation-public-server-ktor")

fun Test.selectInteropLane(lane: String) {
    useJUnitPlatform {
        when (lane) {
            "unit" -> excludeTags("peer-qualification", "core", "oidc", "profile")
            "peer" -> includeTags("peer-qualification")
            "core" -> includeTags("core")
        }
    }
    filter { isFailOnNoMatchingTests = true }
}

fun Test.configureCoreRuntime(adminServer: org.gradle.api.Project, publicServer: org.gradle.api.Project) {
    dependsOn(
        ":modules:openid-federation-admin-server-ktor:installDist",
        ":modules:openid-federation-public-server-ktor:installDist",
    )
    systemProperty(
        "interop.admin.distribution",
        adminServer.layout.buildDirectory.dir("install/${adminServer.name}").get().asFile.absolutePath,
    )
    systemProperty(
        "interop.public.distribution",
        publicServer.layout.buildDirectory.dir("install/${publicServer.name}").get().asFile.absolutePath,
    )
    val coreEvidenceDirectory = project.layout.buildDirectory.dir("interop/core")
    systemProperty("interop.work.directory", project.layout.buildDirectory.dir("interop/work").get().asFile.absolutePath)
    systemProperty("interop.evidence.directory", coreEvidenceDirectory.get().asFile.absolutePath)
    outputs.dir(coreEvidenceDirectory)
}

fun Test.configurePeerEvidence() {
    systemProperty("interop.evidence.directory", project.layout.buildDirectory.dir("interop/peer-qualification").get().asFile.absolutePath)
}

tasks.named<Test>("test") {
    selectInteropLane(interopLane)
    if (interopLane == "peer") {
        configurePeerEvidence()
    }
    if (interopLane == "core") {
        val adminServer = adminServerProject
            ?: throw GradleException("The core interop lane requires :modules:openid-federation-admin-server-ktor in the settings graph; do not use -PopenidFederation.modules")
        val publicServer = publicServerProject
            ?: throw GradleException("The core interop lane requires :modules:openid-federation-public-server-ktor in the settings graph; do not use -PopenidFederation.modules")
        configureCoreRuntime(adminServer, publicServer)
    }
}

val peerQualificationTest = tasks.register<Test>("interopPeerQualificationTest") {
    group = "verification"
    description = "Qualifies the pinned independent peer; does not claim mixed-implementation interoperability."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("peer-qualification") }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    filter { isFailOnNoMatchingTests = true }
    configurePeerEvidence()
}

// The convention plugin's enterpriseArchitectureReport realizes task registrations
// while inspecting a selected-module build. Register the real packaged core lane only
// when its two production server projects are included in the settings graph; unit and
// peer-only selections intentionally include this harness module alone.
if (adminServerProject != null && publicServerProject != null) {
    tasks.register<Test>("interopCoreTest") {
        group = "verification"
        description = "Runs real packaged Sphereon and pinned LightHouse trust-chain interoperability scenarios."
        configureCoreRuntime(adminServerProject, publicServerProject)
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("core") }
        filter { isFailOnNoMatchingTests = true }
    }
}
