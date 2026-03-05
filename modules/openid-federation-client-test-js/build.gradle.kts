plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    alias(libs.plugins.node.gradle)
}

// KSP processors are JVM artifacts — ensure repositories are available for resolution
repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots/") }
    maven { url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases/") }
    maven { url = uri("https://jitpack.io") }
    mavenLocal { content { includeGroupAndSubgroups("com.sphereon") } }
}

kotlin {
    js {
        outputModuleName = "@sphereon/openid-federation-client-test-js"
        nodejs {
            binaries.library()
            generateTypeScriptDefinitions()
        }
        useEsModules()
        compilations["main"].packageJson {
            name = "@sphereon/openid-federation-client-test-js"
            version = rootProject.extra["npmVersion"] as String
            description = "OpenID Federation Client JS Test Module"
            customField("type", "module")
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
        }

        val jsMain by getting {
            dependencies {
                api(projects.modules.openidFederationClientImpl)
                implementation(idklib.sphereon.idk.lib.core.api.default)
                implementation(idklib.sphereon.idk.lib.data.link.http.client.impl)
                implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.public)
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public)
                implementation(sphereonlib.software.amazon.app.platform.di.common.public)
                implementation(sphereonlib.software.amazon.app.platform.scope.public)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime.optional)
                implementation(sphereonlib.me.tatarka.inject.kotlin.inject.runtime.kmp)
                implementation(sphereonlib.io.ktor.client.js)
                implementation(sphereonlib.io.ktor.client.core)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core.js)
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test.js)
                implementation(sphereonlib.io.ktor.client.mock.js)
            }
        }
    }
}

ksp {
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

fun DependencyHandlerScope.addKspDependencies(configName: String) {
    addProvider(configName, sphereonlib.me.tatarka.inject.kotlin.inject.compiler.ksp)
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public.get())
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.impl.code.generators.get())
    add(configName, sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.compiler.get())
}

dependencies {
    addKspDependencies("kspJs")
}

kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
}

kotlin.sourceSets.named("jsTest") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
    kotlin.srcDir("build/generated/ksp/js/jsTest/kotlin")
}

// node-gradle configuration for TypeScript tests
node {
    download.set(true)
    version.set("20.11.0")
    nodeProjectDir.set(file("src/jsTest/resources/ts"))
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("npmInstallTs") {
    dependsOn(tasks.named("npmSetup"))
    args.set(listOf("install"))
    workingDir.set(file("src/jsTest/resources/ts"))
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("tsTest") {
    dependsOn(tasks.named("npmInstallTs"))
    dependsOn(tasks.named("jsNodeProductionLibraryDistribution"))
    args.set(listOf("test"))
    workingDir.set(file("src/jsTest/resources/ts"))
    // Kotlin/JS npm dependencies (e.g. @js-joda/core) are installed in build/js/node_modules
    // by the Gradle kotlinNpmInstall task. Set NODE_PATH so Node.js can resolve them
    // when importing the compiled .mjs library files.
    environment.put("NODE_PATH", rootProject.layout.buildDirectory.dir("js/node_modules").get().asFile.absolutePath)
    // kotlinx-io uses eval('require')('os') internally which fails in ESM mode.
    // Preload the CJS shim that patches globalThis.require.
    // See: https://github.com/Kotlin/kotlinx-io/issues/345
    val shimFile = rootProject.file("gradle-build-support/js/esm-require-shim.cjs")
    if (shimFile.exists()) {
        environment.put("NODE_OPTIONS", "--require ${shimFile.absolutePath}")
    }
}
