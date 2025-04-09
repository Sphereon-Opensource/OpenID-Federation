import com.android.build.gradle.internal.tasks.factory.dependsOn

plugins {
    alias(libs.plugins.springboot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinPluginSpring)
    id("maven-publish")
    application
}

tasks.register<Copy>("copyOpenAPI") {
    from("../openapi/src/commonMain/kotlin/com/sphereon/oid/fed/openapi/admin-server.yaml")
    into("src/main/resources/public")
}

tasks.processResources.dependsOn(":modules:openid-federation-admin-server:copyOpenAPI")

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
    }
    maven {
        url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
    }
    maven {
        url = uri("https://jitpack.io")
    }
}

sourceSets {
    main {
        java {
            srcDirs("../openid-federation-openapi/build/generated-java/src/main/java")
        }
    }
}


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(projects.modules.openidFederationOpenapi)
    api(projects.modules.openidFederationCommon)
    api(projects.modules.openidFederationServices)
    api(projects.modules.openidFederationLogger)

    implementation(libs.springboot.actuator) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    implementation(libs.springboot.oauth2.client)
    implementation(libs.springboot.security)
    implementation(libs.springboot.oauth2.resource.server)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.projectreactor.kotlin.extensions)
    implementation(libs.sphereon.kmp.cbor)
    implementation(libs.sphereon.kmp.crypto)
    implementation(libs.sphereon.kmp.crypto.kms)
    implementation(libs.sphereon.kmp.crypto.kms.azure)
    implementation(libs.kotlin.stdlib)
    implementation(libs.springboot.web)
    implementation(libs.springboot.data.jdbc)
    implementation(libs.springboot.validation)
    implementation(libs.kotlin.reflect)
    implementation(libs.whyoleg.cryptography.core)
    implementation(libs.springdoc.starter.webmvc.ui)
    testImplementation(libs.springboot.test)
    testImplementation(libs.testcontainer.junit)
    testImplementation(libs.springboot.testcontainer)
    testImplementation(libs.testcontainer.postgres)
    testImplementation(libs.spring.security.test)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.springboot.devtools)
    implementation(libs.ktor.serialization.kotlinx.json)
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
                url.set("https://github.com/Sphereon-Opensource/OpenID-Federation")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("compileKotlin").configure {
    dependsOn(":modules:openid-federation-openapi:copyFixedJavaToOutput")
}
