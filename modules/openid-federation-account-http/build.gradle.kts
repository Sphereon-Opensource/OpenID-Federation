plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("maven-publish")
}

/**
 * LEGACY account-management REST (`/accounts`) HTTP endpoint commands + DI contributions.
 *
 * ## Deployment contract (classpath only)
 * - **On classpath** → Metro contributes `/accounts` endpoints (still gated by
 *   `identity.mode=legacy` at runtime).
 * - **Off classpath** → empty contribution sets; no account REST in the admin adapter.
 *
 * Platform hosts simply do not depend on this module. There is no separate runtime mode.
 * Tenant resolvers stay in `account-impl` (always available when multi-tenancy is needed).
 */
kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.modules.openidFederationAccountPublic)
                api(projects.modules.openidFederationAccountImpl)
                api(projects.modules.openidFederationAdminServerApi)
                api(projects.modules.openidFederationCorePublic)
                api(projects.modules.openidFederationCommon)
                api(projects.modules.openidFederationOpenapi)
                api(idklib.sphereon.idk.lib.core.api.public)
                implementation(sphereonlib.org.jetbrains.kotlin.stdlib)
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.core)
                implementation(sphereonlib.org.jetbrains.kotlinx.serialization.json)
                implementation(sphereonlib.software.amazon.app.platform.di.common.public)
                implementation(sphereonlib.software.amazon.app.platform.scope.public)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            pom {
                name.set("OpenID Federation Account HTTP")
                description.set("LEGACY /accounts REST endpoint commands for OpenID Federation admin")
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
