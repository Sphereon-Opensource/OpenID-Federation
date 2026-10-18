plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("maven-publish")
}
kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
                api(projects.modules.openidFederationAdminPublic)
                api(projects.modules.openidFederationServicesImpl)
                implementation(idklib.sphereon.idk.lib.crypto.core.public)
                implementation(idklib.sphereon.idk.lib.crypto.core.impl)
                api(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(sphereonlib.io.mockk.mockk)
            implementation(idklib.sphereon.idk.lib.crypto.kms.provider.software)
            implementation(kotlin("test-junit"))
            implementation(sphereonlib.app.cash.sqldelight.jdbc.driver)
            implementation(sphereonlib.com.zaxxer.hikaricp)
            implementation(sphereonlib.org.postgresql.postgresql)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
        }
    }
}
