# OpenID Federation Project Technologies

## Core Technologies

### Programming Languages & Platforms

- **Kotlin Multiplatform**: Primary development language supporting multiple targets (JVM, JS)
- **Java**: JVM target platform (Java 21)
- **JavaScript/TypeScript**: Web and Node.js support

### Build & Dependency Management

- **Gradle**: Build automation tool
- **Maven**: Artifact publishing and dependency management
- **NPM**: JavaScript package management and publishing

## Frameworks & Libraries

### Core Libraries

- **Ktor**: Multiplatform HTTP client/server framework
    - Client Core
    - Content Negotiation
    - Authentication
    - Logging
    - JSON Serialization

### Database & Persistence

- **SQLDelight**: SQL database with type-safe Kotlin APIs
- **PostgreSQL**: Primary database
- **HikariCP**: High-performance JDBC connection pool

### Security & Cryptography

- **Nimbus JOSE + JWT**: JSON Web Token (JWT) implementation
- **Local KMS**: Key Management System implementation

### Serialization

- **Kotlinx.serialization**: Kotlin multiplatform serialization
- **JSON Processing**: Native Kotlin JSON support

### Testing

- **Kotlin Test**: Testing framework
- **MockK**: Mocking framework for Kotlin
- **JUnit**: Testing framework for JVM
- **Mocha**: JavaScript testing framework
- **TestContainers**: Containerized testing support

### Logging & Monitoring

- **Kermit**: Multiplatform logging
- **Spring Boot Actuator**: Application monitoring and metrics

### Web Frameworks

- **Spring Boot**: Server-side framework
    - Spring Security
    - Spring Data JDBC
    - Spring OAuth2
    - Spring Web

### Development Tools

- **Spring Boot DevTools**: Development productivity tools
- **Kotlin Compiler Plugins**:
    - Serialization
    - Spring support
    - Multiplatform support

## Module Structure

### Core Modules

- **openid-federation-common**: Common utilities and base functionality
- **openid-federation-client**: Client implementation
- **openapi**: OpenAPI specification and generated code
- **services**: Core services implementation
- **persistence**: Data persistence layer
- **local-kms**: Key Management System
- **logger**: Logging infrastructure

### Server Modules

- **openid-federation-admin-server**: Administration interface
- **openid-federation-server**: Federation protocol implementation

## Publishing & Distribution

- Maven Publication
- NPM Package Publishing
- Multiple artifact formats (JVM, JS, Common)

## License

Apache License, Version 2.0
