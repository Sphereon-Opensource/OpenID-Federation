rootProject.name = "openid-federation"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // Prefer local Sphereon/IDK publishes over remote SNAPSHOTs (metadata must match Metro).
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroupAndSubgroups("software.amazon") }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases/")
            mavenContent { releasesOnly() }
        }
        maven {
            url = uri("https://jitpack.io")
        }
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    // Project-level repositories {} would replace these (Gradle PREFER_PROJECT default)
    // and silently drop Sphereon Nexus — that broke openapi CI for IDK SNAPSHOTs.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    versionCatalogs {
        create("sphereonplug") {
            from("com.sphereon.gradle:gradle-plugin-bom:0.10.0-SNAPSHOT@toml" as String)
        }
        create("sphereonlib") {
            from("com.sphereon.gradle:library-bom:0.10.0-SNAPSHOT@toml" as String)
        }
        create("idklib") {
            from("com.sphereon.idk:idk-bom:0.26.0-SNAPSHOT@toml" as String)
        }
        // TODO: Move aws sdk to our bom
        create("awssdk") {
            from("aws.sdk.kotlin:version-catalog:1.6.107" as String)
        }
    }
    repositories {
        // Prefer local Sphereon/IDK publishes over remote SNAPSHOTs (metadata must match Metro).
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Nexus repos also proxy/cache external deps (e.g. software.amazon) — no group filter
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
            mavenContent { releasesOnly() }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroupAndSubgroups("software.amazon") }
        }
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://jitpack.io")
        }
        gradlePluginPortal()

        // Kotlin JS/Wasm + node-gradle download tooling via Ivy (not Maven).
        // With PREFER_SETTINGS, project.repositories.ivy { } is ignored for resolution
        // (KT-55620), so every AbstractSetupTask distro must be listed here:
        // Node, Yarn, Binaryen, D8.
        ivy {
            name = "Node.js distributions"
            url = uri("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn distributions"
            url = uri("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy {
            name = "Binaryen distributions"
            url = uri("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
        ivy {
            name = "D8 distributions"
            url = uri("https://storage.googleapis.com/chromium-v8/official/canary")
            patternLayout {
                artifact("[artifact]-[revision].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("google.d8", "v8") }
        }
    }
}

// `-PopenidFederation.modules=<name>,<name>` builds only the named modules (for example a JVM service closure);
// absent means the full build.
val selectedFederationModules = providers.gradleProperty("openidFederation.modules").orNull?.let { raw ->
    require(Regex("[A-Za-z0-9][A-Za-z0-9-]*(,[A-Za-z0-9][A-Za-z0-9-]*)*").matches(raw)) {
        "openidFederation.modules must be a nonempty comma-separated list of exact module names"
    }
    val names = raw.split(',')
    require(names.size == names.toSet().size) { "openidFederation.modules contains duplicate module names" }
    names.toSet()
}
val includedFederationModules = mutableSetOf<String>()

fun org.gradle.api.initialization.Settings.includeFederationModule(path: String) {
    require(path.startsWith(":modules:") && path.count { it == ':' } == 2) {
        "Federation module path must be an exact :modules:<name> leaf"
    }
    val name = path.removePrefix(":modules:")
    if (selectedFederationModules == null || name in selectedFederationModules) {
        require(includedFederationModules.add(name)) { "Duplicate federation module declaration: $name" }
        this.include(path)
    }
}

// Core modules (IDK migration)
// NOTE: These modules should use IDK types directly, not duplicate them
includeFederationModule(":modules:openid-federation-core-public")
includeFederationModule(":modules:openid-federation-core-impl")

// Account modules (optional account-based multi-tenancy)
includeFederationModule(":modules:openid-federation-account-public")
includeFederationModule(":modules:openid-federation-account-impl")
// LEGACY /accounts REST only — optional dependency (on classpath = REST present)
includeFederationModule(":modules:openid-federation-account-http")

// Existing modules
includeFederationModule(":modules:openid-federation-openapi")
includeFederationModule(":modules:openid-federation-services")
includeFederationModule(":modules:openid-federation-services-public")
includeFederationModule(":modules:openid-federation-services-impl")
includeFederationModule(":modules:openid-federation-bom")
includeFederationModule(":modules:openid-federation-common")
includeFederationModule(":modules:openid-federation-client")
includeFederationModule(":modules:openid-federation-client-public")
includeFederationModule(":modules:openid-federation-client-impl")
// The JS test harness only exists in builds that enable the JS targets (kmp.targets), like the modules it consumes.
val jsTargetsEnabled = (System.getProperty("kmp.targets") ?: "jvm").split(',').map { it.trim().lowercase() }
    .let { "all" in it || "js" in it }
if (jsTargetsEnabled) includeFederationModule(":modules:openid-federation-client-test-js")

// Trust bridge module (bridges OID-Fed trust chain to IDK Trust Validation framework)
includeFederationModule(":modules:openid-federation-trust")

// Wallet architecture modules (OpenID Federation Wallet Architecture 1.0)
includeFederationModule(":modules:openid-federation-wallet-public")
includeFederationModule(":modules:openid-federation-wallet-impl")
includeFederationModule(":modules:openid-federation-http-resolver")
includeFederationModule(":modules:openid-federation-persistence")
includeFederationModule(":modules:openid-federation-integration-tests")
includeFederationModule(":modules:openid-federation-conformance-tests")

// Admin server modules (API layer is KMP, Ktor layer is JVM)
includeFederationModule(":modules:openid-federation-admin-server-api")
includeFederationModule(":modules:openid-federation-admin-server-ktor")
includeFederationModule(":modules:openid-federation-admin-server")  // Backwards compat shim

// Federation server modules (API layer is KMP, Ktor layer is JVM)
includeFederationModule(":modules:openid-federation-public-server-api")
includeFederationModule(":modules:openid-federation-public-server-ktor")
includeFederationModule(":modules:openid-federation-server")  // Backwards compat shim


if (selectedFederationModules != null) {
    require(includedFederationModules == selectedFederationModules) {
        "openidFederation.modules contains unknown module names: " + (selectedFederationModules - includedFederationModules)
    }
}
