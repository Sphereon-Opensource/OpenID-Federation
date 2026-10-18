import com.sphereon.gradle.plugin.configureJsTargetIfEnabled
import com.sphereon.gradle.plugin.configureWasmJsTargetIfEnabled
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    id("maven-publish")
}
kotlin {
    jvm()
    configureJsTargetIfEnabled { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    configureWasmJsTargetIfEnabled { nodejs() }
    sourceSets {
        commonMain.dependencies {
                api(projects.modules.openidFederationCorePublic)
                api(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
        }
    }
}
