plugins {
    alias(libs.plugins.springboot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinPluginSpring)
    application
}

group = "com.sphereon.oid.fed.server.admin"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(projects.modules.openidFederationCommon)
    implementation(libs.springboot.actuator)
    implementation(libs.springboot.web)
    implementation(libs.springboot.data.jdbc)
    implementation(libs.kotlin.reflect)
    testImplementation(libs.springboot.test)
    testImplementation(libs.testcontainer.junit)
    testImplementation(libs.springboot.testcontainer)
    testImplementation(libs.testcontainer.postgres)
    runtimeOnly(libs.postgres)
    runtimeOnly(libs.springboot.devtools)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        setExceptionFormat("full")
        events("started", "skipped", "passed", "failed")
        showStandardStreams = true
    }
}