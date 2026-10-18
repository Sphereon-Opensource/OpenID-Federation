import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

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
    testImplementation("com.microsoft.playwright:playwright:1.63.0")
}

val interopNetworkSubnet = providers.gradleProperty("interop.network.subnet").orNull

tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    interopNetworkSubnet?.let { systemProperty("interop.network.subnet", it) }
}

val interopLane = providers.gradleProperty("interop.lane").orElse("unit").get()
val allowedInteropLanes = setOf("unit", "peer", "core", "oidc", "profile", "ofcli")
if (interopLane !in allowedInteropLanes) {
    throw GradleException("Invalid -Pinterop.lane='$interopLane'; expected one of: ${allowedInteropLanes.joinToString()}")
}

val adminServerProject = rootProject.findProject(":modules:openid-federation-admin-server-ktor")
val publicServerProject = rootProject.findProject(":modules:openid-federation-public-server-ktor")
val oidcOpHostProject = rootProject.findProject(":modules:openid-federation-interop-oidc-host")
val oidcBrowserServerProject = rootProject.findProject(":modules:openid-federation-interop-browser-server")
val oidcInteropTestClass = "com.sphereon.openid.fed.interop.oidc.OffaOidcInteropTest"
val profileInteropTestClass = "com.sphereon.openid.fed.interop.core.ResearchEducationProfileInteropTest"
val ofcliInteropTestClass = "com.sphereon.openid.fed.interop.ofcli.OfcliStructuralInteropTest"

fun Test.selectInteropLane(lane: String) {
    useJUnitPlatform {
        when (lane) {
            "unit" -> excludeTags("peer-qualification", "core", "oidc", "profile", "ofcli")
            "peer" -> includeTags("peer-qualification")
            "core" -> includeTags("core")
            "oidc" -> includeTags("oidc")
            "profile" -> includeTags("profile")
            "ofcli" -> includeTags("ofcli")
        }
    }
    filter {
        if (lane == "oidc") includeTestsMatching(oidcInteropTestClass)
        if (lane == "profile") includeTestsMatching(profileInteropTestClass)
        if (lane == "ofcli") includeTestsMatching(ofcliInteropTestClass)
        isFailOnNoMatchingTests = true
    }
}

fun Test.configureCoreRuntime(
    adminServer: org.gradle.api.Project,
    publicServer: org.gradle.api.Project,
    evidenceLane: String = "core",
) {
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
    val evidenceDirectory = project.layout.buildDirectory.dir("interop/$evidenceLane")
    systemProperty("interop.work.directory", project.layout.buildDirectory.dir("interop/work").get().asFile.absolutePath)
    systemProperty("interop.evidence.directory", evidenceDirectory.get().asFile.absolutePath)
    outputs.dir(evidenceDirectory)
}

fun Test.configureOfcliEvidence() {
    val moduleDirectory = project.projectDir.toPath()
    val configuredBuildDirectory = project.layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
    val evidenceDirectory = project.layout.buildDirectory.dir("interop/ofcli").get().asFile.toPath().toAbsolutePath().normalize()
    systemProperty("interop.evidence.directory", evidenceDirectory.toString())
    systemProperty("interop.evidence.runId", UUID.randomUUID().toString())
    doFirst("Remove only stale allowlisted OFCLI evidence before the selected run") {
        val moduleRoot = moduleDirectory.toRealPath()
        val expectedBuild = moduleRoot.resolve("build")
        val configuredBuild = configuredBuildDirectory
        require(configuredBuild == expectedBuild) { "OFCLI evidence cleanup requires the canonical module build directory" }
        fun ensureSafeDirectory(path: Path) {
            require(!Files.isSymbolicLink(path)) { "Symlink in OFCLI evidence path" }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "OFCLI evidence path is not a directory" }
            require(path.toRealPath() == path.toAbsolutePath().normalize()) { "OFCLI evidence path escapes the canonical module build directory" }
        }
        ensureSafeDirectory(expectedBuild)
        val interopDirectory = expectedBuild.resolve("interop")
        ensureSafeDirectory(interopDirectory)
        val ofcliDirectory = interopDirectory.resolve("ofcli")
        ensureSafeDirectory(ofcliDirectory)
        require(evidenceDirectory == ofcliDirectory) { "OFCLI evidence must stay under build/interop/ofcli" }
        val allowedFixed = setOf("lighthouse-sphereon-two-level-metadata-policy.json")
        val allowedRun = Regex("ofcli-structural-[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.json")
        Files.newDirectoryStream(ofcliDirectory).use { children ->
            for (child in children) {
                require(!Files.isSymbolicLink(child)) { "Symlink in OFCLI evidence directory" }
                require(child.toRealPath() == child.toAbsolutePath().normalize()) { "Reparse or linked OFCLI evidence entry" }
                require(Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) { "Non-regular OFCLI evidence entry" }
                val name = child.fileName.toString()
                require(name in allowedFixed || allowedRun.matches(name)) { "Unexpected OFCLI evidence filename" }
                Files.delete(child)
            }
        }
    }
}


fun Test.configureOidcRuntime(host: org.gradle.api.Project, adminServer: org.gradle.api.Project, browserServer: org.gradle.api.Project) {
    val moduleDirectory = project.projectDir.toPath()
    val configuredBuildDirectory = project.layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
    dependsOn(
        ":modules:openid-federation-interop-oidc-host:installDist",
        ":modules:openid-federation-admin-server-ktor:installDist",
        ":modules:openid-federation-interop-browser-server:installDist",
    )
    systemProperty("interop.oidc.distribution", host.layout.buildDirectory.dir("install/oidf-op-host").get().asFile.absolutePath)
    systemProperty("interop.admin.distribution", adminServer.layout.buildDirectory.dir("install/${adminServer.name}").get().asFile.absolutePath)
    systemProperty("interop.browser.distribution", browserServer.layout.buildDirectory.dir("install/${browserServer.name}").get().asFile.absolutePath)
    systemProperty("interop.work.directory", project.layout.buildDirectory.dir("interop/work").get().asFile.absolutePath)
    val evidenceDirectoryProvider = project.layout.buildDirectory.dir("interop/oidc")
    val evidenceDirectory = evidenceDirectoryProvider.get().asFile.toPath().toAbsolutePath().normalize()
    systemProperty("interop.evidence.directory", evidenceDirectory.toString())
    outputs.dir(evidenceDirectoryProvider)
    doFirst("Remove stale OIDC result evidence immediately before the selected lane runs") {
        val moduleRoot = moduleDirectory.toRealPath()
        val expectedBuild = moduleRoot.resolve("build")
        val configuredBuild = configuredBuildDirectory
        require(configuredBuild == expectedBuild) { "OIDC evidence cleanup requires the canonical module build directory" }
        fun ensureSafeDirectory(path: Path) {
            require(!Files.isSymbolicLink(path)) { "Symlink in OIDC evidence path" }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "OIDC evidence path is not a directory" }
            require(path.toRealPath() == path.toAbsolutePath().normalize()) { "OIDC evidence path escapes the canonical module build directory" }
        }
        ensureSafeDirectory(expectedBuild)
        val interopDirectory = expectedBuild.resolve("interop")
        ensureSafeDirectory(interopDirectory)
        val oidcDirectory = interopDirectory.resolve("oidc")
        ensureSafeDirectory(oidcDirectory)
        require(evidenceDirectory == oidcDirectory) { "OIDC evidence must stay under build/interop/oidc" }
        val allowedFiles = setOf("offa-default.json", "offa-rs256.json", "offa-es256.json", "offa-es384.json", "offa-es512.json")
        Files.newDirectoryStream(oidcDirectory).use { children ->
            for (child in children) {
                require(!Files.isSymbolicLink(child)) { "Symlink in OIDC evidence directory" }
                require(child.toRealPath() == child.toAbsolutePath().normalize()) { "Reparse or linked OIDC evidence entry" }
                require(Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) { "Non-regular OIDC evidence entry" }
                if (child.fileName.toString().endsWith(".json")) {
                    require(child.fileName.toString() in allowedFiles) { "Unexpected OIDC evidence JSON filename" }
                    Files.delete(child)
                }
            }
        }
    }
}

fun Test.configurePeerEvidence() {
    val evidenceDirectory = project.layout.buildDirectory.dir("interop/peer-qualification")
    systemProperty("interop.evidence.directory", evidenceDirectory.get().asFile.absolutePath)
    outputs.dir(evidenceDirectory)
}

tasks.named<Test>("test") {
    selectInteropLane(interopLane)
    if (interopLane == "peer") {
        configurePeerEvidence()
    }
    if (interopLane == "core" || interopLane == "profile" || interopLane == "ofcli") {
        val adminServer = adminServerProject
            ?: throw GradleException("The $interopLane interop lane requires :modules:openid-federation-admin-server-ktor in the settings graph; do not use -PopenidFederation.modules")
        val publicServer = publicServerProject
            ?: throw GradleException("The $interopLane interop lane requires :modules:openid-federation-public-server-ktor in the settings graph; do not use -PopenidFederation.modules")
        val evidenceLane = when (interopLane) {
            "profile" -> "profile"
            "ofcli" -> "ofcli"
            else -> "core"
        }
        configureCoreRuntime(adminServer, publicServer, evidenceLane)
        if (interopLane == "ofcli") configureOfcliEvidence()
    }
    if (interopLane == "oidc") {
        val host = oidcOpHostProject
            ?: throw GradleException("The OIDC interop lane requires :modules:openid-federation-interop-oidc-host in the settings graph")
        val adminServer = adminServerProject
            ?: throw GradleException("The OIDC interop lane requires :modules:openid-federation-admin-server-ktor in the settings graph")
        val browserServer = oidcBrowserServerProject
            ?: throw GradleException("The OIDC interop lane requires :modules:openid-federation-interop-browser-server in the settings graph")
        configureOidcRuntime(host, adminServer, browserServer)
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
if (adminServerProject != null && publicServerProject != null) {
    tasks.register<Test>("interopOfcliTest") {
        group = "verification"
        description = "Runs the pinned OFCLI structural chain probe after independent Nimbus verification."
        configureCoreRuntime(adminServerProject, publicServerProject, "ofcli")
        configureOfcliEvidence()
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("ofcli") }
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        filter {
            includeTestsMatching(ofcliInteropTestClass)
            isFailOnNoMatchingTests = true
        }
    }
}

if (oidcOpHostProject != null && adminServerProject != null && oidcBrowserServerProject != null) {
    tasks.register<Test>("interopOidcTest") {
        group = "verification"
        description = "Runs the selected real OFFA OIDC browser interop flows."
        configureOidcRuntime(oidcOpHostProject, adminServerProject, oidcBrowserServerProject)
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("oidc") }
        filter {
            includeTestsMatching(oidcInteropTestClass)
            isFailOnNoMatchingTests = true
        }
    }
}

if (adminServerProject != null && publicServerProject != null) {
    tasks.register<Test>("interopProfileTest") {
        group = "verification"
        description = "Runs the separate pinned R&E pilot-draft profile assertion; not production-profile acceptance."
        configureCoreRuntime(adminServerProject, publicServerProject, "profile")
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("profile") }
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        filter {
            includeTestsMatching(profileInteropTestClass)
            isFailOnNoMatchingTests = true
        }
    }
}


// Runs the complete interop matrix as one serial coordinator-friendly invocation.
// The aggregate is registered only when every project needed by its lanes is present.
val aggregateInteropRequested = gradle.startParameter.taskNames.any { it == "interopTest" || it.endsWith(":interopTest") }
val aggregateMissingProjects = buildList {
    if (adminServerProject == null) add(":modules:openid-federation-admin-server-ktor")
    if (publicServerProject == null) add(":modules:openid-federation-public-server-ktor")
    if (oidcOpHostProject == null) add(":modules:openid-federation-interop-oidc-host")
    if (oidcBrowserServerProject == null) add(":modules:openid-federation-interop-browser-server")
}
if (aggregateInteropRequested && aggregateMissingProjects.isNotEmpty()) {
    throw GradleException(
        "interopTest requires the full project graph; missing: ${aggregateMissingProjects.joinToString()}. " +
            "Do not select a module-only graph for the aggregate lane."
    )
}
if (aggregateInteropRequested && interopLane != "unit") {
    throw GradleException("interopTest always runs its own unit task first; omit -Pinterop.lane or set it to unit")
}
if (aggregateMissingProjects.isEmpty()) {
    val aggregateUnit = tasks.named<Test>("test")
    val aggregateHostUnit = oidcOpHostProject!!.tasks.named<Test>("test")
    val aggregatePeer = peerQualificationTest
    val aggregateCore = tasks.named<Test>("interopCoreTest")
    val aggregateOidc = tasks.named<Test>("interopOidcTest")
    val aggregateProfile = tasks.named<Test>("interopProfileTest")
    val aggregateOfcli = tasks.named<Test>("interopOfcliTest")

    listOf(aggregateUnit, aggregateHostUnit, aggregatePeer, aggregateCore, aggregateOidc, aggregateProfile, aggregateOfcli)
        .forEach { provider -> provider.configure { maxParallelForks = 1 } }
    aggregateHostUnit.configure { mustRunAfter(aggregateUnit) }
    aggregatePeer.configure { mustRunAfter(aggregateHostUnit) }
    aggregateCore.configure { mustRunAfter(aggregatePeer) }
    aggregateOidc.configure { mustRunAfter(aggregateCore) }
    aggregateProfile.configure { mustRunAfter(aggregateOidc) }
    aggregateOfcli.configure { mustRunAfter(aggregateProfile) }

    tasks.register("interopTest") {
        group = "verification"
        description = "Runs unit, host-unit, peer, full core, OIDC, profile, and OFCLI interop lanes serially."
        dependsOn(aggregateUnit, aggregateHostUnit, aggregatePeer, aggregateCore, aggregateOidc, aggregateProfile, aggregateOfcli)
    }
}
