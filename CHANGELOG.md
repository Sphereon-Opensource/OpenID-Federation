# Changelog

## 0.8.0 - 20250320

### Documentation

- **Postman Collection:** Added a new Postman collection to the documentation, providing ready-to-use API examples including OAuth2 integration.
- **Enhanced README:** Improved examples and instructions for:
    - Setting up environment variables (including guidance on copying the example file).
    - Deploying with Docker and Docker Compose.
    - Configuring the Key Management System with details for in-memory, AWS, and Azure setups.
- **Clarity and Consistency:** Revised text formatting and clarified descriptions for better readability.
- **Key Management Examples:** Updated examples to show usage of parameters like `kmsKeyRef` and provided guidance on signature algorithms.

### Deployment

- **Docker environment variables:** Unified usage of .env and .env.local files for environment values. Moved .env to .env.example as a user should create a .env or .env.local file
  themselves.
    - WARNING: This potentially means you will have to migrate/create the .env or .env.local file.

### API and codebase

- **JSON Payload Updates:** Standardized JSON keys (e.g., updated "dry-run" to "dryRun") in multiple API examples. Everything which is a param or response by default is camelCase.
  OIDF spec properties are snake_case.
- **Multiple Keys:** The system now allows to manage and use multiple keys. You can choose a specific key to sign/publish with using either the `kid` or `kmsKeyRef` request params.
  In the near future support for multiple KMS-es at the same time will also land.
- **Response forward compat:** Response now always return objects with potential arrays in them for list endpoints. This is done to ensure maximum future forward compatibility/extensibility
- **Migrations:** The DB should migrate to support the new kmsKeyRef values a user can set for keys. Existing keys will get their `kid` value for the `kmsKeyRef`
- **Dependency updates:** Moved to latest versions of Kotlin serialization, coroutines and ktor
- **Swagger UI:** The admin server now also hosts an OpenAPI UI in the form of Swagger UI at http://localhost:8081/swagger-ui/index.html to easy API testing
 
