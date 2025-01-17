plugins {
    alias(libs.plugins.springboot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinPluginSpring)
    id("maven-publish")
    application
}

group = "com.sphereon.oid.fed.server.federation"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(projects.modules.openapi)
    api(projects.modules.openidFederationCommon)
    api(projects.modules.persistence)
    api(projects.modules.services)
    implementation(libs.springboot.actuator)
    implementation(libs.springboot.web)
    implementation(libs.springboot.data.jdbc)
    implementation(libs.kotlin.reflect)
    testImplementation(libs.springboot.test)
    testImplementation(libs.testcontainer.junit)
    testImplementation(libs.springboot.testcontainer)
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            artifact(tasks.named("bootJar"))

            pom {
                name.set("OpenID Federation Server")
                description.set("Server for OpenID Federation")
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

tasks.named<Jar>("jar") {
    enabled = false
}
