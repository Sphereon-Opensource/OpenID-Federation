plugins {
	alias(libs.plugins.springboot)
	alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinPluginSpring)
    application
}

group = "com.sphereon.oid.fed"
version = "1.0.0"

java {
	toolchain {
        languageVersion = JavaLanguageVersion.of(21)
	}
}

dependencies {
    
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    runtimeOnly("org.springframework.boot:spring-boot-devtools")
    //testImplementation(libs.kotlin.test.junit)
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}