import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

// Gradle 9 requires ExecOperations to be injected rather than using Project.exec()
abstract class ExecService @Inject constructor(val exec: ExecOperations)
val execService = objects.newInstance(ExecService::class.java)

tasks.register("installGitHooks", Copy::class) {
    group = "git hooks"
    description = "Installs git hooks from .githooks directory"

    val sourceDir = file(".githooks")
    val targetDir = file(".git/hooks")

    from(sourceDir) {
        include("**/*")
    }
    into(targetDir)
    filePermissions {
        unix("rwxr-xr-x")
    }

    inputs.dir(sourceDir)
    outputs.dir(targetDir)

    doFirst {
        sourceDir.mkdirs()
        targetDir.mkdirs()

        val preCommitFile = sourceDir.resolve("pre-commit")
        if (!preCommitFile.exists()) {
            throw GradleException("pre-commit hook file not found in .githooks directory")
        }

        println("Installing Git hooks...")
    }

    outputs.upToDateWhen {
        val preCommitSource = sourceDir.resolve("pre-commit")
        val preCommitTarget = targetDir.resolve("pre-commit")

        if (!preCommitTarget.exists()) {
            return@upToDateWhen false
        }

        val isUpToDate = preCommitSource.lastModified() <= preCommitTarget.lastModified() &&
                preCommitSource.length() == preCommitTarget.length()

        isUpToDate
    }
}

tasks.matching { it.name == "build" }.configureEach {
    dependsOn("installGitHooks")
}

gradle.projectsEvaluated {
    tasks.named("prepareKotlinBuildScriptModel").configure {
        dependsOn("installGitHooks")
    }
}


plugins {
    alias(sphereonplug.plugins.com.android.library) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm) apply false
    alias(sphereonplug.plugins.com.vanniktech.maven.publish) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization) apply false
    alias(sphereonplug.plugins.io.kotest.io.kotest.gradle.plugin) apply false
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.android) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.npm.publish.org.jetbrains.kotlin.npm.publish.gradle.plugin) apply false
    alias(sphereonplug.plugins.com.sphereon.gradle.plugin.conventions) apply false
    alias(sphereonplug.plugins.com.sphereon.gradle.plugin.integration.tests) apply false
    alias(sphereonplug.plugins.com.sphereon.gradle.plugin.project.publication) apply false

    // TODO update to sphereonplugs
    alias(sphereonplug.plugins.com.android.application) apply false
    alias(sphereonplug.plugins.org.jetbrains.compose) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.compose) apply false
    // Spring Boot plugins removed - migrated to Ktor
    alias(libs.plugins.node.gradle) apply false
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
    group = "com.sphereon.openid.fed"
    version = "0.25.4-SNAPSHOT"
    val npmVersion by extra { getNpmVersion() }

    configurations {
        all {
            exclude(group = "org.slf4j", module = "slf4j-simple")
        }
    }
}

subprojects {
    apply(plugin = "com.sphereon.gradle.plugin.conventions")

    // JS/WasmJS compiler flags — aligned with IDK ConventionsPlugin
    // Note: -Xenable-suspend-function-exporting, -Xes-long-as-bigint, and -XXLanguage:+JsAllowLongInExportedDeclarations
    // are already set by the ConventionsPlugin on Kotlin2JsCompile tasks.
    // moduleKind and target must be set per-module in js { compilerOptions { } }
    // because the KMP plugin overrides convention plugin settings at a later lifecycle point.

    // TODO: Move to publication plugin once ready
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

            // Ensure unique coordinates for different publication types
            publications.withType<MavenPublication> {
                val publicationName = name
                if (publicationName == "kotlinMultiplatform") {
                    artifactId = "${project.name}-multiplatform"
                } else if (publicationName == "mavenKotlin") {
                    artifactId = "${project.name}-jvm"
                }
            }
        }
    }
}

tasks.register("checkDockerStatusDb") {
    group = "docker"
    description = "Checks if Docker containers are running"
    doLast {
        val output = ByteArrayOutputStream()
        val process = execService.exec.exec {
            commandLine("docker", "compose", "ps", "-q", "db")
            isIgnoreExitValue = true
            standardOutput = output
        }

        val databaseRunning = process.exitValue == 0 && output.toString().trim().isNotEmpty()
        project.ext.set("databaseRunning", databaseRunning)

        if (databaseRunning) {
            println("Required Docker containers are already running")
        } else {
            println("Required Docker containers are not running")
        }
    }
}

tasks.register("dockerCleanup") {
    group = "docker"
    description = "Stops and removes specific Docker containers"
    doLast {
        execService.exec.exec {
            commandLine(
                "docker",
                "compose",
                "rm",
                "-fsv",
                "db",
                "openid-federation-admin-server",
                "openid-federation-server",
                "keycloak"
            )
        }
    }
}

tasks.register("dockerStartAdminServer") {
    group = "docker"
    description = "Starts specific Docker containers"

    dependsOn(rootProject.tasks.named("dockerStartDb"))

    doFirst {
        execService.exec.exec {
            commandLine(
                "docker",
                "compose",
                "up",
                "-d",
                "openid-federation-admin-server"
            )
        }
        waitForAdminServer()
    }
}

tasks.register("dockerStartDb") {
    group = "docker"
    description = "Ensures Docker databases are running, starting them if needed"

    doLast {
        if (!project.ext.has("databaseRunning") || !project.ext.get("databaseRunning").toString().toBoolean()) {
            execService.exec.exec {
                commandLine("docker", "compose", "up", "-d", "db")
            }
        }
        waitForDatabase()
        project.ext.set("databaseRunning", true)
    }
}

tasks.matching { it.name == "build" }.configureEach {
    dependsOn(rootProject.tasks.named("dockerStartDb"))

    doFirst {
        if (!rootProject.ext.has("databaseRunning") || !rootProject.ext.get("databaseRunning").toString()
                .toBoolean()
        ) {
            throw GradleException("Docker databases are not running. Please run './gradlew dockerStartDb' first.")
        }
    }
}

fun waitForDatabase() {
    var ready = false
    var attempts = 0
    val maxAttempts = 30

    while (!ready && attempts < maxAttempts) {
        try {
            val process = execService.exec.exec {
                commandLine("docker", "compose", "exec", "-T", "db", "pg_isready", "-U", "postgres")
                isIgnoreExitValue = true
            }
            ready = process.exitValue == 0
        } catch (e: Exception) {
        }

        if (!ready) {
            attempts++
            Thread.sleep(2000)
            println("Waiting for database to be ready... (Attempt $attempts/$maxAttempts)")
        }
    }

    if (!ready) {
        throw GradleException("Database failed to become ready within the timeout period")
    }

    println("Database is ready!")
}

fun waitForAdminServer(timeoutSeconds: Long = 90, pollIntervalMs: Long = 3000) {
    val statusUrl = URI.create("http://localhost:8081/status").toURL()
    val startTime = Instant.now()
    val endTime = startTime.plusSeconds(timeoutSeconds)
    var attempts = 0
    var serverReady = false

    println("Waiting for Admin Server at ${statusUrl}...")

    while (Instant.now().isBefore(endTime) && !serverReady) {
        attempts++
        try {
            val connection = statusUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                serverReady = true
                println("Admin Server is ready! (Attempt $attempts)")
            } else {
                println("Admin Server not ready yet (Attempt $attempts, Status: $responseCode). Waiting ${pollIntervalMs}ms...")
                Thread.sleep(pollIntervalMs)
            }
            connection.disconnect()
        } catch (e: Exception) {
            println("Admin Server not reachable yet (Attempt $attempts, Error: ${e.message}). Waiting ${pollIntervalMs}ms...")
            Thread.sleep(pollIntervalMs)
        }
    }

    if (!serverReady) {
        val duration = Duration.between(startTime, Instant.now()).seconds
        throw GradleException("Admin Server failed to become ready at $statusUrl within ${duration} seconds.")
    }
}
