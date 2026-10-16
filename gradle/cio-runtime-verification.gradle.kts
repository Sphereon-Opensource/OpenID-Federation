import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

data class CioRuntimeArtifact(val coordinate: String, val path: String, val sha256: String)
data class CioRuntimeGraph(val components: List<String>, val unresolved: List<String>)

object CioConsumedInputs {
    private val propertyKeys = listOf("oidfedCioVersion", "oidfedCioExpectedGeneration", "oidfedCioExpectedJarSha256")
    private val hash = Regex("[a-f0-9]{64}")
    fun validate(record: Map<*, *>?, properties: Map<String, String>, policy: Map<*, *>?): Map<String, String>? {
        if (record == null && properties.isEmpty()) return null
        require(record != null && record.keys == setOf("schemaVersion", "version", "generation", "jvmJarSha256", "repositorySubsetSha256") &&
            record["schemaVersion"] == 1) { "Missing or invalid consumed CIO input record" }
        val expected = listOf("version", "generation", "jvmJarSha256").associateWith { key ->
            record[key] as? String ?: error("Malformed CIO input record")
        }
        require(expected.getValue("version") == CioRuntimeSelectionContract.VERSION &&
            hash.matches(expected.getValue("generation")) && hash.matches(expected.getValue("jvmJarSha256"))) { "Unbound accepted CIO generation/JAR" }
        require(properties == propertyKeys.zip(expected.values).toMap()) { "CIO record/request drift" }
        require(policy != null && record["repositorySubsetSha256"] == subsetSha256(policy, expected.getValue("generation"))) {
            "CIO record/routed policy drift"
        }
        return expected + ("repositorySubsetSha256" to record["repositorySubsetSha256"].toString())
    }
    fun subsetSha256(policy: Map<*, *>, generation: String): String {
        require(policy.keys == setOf("schemaVersion", "components", "metadata") && policy["schemaVersion"] == 2) { "Wrong normal routed policy" }
        val components = policy["components"] as? Map<*, *> ?: error("Missing routed components")
        val metadata = policy["metadata"] as? Map<*, *> ?: error("Missing routed metadata")
        val selected = sortedMapOf<String, String>()
        val pinned = sortedMapOf<String, String>()
        CioRuntimeSelectionContract.NAMES.sorted().forEach { name ->
            val version = CioRuntimeSelectionContract.VERSION
            val coordinate = "io.ktor:$name:$version"
            val origin = components[coordinate] as? String ?: error("Missing canonical CIO origin")
            require(File(origin).isAbsolute && '\\' !in origin &&
                origin.split('/').none { it == "." || it == ".." } &&
                origin.endsWith("/generations/$generation/repo")) { "Wrong routed CIO generation" }
            selected[coordinate] = origin
            listOf(".module", ".pom").forEach { suffix ->
                val path = "io/ktor/$name/$version/$name-$version$suffix"
                val digest = metadata[path] as? String ?: error("Missing CIO metadata pin")
                require(hash.matches(digest)) { "Malformed CIO metadata pin" }
                pinned[path] = digest
            }
        }
        require(selected.values.toSet().size == 1) { "Split CIO generation origins" }
        require(components.keys.filter { key ->
            val fields = key.toString().split(':')
            fields.size >= 2 && fields[0] == "io.ktor" && fields[1] in CioRuntimeSelectionContract.NAMES
        }.toSet() == selected.keys) { "Conflicting routed CIO owner" }
        val fields = mutableListOf("oidfed-cio-subset-v1", "components")
        selected.forEach { (key, value) -> fields.addAll(listOf(key, value)) }
        fields += "metadata"
        pinned.forEach { (key, value) -> fields.addAll(listOf(key, value)) }
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object CioRuntimeSelectionContract {
    const val VERSION = "3.5.0-sphereon-cio-01a13833-SNAPSHOT"
    val NAMES = setOf("ktor-server-cio", "ktor-server-cio-jvm")
    fun validate(
        components: List<String>,
        unresolved: List<String>,
        artifacts: List<CioRuntimeArtifact>,
        expectedVersion: String,
        expectedGeneration: String,
        expectedJarSha256: String,
    ) {
        require(expectedVersion == VERSION && Regex("[a-f0-9]{64}").matches(expectedGeneration) &&
            Regex("[a-f0-9]{64}").matches(expectedJarSha256)) { "Unbound accepted CIO generation/JAR" }
        require(unresolved.isEmpty()) { "Unresolved runtime dependencies: ${unresolved.joinToString()}" }
        var hasCio = false
        components.forEach { coordinate ->
            val parts = coordinate.split(':')
            require(parts.size == 3) { "Malformed selected coordinate: $coordinate" }
            if (parts[0] == "io.ktor") {
                if (parts[1] in NAMES) {
                    require(parts[2] == VERSION) { "Wrong selected CIO version: $coordinate" }
                    hasCio = true
                } else {
                    // Report an existing mixture for review; never upgrade the
                    // family or change a neighbor version from this verifier.
                    require(parts[2] == "3.5.0") {
                        "Unexpected selected Ktor neighbor version; review actual graph: $coordinate"
                    }
                }
            }
        }
        require(hasCio) { "No actual selected CIO component" }
        val cioArtifacts = artifacts.filter { artifact ->
            val parts = artifact.coordinate.split(':')
            require(parts.size == 3) { "Malformed consumed coordinate" }
            if (parts[0] == "io.ktor" && parts[1] !in NAMES) {
                require(parts[2] == "3.5.0") { "Unexpected consumed Ktor neighbor version" }
            }
            parts[0] == "io.ktor" && parts[1] in NAMES
        }
        require(cioArtifacts.size == 1 &&
            File(cioArtifacts.single().path).name == "ktor-server-cio-jvm-$VERSION.jar") {
            "Expected exactly one CIO JVM JAR"
        }
        require(cioArtifacts.single().coordinate.substringAfterLast(':') == VERSION) {
            "Wrong consumed CIO version"
        }
        require(cioArtifacts.single().sha256 == expectedJarSha256) { "Wrong actual consumed CIO JAR hash" }
    }
    fun describe(root: ResolvedComponentResult): CioRuntimeGraph {
        val components = sortedSetOf<String>()
        val unresolved = sortedSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component.id.displayName)) return
            (component.id as? ModuleComponentIdentifier)?.let { id ->
                components += "${id.group}:${id.module}:${id.version}"
            }
            component.dependencies.forEach { dependency ->
                when (dependency) {
                    is ResolvedDependencyResult -> visit(dependency.selected)
                    is UnresolvedDependencyResult -> unresolved += dependency.requested.displayName
                }
            }
        }
        visit(root)
        return CioRuntimeGraph(components.toList(), unresolved.toList())
    }
}

abstract class VerifyCioRuntimeSelectionTask : DefaultTask() {
    @get:Input abstract val expectedVersion: Property<String>
    @get:Input abstract val expectedGeneration: Property<String>
    @get:Input abstract val expectedJarSha256: Property<String>
    @get:Input abstract val repositorySubsetSha256: Property<String>
    @get:Input abstract val componentCoordinates: ListProperty<String>
    @get:Input abstract val unresolvedDependencies: ListProperty<String>
    @get:Input abstract val artifactCoordinatesAndPaths: ListProperty<String>
    @get:Classpath abstract val runtimeFiles: ConfigurableFileCollection
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val artifacts = artifactCoordinatesAndPaths.get().map { record ->
            val parts = record.split('\t', limit = 2)
            require(parts.size == 2) { "Malformed resolved artifact record" }
            val file = File(parts[1])
            require(file.isFile) { "Resolved runtime artifact is missing: $file" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            CioRuntimeArtifact(parts[0], file.absolutePath, digest.digest().joinToString("") { "%02x".format(it) })
        }
        val expected = mapOf("version" to expectedVersion.get(), "generation" to expectedGeneration.get(),
            "jvmJarSha256" to expectedJarSha256.get())
        val report = linkedMapOf<String, Any>("schemaVersion" to 1, "validation" to "pending", "expected" to expected,
            "repositorySubsetSha256" to repositorySubsetSha256.get(),
            "components" to componentCoordinates.get(), "unresolved" to unresolvedDependencies.get(),
            "artifacts" to artifacts.map { mapOf("coordinate" to it.coordinate, "path" to it.path, "sha256" to it.sha256) })
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)))
        CioRuntimeSelectionContract.validate(componentCoordinates.get(), unresolvedDependencies.get(), artifacts,
            expectedVersion.get(), expectedGeneration.get(), expectedJarSha256.get())
        report["validation"] = "passed"
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)))
    }
}

fun Project.configureCioRuntimeSelectionVerification(
    implementationConfiguration: String,
    runtimeConfiguration: String,
    guardedTasks: Set<String>,
    inputRecordFile: File = rootProject.file("gradle/oidfed-cio-inputs.json"),
) {
    val keys = listOf("oidfedCioVersion", "oidfedCioExpectedGeneration", "oidfedCioExpectedJarSha256")
    val properties = keys.mapNotNull { key -> providers.gradleProperty(key).orNull?.let { key to it } }.toMap()
    if (!inputRecordFile.exists() && properties.isEmpty()) return
    require(inputRecordFile.isFile && inputRecordFile.length() <= 4096) { "Missing or oversized consumed CIO input record" }
    val record = JsonSlurper().parseText(providers.fileContents(layout.file(providers.provider { inputRecordFile })).asText.get()) as? Map<*, *>
    val inline = providers.environmentVariable("WORKSPACE_REPOSITORY_POLICY").orNull
    val policyFile = providers.environmentVariable("WORKSPACE_REPOSITORY_POLICY_FILE").orNull
    val policyHash = providers.environmentVariable("WORKSPACE_REPOSITORY_POLICY_SHA256").orNull
    val encoded = if (policyFile != null || policyHash != null) {
        require(inline == null && policyFile != null && policyHash != null && Regex("[a-f0-9]{64}").matches(policyHash)) { "Ambiguous CIO routed policy" }
        val source = File(policyFile)
        require(source.isAbsolute && source.isFile && source.length() <= 8 * 1024 * 1024) { "Missing or oversized CIO routed policy" }
        val bytes = providers.fileContents(layout.file(providers.provider { source })).asBytes.get()
        val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actualHash == policyHash) { "CIO routed policy file changed" }
        bytes.toString(Charsets.UTF_8)
    } else inline ?: error("Missing normal CIO routed policy")
    val policy = JsonSlurper().parseText(encoded) as? Map<*, *>
    val consumed = CioConsumedInputs.validate(record, properties, policy) ?: error("Missing consumed CIO input")
    val requestedVersion = consumed.getValue("version")
    val generation = consumed.getValue("generation")
    val jarSha = consumed.getValue("jvmJarSha256")
    // Replace the existing catalog dependency's own require value, rather
    // than leaving an incompatible direct stable-version requirement beside
    // the reviewed development constraint. Only these two exact names match.
    configurations.getByName(implementationConfiguration).withDependencies {
        filterIsInstance<ExternalModuleDependency>().filter {
            it.group == "io.ktor" && it.name in CioRuntimeSelectionContract.NAMES
        }.forEach { dependency ->
            dependency.version { strictly(requestedVersion) }
        }
    }
    CioRuntimeSelectionContract.NAMES.forEach { name ->
        dependencies.constraints.add(implementationConfiguration, "io.ktor:$name:$requestedVersion") {
            version { strictly(requestedVersion) }
            because("Use the reviewed CIO owner only; preserve all neighboring Ktor coordinates")
        }
    }
    val configuration = configurations.getByName(runtimeConfiguration)
    val graph = configuration.incoming.resolutionResult.rootComponent.map { CioRuntimeSelectionContract.describe(it) }
    val check = tasks.register("verifyCioRuntimeSelection", VerifyCioRuntimeSelectionTask::class.java) {
        group = "verification"
        description = "Verify actual CIO runtime selection and consumed bytes against its accepted producer"
        expectedVersion.set(requestedVersion)
        expectedGeneration.set(generation)
        expectedJarSha256.set(jarSha)
        repositorySubsetSha256.set(consumed.getValue("repositorySubsetSha256"))
        componentCoordinates.set(graph.map { it.components })
        unresolvedDependencies.set(graph.map { it.unresolved })
        artifactCoordinatesAndPaths.set(configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.mapNotNull { artifact ->
                val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                if (id?.group == "io.ktor") "${id.group}:${id.module}:${id.version}\t${artifact.file.absolutePath}" else null
            }.sorted()
        })
        runtimeFiles.from(configuration)
        reportFile.set(layout.buildDirectory.file("reports/oidfed-cio/runtime-selection.json"))
    }
    tasks.matching { it.name in guardedTasks }.configureEach { dependsOn(check) }
}

require(project.path in setOf(
    ":modules:openid-federation-public-server-ktor",
    ":modules:openid-federation-admin-server-ktor",
)) { "CIO application verifier belongs only to the public and admin server applications" }
configureCioRuntimeSelectionVerification(
    "implementation", "runtimeClasspath",
    setOf("test", "check", "jar", "assemble", "startScripts", "installDist", "distTar", "distZip", "run"),
    inputRecordFile = rootProject.file("gradle/oidfed-cio-inputs.json"),
)
