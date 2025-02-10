import java.io.ByteArrayOutputStream

tasks.register("installGitHooks", Copy::class) {
    group = "git hooks"
    description = "Installs git hooks from .githooks directory"

    val sourceDir = file(".githooks")
    val targetDir = file(".git/hooks")

    from(sourceDir) {
        include("**/*")
    }
    into(targetDir)
    fileMode = 0b111101101 // 755 in octal: rwxr-xr-x

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
    version = "0.4.14-SNAPSHOT"
    val npmVersion by extra { getNpmVersion() }

    // Common repository configuration for all projects
    repositories {
        mavenCentral()
        mavenLocal()
        google()
    }
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

tasks.register("checkDockerStatus") {
    group = "docker"
    description = "Checks if Docker containers are running"
    doLast {
        val output = ByteArrayOutputStream()
        val process = exec {
            commandLine("docker", "compose", "ps", "-q", "db", "local-kms-db")
            isIgnoreExitValue = true
            standardOutput = output
        }

        val containersRunning = process.exitValue == 0 && output.toString().trim().isNotEmpty()
        project.ext.set("containersRunning", containersRunning)

        if (containersRunning) {
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
        exec {
            commandLine("docker", "compose", "rm", "-fsv", "db", "local-kms-db")
        }
    }
}

fun waitForDatabase() {
    var ready = false
    var attempts = 0
    val maxAttempts = 30

    while (!ready && attempts < maxAttempts) {
        try {
            val process = exec {
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

tasks.register("waitForDatabase") {
    group = "docker"
    description = "Waits for the database to be ready"
    doLast {
        waitForDatabase()
    }
}

tasks.register("dockerStart") {
    group = "docker"
    description = "Starts specific Docker containers"
    doLast {
        exec {
            commandLine("docker", "compose", "up", "-d", "db", "local-kms-db")
        }
        waitForDatabase()
    }
}

tasks.register("dockerEnsureRunning") {
    group = "docker"
    description = "Ensures Docker containers are running, starting them if needed"
    dependsOn("checkDockerStatus")

    doLast {
        if (!project.ext.has("containersRunning") || !project.ext.get("containersRunning").toString().toBoolean()) {
            exec {
                commandLine("docker", "compose", "rm", "-fsv", "db", "local-kms-db")
            }
            exec {
                commandLine("docker", "compose", "up", "-d", "db", "local-kms-db")
            }
        }
        waitForDatabase()
        project.ext.set("containersRunning", true)
    }
}

subprojects {
    tasks.matching { it.name == "build" }.configureEach {
        dependsOn(rootProject.tasks.named("dockerEnsureRunning"))

        doFirst {
            if (!rootProject.ext.has("containersRunning") || !rootProject.ext.get("containersRunning").toString()
                    .toBoolean()
            ) {
                throw GradleException("Docker containers are not running. Please run './gradlew dockerEnsureRunning' first.")
            }
        }
    }
}