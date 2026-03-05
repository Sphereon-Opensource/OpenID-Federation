import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
    id("maven-publish")
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    js {
        outputModuleName = "@sphereon/openid-federation-wallet-impl"
        nodejs {
            useEsModules()
            binaries.library()
            generateTypeScriptDefinitions()
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
        }

        val commonMain by getting {
            dependencies {
                // Wallet public interfaces
                api(projects.modules.openidFederationWalletPublic)

                // Federation client public (FederationClient interface)
                api(projects.modules.openidFederationClientPublic)

                // Core implementation for caching
                api(projects.modules.openidFederationCoreImpl)

                // IDK core and crypto
                api(idklib.sphereon.idk.lib.core.api.public)
                api(idklib.sphereon.idk.lib.crypto.core.public)

                // kotlin-inject for DI
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.public)
                implementation(sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public)
                implementation(sphereonlib.software.amazon.app.platform.di.common.public)
                implementation(sphereonlib.software.amazon.app.platform.scope.public)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime)
                implementation(sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.runtime.optional)
                implementation(sphereonlib.me.tatarka.inject.kotlin.inject.runtime.kmp)

                // Standard library
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.datetime)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(sphereonlib.org.jetbrains.kotlin.test.junit)
            }
        }
    }
}

ksp {
    // We are using the Amazon App Platform binding processor instead!
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

fun DependencyHandlerScope.addKspDependencies(configName: String) {
    addProvider(configName, sphereonlib.me.tatarka.inject.kotlin.inject.compiler.ksp)
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public.get())
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.impl.code.generators.get())
    add(configName, sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.compiler.get())
}

// KSP dependencies for kotlin-inject code generation
dependencies {
    addKspDependencies("kspJvm")
    addKspDependencies("kspJvmTest")
    addKspDependencies("kspJs")
    addKspDependencies("kspJsTest")
    addKspDependencies("kspWasmJs")
    addKspDependencies("kspWasmJsTest")
}

// Needed because KSP-generated code needs to be included in the source sets
kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
}

kotlin.sourceSets.named("jsTest") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
    kotlin.srcDir("build/generated/ksp/js/jsTest/kotlin")
}

kotlin.sourceSets.named("wasmJsMain") {
    kotlin.srcDir("build/generated/ksp/wasmJs/wasmJsMain/kotlin")
}

kotlin.sourceSets.named("wasmJsTest") {
    kotlin.srcDir("build/generated/ksp/wasmJs/wasmJsMain/kotlin")
    kotlin.srcDir("build/generated/ksp/wasmJs/wasmJsTest/kotlin")
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Wallet Implementation")
                description.set("Wallet architecture implementations for OpenID Federation")
                url.set("https://github.com/Sphereon-Opensource/openid-federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
