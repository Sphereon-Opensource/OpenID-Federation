plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.springboot) apply false
    alias(libs.plugins.springDependencyManagement) apply false
    alias(libs.plugins.kotlinPluginSpring) apply false
    id("maven-publish")
    id("com.github.node-gradle.node") version "7.0.1"
}

fun getNpmVersion(): String {
    val baseVersion = project.version.toString()
    if (!baseVersion.endsWith("-SNAPSHOT")) {
        return baseVersion
    }

    // For SNAPSHOT versions, create an unstable.<commit-hash> version
    val versionBase = baseVersion.removeSuffix("-SNAPSHOT")

    // Get git commit hash
    val gitCommitHash = try {
        val process = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        process.inputStream.bufferedReader().use { it.readLine() }
    } catch (e: Exception) {
        "unknown"
    }

    return "$versionBase-unstable.$gitCommitHash"
}

allprojects {
    group = "com.sphereon.oid.fed"
    version = "0.1.0-SNAPSHOT"
    val npmVersion by extra { getNpmVersion() }

    // Common repository configuration for all projects
    repositories {
        mavenCentral()
        mavenLocal()
        google()
    }
}

node {
    download.set(true) 
    version.set("20.12.2") 
    npmVersion.set("10.6.0")
}

tasks.register("publishNpmPackages") {
    group = "publishing"
    description = "Publishes all NPM packages"

    // Ensure JS compilation happens first
    dependsOn(":modules:openapi:compileKotlinJs")
    dependsOn(":modules:openid-federation-common:compileKotlinJs")
    dependsOn(":modules:openapi:jsPublicPackageJson")
    dependsOn(":modules:openid-federation-common:jsPublicPackageJson")

    doLast {
        // Publish openapi module
        exec {
            workingDir("modules/openapi/build/js/packages/openapi")
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                commandLine("cmd", "/c", "npm publish --access public")
            } else {
                commandLine("sh", "-c", "npm publish --access public")
            }
            environment("NPM_TOKEN", System.getenv("NPM_TOKEN"))
        }

        // Publish openid-federation-common module
        exec {
            workingDir("modules/openid-federation-common/build/js/packages/@sphereon/openid-federation-common")
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                commandLine("cmd", "/c", "npm publish --access public")
            } else {
                commandLine("sh", "-c", "npm publish --access public")
            }
            environment("NPM_TOKEN", System.getenv("NPM_TOKEN"))
        }
    }
}

tasks.register("createNpmConfig") {
    group = "publishing"
    description = "Creates .npmrc files for authentication"

    // Ensure JS compilation and package.json creation happens first
    dependsOn(":modules:openapi:compileKotlinJs")
    dependsOn(":modules:openid-federation-common:compileKotlinJs")
    dependsOn(":modules:openapi:jsPublicPackageJson")
    dependsOn(":modules:openid-federation-common:jsPublicPackageJson")

    doLast {
        val npmrcContent = "//registry.npmjs.org/:_authToken=\${NPM_TOKEN}\n" +
                "registry=https://registry.npmjs.org/\n" +
                "always-auth=true"

        // Create directories if they don't exist
        file("modules/openapi/build/js/packages/openapi").mkdirs()
        file("modules/openid-federation-common/build/js/packages/@sphereon/openid-federation-common").mkdirs()

        file("modules/openapi/build/js/packages/openapi/.npmrc")
            .writeText(npmrcContent)

        file("modules/openid-federation-common/build/js/packages/@sphereon/openid-federation-common/.npmrc")
            .writeText(npmrcContent)
    }
}

tasks.named("publishNpmPackages") {
    dependsOn("createNpmConfig")
}

subprojects {
    plugins.withType<MavenPublishPlugin> {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "sphereon-opensource"
                    val snapshotsUrl = "https://nexus.sphereon.com/repository/sphereon-opensource-snapshots/"
                    val releasesUrl = "https://nexus.sphereon.com/repository/sphereon-opensource-releases/"
                    url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl)
                    credentials {
                        username = System.getenv("NEXUS_USERNAME")
                        password = System.getenv("NEXUS_PASSWORD")
                    }
                }
            }
        }
    }
}
