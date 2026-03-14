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
        outputModuleName = "@sphereon/openid-federation-trust"
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
        }

        val commonMain by getting {
            dependencies {
                // OID-Fed client commands (ResolveTrustChain, VerifyTrustChain, VerifyTrustMark)
                api(projects.modules.openidFederationClientPublic)
                api(projects.modules.openidFederationCorePublic)

                // IDK trust framework
                api(idklib.sphereon.idk.lib.trust.core.public)
                implementation(idklib.sphereon.idk.lib.trust.core.impl)

                // IDK core
                api(idklib.sphereon.idk.lib.core.api.public)
                api(idklib.sphereon.idk.lib.core.compat)

                // IDK cache
                implementation(idklib.sphereon.idk.lib.core.api.public)

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
    arg("software.amazon.lastmile.kotlin.inject.anvil.processor.ContributesBindingProcessor", "disabled")
}

fun DependencyHandlerScope.addKspDependencies(configName: String) {
    addProvider(configName, sphereonlib.me.tatarka.inject.kotlin.inject.compiler.ksp)
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.public.get())
    add(configName, sphereonlib.software.amazon.app.platform.kotlin.inject.contribute.impl.code.generators.get())
    add(configName, sphereonlib.software.amazon.lastmile.kotlin.inject.anvil.compiler.get())
}

dependencies {
    addKspDependencies("kspJvm")
    addKspDependencies("kspJvmTest")
    addKspDependencies("kspJs")
    addKspDependencies("kspJsTest")
    addKspDependencies("kspWasmJs")
    addKspDependencies("kspWasmJsTest")
}

kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin")
}

kotlin.sourceSets.named("wasmJsMain") {
    kotlin.srcDir("build/generated/ksp/wasmJs/wasmJsMain/kotlin")
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Trust Bridge")
                description.set("Bridges OpenID Federation trust chain resolution to IDK Trust Validation framework")
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
