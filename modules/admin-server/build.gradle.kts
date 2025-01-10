plugins {
    alias(libs.plugins.springboot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinPluginSpring)
    id("maven-publish")
    application
}

group = "com.sphereon.oid.fed.server.admin"

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
    implementation(libs.springboot.actuator) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation(libs.springboot.web)
    implementation(libs.springboot.data.jdbc)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.springboot.test)
    testImplementation(libs.testcontainer.junit)
    testImplementation(libs.springboot.testcontainer)
    testImplementation(libs.testcontainer.postgres)
    runtimeOnly(libs.postgres)
    runtimeOnly(libs.springboot.devtools)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.20")
    implementation("org.openapitools:jackson-databind-nullable:0.2.6")
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
                name.set("OpenID Federation Admin Server")
                description.set("Admin Server for OpenID Federation")
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
